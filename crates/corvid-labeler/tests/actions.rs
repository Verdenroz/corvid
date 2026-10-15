mod common;

use std::cell::{Cell, RefCell};
use std::fs;
use std::io;
use std::path::PathBuf;

use common::{ARCHIVE, Fake, INBOX, JUNK, TRASH, scratch};
use corvid_labeler::{ActionLog, Candidate, Exit, poll_observed};

const USER: &str = "me@home.test";
const SENDER: &str = "billing@acme.test";
const T0: i64 = 100 * 86400;
const DAY: i64 = 86400;

struct Rig {
    _dir: tempfile::TempDir,
    state: PathBuf,
    actions_path: PathBuf,
    actions: RefCell<ActionLog>,
    clock: Cell<i64>,
    mailbox: Fake,
}

impl Rig {
    fn new() -> Self {
        let (dir, state) = scratch();
        let actions_path = state.with_file_name("actions.json");
        let rig = Self {
            _dir: dir,
            actions: RefCell::new(ActionLog::open(&actions_path).unwrap()),
            actions_path,
            state,
            clock: Cell::new(0),
            mailbox: Fake::default(),
        };
        rig.poll(T0 - DAY).unwrap();
        rig
    }

    fn poll(&self, now: i64) -> io::Result<()> {
        self.clock.set(now);
        poll_observed(&self.state, &self.mailbox, |delta| {
            self.actions
                .borrow_mut()
                .record(&self.mailbox, USER, now, delta)
        })
        .map(drop)
    }

    fn candidates(&self) -> Vec<Candidate> {
        self.actions
            .borrow_mut()
            .mine(self.clock.get())
            .unwrap()
            .to_vec()
    }

    /// One message a day for 21 days, each acted on by `act` a minute after it lands, then
    /// time passes until every one has settled.
    fn month(
        &self,
        from: &str,
        age: i64,
        thread: impl Fn(i64) -> String,
        act: impl Fn(&Fake, &str),
    ) {
        for day in 0..21 {
            let now = T0 + day * DAY;
            let id = format!("m{day}");
            self.mailbox.arrive(&id, from, &thread(day), now - age);
            self.poll(now).unwrap();
            act(&self.mailbox, &id);
            self.poll(now + 60).unwrap();
        }
        self.poll(T0 + 30 * DAY).unwrap();
    }
}

fn own_thread(day: i64) -> String {
    format!("t{day}")
}

fn archive_unread(mailbox: &Fake, id: &str) {
    mailbox.relocate(id, ARCHIVE);
}

#[test]
fn three_weeks_of_unread_archives_mint_one_candidate() {
    let rig = Rig::new();
    rig.month(SENDER, 0, own_thread, archive_unread);

    let found = rig.candidates();

    assert_eq!(found.len(), 1);
    assert_eq!(found[0].key, "from:billing@acme.test");
    assert_eq!(found[0].outcome, Exit::Archive);
    assert_eq!(rig.candidates(), found, "the id is stable across runs");
}

#[test]
fn trashing_unread_mail_mints_a_trash_candidate() {
    let rig = Rig::new();
    rig.month(SENDER, 0, own_thread, |mailbox, id| {
        mailbox.relocate(id, TRASH)
    });

    assert_eq!(rig.candidates()[0].outcome, Exit::Trash);
}

#[test]
fn deleting_unread_mail_outright_counts_as_trash() {
    let rig = Rig::new();
    rig.month(SENDER, 0, own_thread, |mailbox, id| mailbox.expunge(id));

    assert_eq!(rig.candidates()[0].outcome, Exit::Trash);
}

#[test]
fn an_inbox_zero_reader_who_reads_then_archives_mints_nothing() {
    let rig = Rig::new();
    rig.month(SENDER, 0, own_thread, |mailbox, id| {
        mailbox.read(id);
        mailbox.relocate(id, ARCHIVE);
    });

    assert!(rig.candidates().is_empty());
}

