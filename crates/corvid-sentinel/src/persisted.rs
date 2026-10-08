use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, PoisonError};

/// State kept in memory and in a file, where the file is written before the memory changes.
pub(crate) struct Persisted<T> {
    path: PathBuf,
    save: fn(&Path, &T) -> io::Result<()>,
    // A poisoned lock means a prior request panicked mid-decision; the state it left behind is
    // still whatever the last successful commit wrote, so it is safe to keep using.
    value: Mutex<T>,
}

impl<T> Persisted<T> {
    pub(crate) fn new(path: PathBuf, value: T, save: fn(&Path, &T) -> io::Result<()>) -> Self {
        Self {
            path,
            save,
            value: Mutex::new(value),
        }
    }

    /// Reads under the lock, so a caller takes out only the part it needs.
    pub(crate) fn read<R>(&self, read: impl FnOnce(&T) -> R) -> R {
        read(&self.value.lock().unwrap_or_else(PoisonError::into_inner))
    }

    /// Applies `change` to the current state and persists the result before it becomes
    /// current, so a failed save leaves the state, and so a retried request, as it was. `change`
    /// answers with the next state, or `None` for nothing to commit, and a result.
    pub(crate) fn commit<R>(&self, change: impl FnOnce(&T) -> (Option<T>, R)) -> io::Result<R> {
        let mut value = self.value.lock().unwrap_or_else(PoisonError::into_inner);
        let (next, result) = change(&value);
        if let Some(next) = next {
            (self.save)(&self.path, &next)?;
            *value = next;
        }
        Ok(result)
    }
}
