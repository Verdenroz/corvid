use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::mailbox::Cursors;

/// Bump whenever `State`'s shape changes, and keep a migration from the version it leaves.
const SCHEMA: u32 = 1;

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct State {
    /// `None` until the first poll adopts the server's position.
    pub cursors: Option<Cursors>,
    /// Threads to judge again, kept until judged so a model that is off loses none.
    pub waiting: BTreeSet<String>,
    /// Thread to the input hash whose answer came back empty or unfinished, so the same
    /// messages are not asked about every pass.
    pub tried: BTreeMap<String, String>,
}

corvid_store::pin_shape!(Cursors { email, thread });
corvid_store::pin_shape!(State {
    cursors,
    waiting,
    tried
});

pub fn open(path: &Path) -> io::Result<State> {
    corvid_store::open(path, SCHEMA)
}

pub fn save(path: &Path, state: &State) -> io::Result<()> {
    corvid_store::save(path, SCHEMA, state)
}
