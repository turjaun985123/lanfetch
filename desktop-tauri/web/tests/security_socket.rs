//! Socket-level security regression tests (docs/SECURITY.md,
//! "Security regression tests"): boot the real `lanfetch-web`
//! binary in --lan mode with a throwaway HOME and drive it with
//! raw HTTP over TcpStream. A single test with sequential
//! phases, so the phases cannot race each other; the handler-
//! level pins live in serve.rs's unit tests.
//!
//! Covered here because they need the real accept loop or the
//! real process: item 1/2 end-to-end auth over the wire, item 4
//! (the MAX_CONN accept-loop cap and recovery after the flood
//! drains), item 7 end-to-end, item 8/9 over the wire.
//! Not covered: item 5's 30 s slow-loris deadline (a hard
//! constant — exercising it would take longer than the whole
//! suite's budget) and items 6/10/11, which belong to the
//! engine, the Android suite, and CI respectively.

use std::io::{Read, Write};
use std::net::TcpStream;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

const PORT: u16 = 18787;
const ADDR: &str = "127.0.0.1:18787";

struct ChildGuard(Child);

impl Drop for ChildGuard {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

/// Send one raw request and read the whole response (the server
/// closes every non-SSE response). None on any I/O failure.
fn send(raw: &str) -> Option<String> {
    let mut stream = TcpStream::connect(ADDR).ok()?;
    stream.set_read_timeout(Some(Duration::from_secs(5))).ok()?;
    stream.write_all(raw.as_bytes()).ok()?;
    let mut buf = Vec::new();
    let _ = stream.read_to_end(&mut buf);
    Some(String::from_utf8_lossy(&buf).into_owned())
}

fn get(path: &str, token: Option<&str>) -> String {
    let mut raw = format!("GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{PORT}\r\n");
    if let Some(t) = token {
        raw.push_str(&format!("Authorization: Bearer {t}\r\n"));
    }
    raw.push_str("\r\n");
    send(&raw).unwrap_or_default()
}

fn wait_until(deadline: Duration, mut cond: impl FnMut() -> bool) -> bool {
    let started = Instant::now();
    while started.elapsed() < deadline {
        if cond() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    cond()
}

#[test]
fn lan_mode_socket_security() {
    // A fresh throwaway HOME: the server creates its token and
    // all of its state under it, and nothing real is touched.
    let home: PathBuf =
        std::env::temp_dir().join(format!("lanfetch-web-socket-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&home);
    std::fs::create_dir_all(&home).unwrap();

    let child = Command::new(env!("CARGO_BIN_EXE_lanfetch-web"))
        .args(["--port", &PORT.to_string(), "--lan"])
        .env("HOME", &home)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .expect("spawn lanfetch-web");
    let _child = ChildGuard(child);

    // Wait for the listener, then for the token file run()
    // creates at startup.
    assert!(
        wait_until(Duration::from_secs(10), || TcpStream::connect(ADDR).is_ok()),
        "server did not start listening on {ADDR}"
    );
    let token_path = home.join(".local/share/lanfetch/web-token");
    let mut token = String::new();
    assert!(
        wait_until(Duration::from_secs(5), || {
            match std::fs::read_to_string(&token_path) {
                Ok(t) if t.trim().len() >= 32 => {
                    token = t.trim().to_string();
                    true
                }
                _ => false,
            }
        }),
        "server did not create its web token"
    );

    // Item 1/2: the health probe is the one unauthenticated
    // route; everything else 401s without a token and with a
    // wrong one, and succeeds with the real one.
    let r = get("/api/v1/health", None);
    assert!(r.starts_with("HTTP/1.1 200"), "{r}");
    let r = get("/api/v1/downloads", None);
    assert!(r.starts_with("HTTP/1.1 401"), "{r}");
    let r = get("/api/v1/downloads", Some("definitely-the-wrong-token"));
    assert!(r.starts_with("HTTP/1.1 401"), "{r}");
    let r = get("/api/v1/downloads", Some(&token));
    assert!(r.starts_with("HTTP/1.1 200"), "{r}");

    // Item 1, second half: an unauthenticated mutation changes
    // nothing.
    let add_body = "{\"name\":\"SocketProbe\",\"rootUrl\":\"http://127.0.0.1:9/\"}";
    let add = format!(
        "POST /api/v1/sources HTTP/1.1\r\nHost: 127.0.0.1:{PORT}\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{add_body}",
        add_body.len()
    );
    let r = send(&add).unwrap_or_default();
    assert!(r.starts_with("HTTP/1.1 401"), "{r}");
    let r = get("/api/v1/sources", Some(&token));
    assert!(r.starts_with("HTTP/1.1 200"), "{r}");
    assert!(!r.contains("SocketProbe"), "{r}");

    // Item 7: a spoofed Host is refused even with a valid token.
    let r = send(&format!(
        "GET /api/v1/downloads HTTP/1.1\r\nHost: evil.example.com\r\nAuthorization: Bearer {token}\r\n\r\n"
    ))
    .unwrap_or_default();
    assert!(r.starts_with("HTTP/1.1 403"), "{r}");

    // Item 8: the web bootstrap carries display-shaped paths
    // only — never the (absolute) throwaway HOME or any real one.
    let r = send(&format!(
        "POST /api/get_bootstrap HTTP/1.1\r\nHost: 127.0.0.1:{PORT}\r\nContent-Type: application/json\r\nAuthorization: Bearer {token}\r\nContent-Length: 2\r\n\r\n{{}}"
    ))
    .unwrap_or_default();
    assert!(r.starts_with("HTTP/1.1 200"), "{r}");
    assert!(r.contains("~/Downloads/LanFetch"), "{r}");
    assert!(
        !r.contains(&*home.to_string_lossy()),
        "bootstrap leaked an absolute path: {r}"
    );

    // Item 9: security headers over the wire — nosniff and
    // referrer-policy everywhere, no-store on JSON, the CSP on
    // HTML — and static traversal (literal and encoded) 404s.
    let r = get("/", None);
    let head = r.to_lowercase();
    assert!(r.starts_with("HTTP/1.1 200"), "{r}");
    assert!(head.contains("x-content-type-options: nosniff"), "{r}");
    assert!(head.contains("referrer-policy: no-referrer"), "{r}");
    assert!(head.contains("content-security-policy:"), "{r}");
    let r = get("/api/v1/downloads", Some(&token));
    assert!(r.to_lowercase().contains("cache-control: no-store"), "{r}");
    for path in ["/../secret.txt", "/%2e%2e/secret.txt"] {
        let r = get(path, None);
        assert!(r.starts_with("HTTP/1.1 404"), "{path}: {r}");
    }

    // Item 4: hold MAX_CONN (32) connections open with partial
    // heads; the accept loop must refuse the next one with 503
    // instead of spawning unbounded threads — and once the
    // flood drains, the server answers normally again.
    let mut held: Vec<TcpStream> = Vec::new();
    for _ in 0..32 {
        let mut stream = TcpStream::connect(ADDR).expect("flood connect");
        stream
            .write_all(b"GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n")
            .expect("flood write");
        held.push(stream);
    }
    assert!(
        wait_until(Duration::from_secs(10), || {
            send("GET /api/v1/health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                .map(|r| r.starts_with("HTTP/1.1 503"))
                .unwrap_or(false)
        }),
        "the 33rd connection was not refused with 503"
    );
    drop(held);
    assert!(
        wait_until(Duration::from_secs(10), || {
            get("/api/v1/health", None).starts_with("HTTP/1.1 200")
        }),
        "server did not recover after the flood drained"
    );
}
