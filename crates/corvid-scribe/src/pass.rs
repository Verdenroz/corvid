use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::Path;
use std::time::{Duration, Instant};

use corvid_model::{Model, Written};

use crate::mailbox::{Folder, Mail, Mailbox};
use crate::prompt::{self, SYSTEM};
use crate::sentinel::{Sink, Upsert};
use crate::state::{open, save};

const MAX_SUMMARY_CHARS: usize = 2000;

/// Junk and trash never reach the model, whatever else is excluded.
const ALWAYS_EXCLUDED: [&str; 2] = ["junk", "trash"];

/// The model the pass asks, so a pass can be exercised without a model host.
pub trait Writer {
    fn write(&self, system: &str, user: &str, max_tokens: u32) -> io::Result<Written>;
}

impl Writer for Model {
    fn write(&self, system: &str, user: &str, max_tokens: u32) -> io::Result<Written> {
        Model::write(self, system, user, max_tokens)
    }
}

pub struct Settings {
    pub exclude_mailboxes: Vec<String>,
    pub min_messages: usize,
    pub long_body_chars: usize,
    pub max_input_chars: usize,
    pub model: String,
    pub max_tokens: u32,
    /// Threads asked about per pass.
    pub budget: usize,
    pub deadline: Duration,
}

#[derive(Debug, Default, PartialEq, Eq)]
pub struct Summary {
    pub stored: usize,
    pub unusable: usize,
    pub deleted: usize,
    pub waiting: usize,
}

enum Verdict {
    /// Already summarized or already judged for exactly these messages.
    Current,
    /// No message left.
    Gone,
    /// Too little thread to summarize.
    Short {
        hash: String,
    },
    Ask {
        hash: String,
        mails: Vec<Mail>,
    },
}

/// Collects what changed, then asks the model about the waiting threads that need a summary.
///
/// A thread leaves the waiting set once it is settled: summarized, unusable, current, short or gone.
/// The model not answering ends the pass with the rest still waiting. Nothing past the cursors
/// is saved until the sentinel has taken the results, so a failed post repeats.
pub fn run(
    path: &Path,
    mailbox: &impl Mailbox,
    sink: &impl Sink,
    writer: &impl Writer,
    settings: &Settings,
    now: i64,
) -> io::Result<Summary> {
    let mut state = open(path)?;
    let changes = mailbox.changes(state.cursors.as_ref())?;
    state.waiting.extend(changes.threads);
    state.cursors = Some(changes.cursors);
    save(path, &state)?;
    if state.waiting.is_empty() {
        return Ok(Summary::default());
    }

    let held = sink.manifest(now)?;
    let excluded = excluded_folders(&mailbox.folders()?, &settings.exclude_mailboxes);
    let started = Instant::now();
    let mut asked = 0;
    let mut summary = Summary::default();
    let mut upserts = Vec::new();
    let mut deletes = Vec::new();

    for id in state.waiting.clone() {
        match judge(mailbox, &id, &held, &state.judged, &excluded, settings)? {
            Verdict::Current => {
                state.waiting.remove(&id);
            }
            verdict @ (Verdict::Gone | Verdict::Short { .. }) => {
                state.waiting.remove(&id);
                match verdict {
                    Verdict::Short { hash } => state.judged.insert(id.clone(), hash),
                    _ => state.judged.remove(&id),
                };
                if held.contains_key(&id) {
                    deletes.push(id);
                    summary.deleted += 1;
                }
            }
            Verdict::Ask { hash, mails } => {
                if asked == settings.budget || started.elapsed() >= settings.deadline {
                    break;
                }
                asked += 1;
                let user = prompt::user(&mails, settings.max_input_chars);
                let Ok(written) = writer
                    .write(SYSTEM, &user, settings.max_tokens)
                    .inspect_err(|error| tracing::info!(%error, "the model did not answer"))
                else {
                    break;
                };
                state.waiting.remove(&id);
                match usable(written) {
                    Some(text) => {
                        state.judged.remove(&id);
                        upserts.push(Upsert {
                            thread_id: id,
                            input_hash: hash,
                            text,
                            model: settings.model.clone(),
                            generated_at: now,
                        });
                        summary.stored += 1;
                    }
                    None => {
                        state.judged.insert(id, hash);
                        summary.unusable += 1;
                    }
                }
            }
        }
    }

    if !upserts.is_empty() || !deletes.is_empty() {
        sink.post(now, &upserts, &deletes)?;
    }
    save(path, &state)?;
    summary.waiting = state.waiting.len();
    Ok(summary)
}

/// Bodies are fetched only for a thread whose messages differ from what is held or judged, and
/// a thread under `min_messages` needs them once to know whether one is long.
fn judge(
    mailbox: &impl Mailbox,
    id: &str,
    held: &BTreeMap<String, String>,
    judged: &BTreeMap<String, String>,
    excluded: &BTreeSet<String>,
    settings: &Settings,
) -> io::Result<Verdict> {
    let kept: Vec<String> = mailbox
        .members(id)?
        .into_iter()
        .filter(|member| {
            !member
                .mailbox_ids
                .iter()
                .any(|folder| excluded.contains(folder))
        })
        .map(|member| member.id)
        .collect();
    if kept.is_empty() {
        return Ok(Verdict::Gone);
    }
    let hash = prompt::input_hash(&kept);
    if held.get(id) == Some(&hash) || judged.get(id) == Some(&hash) {
        return Ok(Verdict::Current);
    }

    let mails = mailbox.mails(&kept)?;
    let long = mails
        .iter()
        .any(|mail| mail.body.chars().count() > settings.long_body_chars);
    if kept.len() < settings.min_messages && !long {
        return Ok(Verdict::Short { hash });
    }
    Ok(Verdict::Ask { hash, mails })
}

fn excluded_folders(folders: &[Folder], exclude: &[String]) -> BTreeSet<String> {
    let named =
        |folder: &Folder, name: &str| folder.name == name || folder.role.as_deref() == Some(name);
    folders
        .iter()
        .filter(|folder| {
            ALWAYS_EXCLUDED.iter().any(|name| named(folder, name))
                || exclude.iter().any(|name| named(folder, name))
        })
        .map(|folder| folder.id.clone())
        .collect()
}

/// The summary text, or `None` for an answer not worth keeping. A reply opening with `<think>`
/// is a reasoning model that was not told to stop thinking, and its reasoning is not a summary.
fn usable(written: Written) -> Option<String> {
    let text = written.text.trim();
    if !written.finished || text.is_empty() || text.starts_with("<think>") {
        return None;
    }
    Some(text.chars().take(MAX_SUMMARY_CHARS).collect())
}
