//! `corvid-config check` validates the config file; `corvid-config shell` prints its `server`
//! section as shell assignments, so the deploy scripts share the services' parser and checks.
//! `corvid-config mta-sts-policy` prints the MTA-STS policy file the Cloudflare Worker serves.
//! `corvid-config assetlinks` prints the `assetlinks.json` that lets the Android apps use passkeys.

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
        Some("assetlinks") => {
            print!("{}", or_exit(config.assetlinks()));
            ExitCode::SUCCESS
        }
        Some("mta-sts-policy") => {
            print!("{}", or_exit(config.mta_sts_policy()));
            ExitCode::SUCCESS
        }
        _ => {
            eprintln!("usage: corvid-config check | shell | mta-sts-policy | assetlinks");
            ExitCode::from(64)
        }
    }
}
