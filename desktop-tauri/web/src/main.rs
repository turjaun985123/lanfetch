//! LanFetch Web — a single self-contained binary: the LanFetch
//! engine plus the embedded web UI. No GUI libraries, no display
//! needed; run it on any Linux machine and open the printed
//! address in a browser.
//!
//! Usage: lanfetch-web [--port 8787] [--lan]
//!   --port N   listen port (default 8787)
//!   --lan      listen on all interfaces so other devices on the
//!              network (your phone) can reach it. Without it the
//!              server is local-only. With it, API calls require
//!              the install's access token — the startup banner
//!              prints a pairing link that carries it.

mod serve;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let port = args
        .windows(2)
        .find(|w| w[0] == "--port")
        .and_then(|w| w[1].parse::<u16>().ok())
        .unwrap_or(8787);
    let lan = args.iter().any(|a| a == "--lan");
    serve::run(port, lan);
}
