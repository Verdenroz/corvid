//! `corvid-config check` validates the config file; `corvid-config shell` prints its `server`
//! section as shell assignments, so the deploy scripts share the services' parser and checks.

use std::process::ExitCode;

use corvid_config::{Config, or_exit};

fn main() -> ExitCode {
    let command = std::env::args().nth(1);
    let config = or_exit(Config::load());
    match command.as_deref() {
        Some("check") => ExitCode::SUCCESS,
        Some("shell") => {
            print!("{}", or_exit(config.shell()));
            ExitCode::SUCCESS
        }
        _ => {
            eprintln!("usage: corvid-config check | shell");
            ExitCode::from(64)
        }
    }
}
