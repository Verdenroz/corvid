//! With no arguments, the nightly path: sync, audit, then the ciphertext-only verify, neither
//! of which ever touch the age identity. `verify --deep` is run by hand.

use std::path::Path;
use std::process::ExitCode;

use corvid_config::{Config, or_exit, secret, section};
use corvid_mirror::crypto::{self, Identities};
use corvid_mirror::{Mailbox, audit, encrypt_archive, sync, verify, verify_deep};

fn main() -> ExitCode {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "corvid_mirror=info".into()),
        )
        .init();

    let mut args = std::env::args().skip(1);
    let config = or_exit(Config::load());
    let mirror = or_exit(section(config.mirror, "mirror"));
    let root = mirror.root;

    match args.next().as_deref() {
        None => nightly(
            &root,
            &mirror.jmap_url,
            &mirror.jmap_user,
            &mirror.age_recipient,
        ),
        Some("verify") => match args.next().as_deref() {
            None => shallow_verify(&root),
            Some("--deep") => match args.next() {
                Some(identity_path) => deep_verify(&root, Path::new(&identity_path)),
                None => usage(),
            },
            Some(_) => usage(),
        },
        Some("encrypt-archive") => encrypt(&root, &mirror.age_recipient),
        Some(_) => usage(),
    }
}

fn usage() -> ExitCode {
    eprintln!("usage: corvid-mirror [verify [--deep <identity-file>] | encrypt-archive]");
    ExitCode::from(64)
}

fn identities(path: &Path) -> Result<Identities, ExitCode> {
    crypto::load_identities(path).map_err(|error| {
        tracing::error!(%error, "cannot read the age identity");
        ExitCode::FAILURE
    })
}

fn nightly(root: &Path, url: &str, user: &str, age_recipient: &str) -> ExitCode {
    let password = or_exit(secret("CORVID_JMAP_PASSWORD"));
    let recipient = match crypto::parse_recipient(age_recipient) {
        Ok(recipient) => recipient,
        Err(error) => {
            tracing::error!(%error, "mirror.age_recipient");
            return ExitCode::from(corvid_config::EX_CONFIG);
        }
    };

    let mailbox = match Mailbox::connect(url, user, &password) {
        Ok(mailbox) => mailbox,
        Err(error) => {
            tracing::error!(%error, "cannot reach the mail server");
            return ExitCode::FAILURE;
        }
    };

    // Sync cannot see a message that never arrived, audit cannot see damage to what did, and
    // only verify runs without the server.
    let fetched = match sync(root, &mailbox, &recipient) {
        Ok(summary) => summary,
        Err(error) => {
            tracing::error!(%error, "sync failed");
            return ExitCode::FAILURE;
        }
    };
    tracing::info!(
        fetched = fetched.fetched,
        already_held = fetched.already_held,
        "synced"
    );
    for id in &fetched.unavailable {
        tracing::warn!(%id, "listed but not returned; deleted since, or a fetch that failed");
    }

    let report = match audit(root, &mailbox) {
        Ok(report) => report,
        Err(error) => {
            tracing::error!(%error, "audit failed");
            return ExitCode::FAILURE;
        }
    };
    for id in &report.vanished {
        tracing::info!(%id, "no longer on the server; the copy here is kept");
    }
    if !report.missing.is_empty() {
        tracing::error!(
            missing = report.missing.len(),
            first = %report.missing[0],
            "the server holds messages this archive does not"
        );
        return ExitCode::FAILURE;
    }

    shallow_verify(root)
}

fn shallow_verify(root: &Path) -> ExitCode {
    match verify(root) {
        Ok(damaged) if damaged.is_empty() => {
            tracing::info!("archive verified");
            ExitCode::SUCCESS
        }
        Ok(damaged) => {
            tracing::error!(damaged = damaged.len(), first = %damaged[0], "stored ciphertext does not match its digest");
            ExitCode::FAILURE
        }
        Err(error) => {
            tracing::error!(%error, "verification failed");
            ExitCode::FAILURE
        }
    }
}

fn deep_verify(root: &Path, identity_path: &Path) -> ExitCode {
    let identities = match identities(identity_path) {
        Ok(identities) => identities,
        Err(code) => return code,
    };
    match verify_deep(root, &identities) {
        Ok(damaged) if damaged.is_empty() => {
            tracing::info!("archive decrypts and matches every plaintext digest and size");
            ExitCode::SUCCESS
        }
        Ok(damaged) => {
            tracing::error!(damaged = damaged.len(), first = %damaged[0], "a stored body does not decrypt to its recorded digest or size");
            ExitCode::FAILURE
        }
        Err(error) => {
            tracing::error!(%error, "deep verification failed");
            ExitCode::FAILURE
        }
    }
}

fn encrypt(root: &Path, age_recipient: &str) -> ExitCode {
    let recipient = match crypto::parse_recipient(age_recipient) {
        Ok(recipient) => recipient,
        Err(error) => {
            tracing::error!(%error, "mirror.age_recipient");
            return ExitCode::from(corvid_config::EX_CONFIG);
        }
    };
    match encrypt_archive(root, &recipient) {
        Ok(migrated) => {
            tracing::info!(migrated, "archive encrypted");
            ExitCode::SUCCESS
        }
        Err(error) => {
            tracing::error!(%error, "encrypt-archive failed");
            ExitCode::FAILURE
        }
    }
}