#[test]
fn a_one_day_burst_mints_nothing() {
    let rig = Rig::new();
    for n in 0..25 {
        rig.mailbox
            .arrive(&format!("b{n}"), SENDER, &format!("t{n}"), T0);
    }
    rig.poll(T0).unwrap();
    for n in 0..25 {
        rig.mailbox.relocate(&format!("b{n}"), ARCHIVE);
    }
    rig.poll(T0 + 60).unwrap();
    rig.poll(T0 + 5 * DAY).unwrap();

    assert!(rig.candidates().is_empty());
}

#[test]
fn imported_old_mail_is_never_observed() {
    let rig = Rig::new();
    rig.month(SENDER, 3 * 3600, own_thread, archive_unread);

    assert!(rig.candidates().is_empty());
}

#[test]
fn mail_filed_elsewhere_at_delivery_is_never_observed() {
    let rig = Rig::new();
    for day in 0..21 {
        let id = format!("m{day}");
        rig.mailbox
            .arrive(&id, SENDER, &own_thread(day), T0 + day * DAY);
        rig.mailbox.relocate(&id, ARCHIVE);
        rig.poll(T0 + day * DAY).unwrap();
    }
    rig.poll(T0 + 30 * DAY).unwrap();

    assert!(rig.candidates().is_empty());
}

#[test]
fn an_undo_inside_the_settle_window_counts_against() {
    let rig = Rig::new();
    for day in 0..21 {
        let now = T0 + day * DAY;
        let id = format!("m{day}");
        rig.mailbox.arrive(&id, SENDER, &own_thread(day), now);
        rig.poll(now).unwrap();
        rig.mailbox.relocate(&id, ARCHIVE);
        rig.poll(now + 60).unwrap();
        rig.mailbox.relocate(&id, INBOX);
        rig.poll(now + 120).unwrap();
    }
    rig.poll(T0 + 30 * DAY).unwrap();

    assert!(rig.candidates().is_empty());
}

#[test]
fn arrivals_read_within_a_minute_count_against() {
    let rig = Rig::new();
    for day in 0..40 {
        let now = T0 + day * DAY;
        let id = format!("m{day}");
        rig.mailbox.arrive(&id, SENDER, &own_thread(day), now);
        if day % 4 == 0 {
            rig.mailbox.read(&id);
        }
        rig.poll(now).unwrap();
        rig.mailbox.relocate(&id, ARCHIVE);
        rig.poll(now + 60).unwrap();
    }
    rig.poll(T0 + 50 * DAY).unwrap();

    assert!(rig.candidates().is_empty());
}

#[test]
fn old_positives_age_out_of_the_window() {
    let rig = Rig::new();
    rig.month(SENDER, 0, own_thread, archive_unread);
    assert_eq!(rig.candidates().len(), 1);

    rig.poll(T0 + 300 * DAY).unwrap();

    assert!(rig.candidates().is_empty());
}

#[test]
fn mail_moved_to_junk_counts_for_neither_side() {
    let rig = Rig::new();
    rig.month(SENDER, 0, own_thread, |mailbox, id| {
        mailbox.relocate(id, JUNK)
    });

    assert!(rig.candidates().is_empty());
}

#[test]
fn the_users_own_mail_is_ignored() {
    let rig = Rig::new();
    rig.month(USER, 0, own_thread, archive_unread);

    assert!(rig.candidates().is_empty());
}

#[test]
fn a_long_thread_counts_once() {
    let rig = Rig::new();
    rig.month(SENDER, 0, |_| "one-thread".to_owned(), archive_unread);

    assert!(rig.candidates().is_empty());
}

#[test]
fn a_corrupt_actions_file_is_refused_at_open() {
    let rig = Rig::new();
    fs::write(&rig.actions_path, "garbage").unwrap();

    let error = ActionLog::open(&rig.actions_path).err().unwrap();

    assert_eq!(error.kind(), io::ErrorKind::InvalidData);
}

#[test]
fn an_idle_poll_writes_nothing() {
    let rig = Rig::new();

    rig.poll(T0).unwrap();

    assert!(!rig.actions_path.exists());
}

#[test]
fn another_action_log_failure_does_not_stop_labeling() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    common::taught(&path, &mailbox);
    mailbox.deliver("new", SENDER, &[], 12.0);

    poll_observed(&path, &mailbox, |_| Err(io::Error::other("disk full"))).unwrap();

    assert!(mailbox.keywords_of("new").contains("corvid-receipts"));
}
