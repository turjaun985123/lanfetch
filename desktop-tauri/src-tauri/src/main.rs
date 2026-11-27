#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|a| a == "--serve") {
        // Web mode: `lanfetch --serve [--port 8787] [--lan]` serves
        // the same app to a browser. The GUI never starts.
        let port = args
            .windows(2)
            .find(|w| w[0] == "--port")
            .and_then(|w| w[1].parse::<u16>().ok())
            .unwrap_or(8787);
        let lan = args.iter().any(|a| a == "--lan");
        lanfetch_tauri::serve::run(port, lan);
    }
    lanfetch_tauri::run();
}
