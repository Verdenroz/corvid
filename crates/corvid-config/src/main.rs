//! `corvid-config check` validates the config file; `corvid-config shell` prints its `server`
//! section as shell assignments, so the deploy scripts share the services' parser and checks.
//! `corvid-config mta-sts-policy` prints the MTA-STS policy file the Cloudflare Worker serves.

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
        Some("mta-sts-policy") => {
            print!("{}", or_exit(config.mta_sts_policy()));
            ExitCode::SUCCESS
        }
        _ => {
            eprintln!("usage: corvid-config check | shell | mta-sts-policy");
            ExitCode::from(64)
        }
    }
}
