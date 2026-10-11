//! A device's own connection for previews: the same encrypted bodies FCM would carry, held in a
//! short in-memory queue and served over SSE to the device whose token hashes to its push record.

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::response::IntoResponse;
use axum::response::Response;
use axum::response::sse::{Event, KeepAlive, Sse};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use futures_util::stream;
use sha2::{Digest, Sha256};
use tokio::sync::Notify;

use super::Shared;
use super::state::{Devices, Push, Target};

pub(super) const ENDPOINT: &str = "corvid-stream";
const MAX_QUEUED: usize = 50;
const MAX_AGE: Duration = Duration::from_secs(24 * 3600);
const KEEPALIVE: Duration = Duration::from_secs(240);

pub(super) fn hash(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

struct Queued {
    id: u64,
    at: Instant,
    data: String,
}

#[derive(Default)]
struct Inner {
    last: u64,
    events: VecDeque<Queued>,
    closed: bool,
}

#[derive(Default)]
struct Queue {
    inner: Mutex<Inner>,
    wake: Notify,
}

impl Queue {
    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner.lock().expect("the queue lock is not poisoned")
    }

    fn push(&self, data: String) {
        let mut inner = self.lock();
        // Ids follow the clock so they keep rising across a sentinel restart, which a device
        // resuming from `Last-Event-ID` would otherwise read as already seen.
        let millis = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("the clock is after 1970")
            .as_millis() as u64;
        inner.last = (inner.last + 1).max(millis);
        let id = inner.last;
        inner.events.push_back(Queued {
            id,
            at: Instant::now(),
            data,
        });
        while inner.events.len() > MAX_QUEUED {
            inner.events.pop_front();
        }
        drop(inner);
        self.wake.notify_waiters();
    }

    fn close(&self) {
        self.lock().closed = true;
        self.wake.notify_waiters();
    }

    /// Waits for the next event after `last`; `None` once the queue is closed.
    async fn next(&self, last: u64) -> Option<(u64, String)> {
        loop {
            let notified = self.wake.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            match self.after(last) {
                Err(()) => return None,
                Ok(Some(event)) => return Some(event),
                Ok(None) => notified.await,
            }
        }
    }

    /// The oldest event after `last` that is still within a day, or `Err` once closed.
    fn after(&self, last: u64) -> Result<Option<(u64, String)>, ()> {
        let inner = self.lock();
        if inner.closed {
            return Err(());
        }
        Ok(inner
            .events
            .iter()
            .find(|event| event.id > last && event.at.elapsed() <= MAX_AGE)
            .map(|event| (event.id, event.data.clone())))
    }
}

#[derive(Default)]
pub(super) struct Streams {
    queues: Mutex<HashMap<String, Arc<Queue>>>,
}

impl Streams {
    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, Arc<Queue>>> {
        self.queues
            .lock()
            .expect("the streams lock is not poisoned")
    }

    fn queue(queues: &mut HashMap<String, Arc<Queue>>, name: &str) -> Arc<Queue> {
        queues.entry(name.to_owned()).or_default().clone()
    }

    /// The device's queue, unless `valid` fails. It runs under the same lock as [`Self::close`],
    /// so a stream cannot open on a queue that a revoke has already swept.
    fn open(&self, name: &str, valid: impl FnOnce() -> bool) -> Option<Arc<Queue>> {
        let mut queues = self.lock();
        valid().then(|| Self::queue(&mut queues, name))
    }

    pub(super) fn publish(&self, name: &str, body: &[u8]) {
        let queue = Self::queue(&mut self.lock(), name);
        queue.push(URL_SAFE_NO_PAD.encode(body));
    }

    /// Ends the device's open stream and drops what was queued for it.
    pub(super) fn close(&self, name: &str) {
        let queue = self.lock().remove(name);
        if let Some(queue) = queue {
            queue.close();
        }
    }
}

/// Whether `name` still holds the stream registration that `token_hash` belongs to.
fn holds(devices: &Devices, name: &str, token_hash: &str) -> bool {
    devices.approved.get(name).is_some_and(|device| {
        matches!(
            &device.push,
            Some(Push { target: Target::Stream { token_hash: held }, .. }) if held == token_hash
        )
    })
}

/// `GET /corvid/push/stream`. The bearer token is the credential: it was issued at registration
/// and only its hash is kept, so a locked phone with no session can still hold it.
pub(super) async fn serve(State(state): State<Shared>, headers: HeaderMap) -> Response {
    let hashed = headers
        .get("Authorization")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .map(hash);
    let device = hashed.as_deref().and_then(|hashed| {
        state.devices.read(|devices| {
            devices
                .approved
                .keys()
                .find(|name| holds(devices, name, hashed))
                .cloned()
        })
    });
    let (Some(name), Some(hashed)) = (device, hashed) else {
        return StatusCode::UNAUTHORIZED.into_response();
    };
    let still_theirs = || state.devices.read(|devices| holds(devices, &name, &hashed));
    let Some(queue) = state.streams.open(&name, still_theirs) else {
        return StatusCode::UNAUTHORIZED.into_response();
    };
    let resume = headers
        .get("Last-Event-ID")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<u64>().ok())
        .unwrap_or(0);
    let events = stream::unfold((queue, resume), |(queue, last)| async move {
        let (id, data) = queue.next(last).await?;
        let event = Event::default().id(id.to_string()).event("push").data(data);
        Some((Ok::<_, std::convert::Infallible>(event), (queue, id)))
    });
    let mut response = Sse::new(events)
        .keep_alive(KeepAlive::new().interval(KEEPALIVE).text("keepalive"))
        .into_response();
    response.headers_mut().insert(
        "X-Accel-Buffering",
        "no".parse().expect("a static header value"),
    );
    response
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_newest_fifty_are_kept() {
        let queue = Queue::default();
        for n in 0..60 {
            queue.push(n.to_string());
        }

        let (_, first) = queue.after(0).unwrap().unwrap();

        assert_eq!(first, "10");
    }

    #[test]
    fn an_event_older_than_a_day_is_not_replayed() {
        let queue = Queue::default();
        queue.lock().events.push_back(Queued {
            id: 1,
            at: Instant::now() - MAX_AGE - Duration::from_secs(1),
            data: "stale".to_owned(),
        });
        queue.push("fresh".to_owned());

        let (_, first) = queue.after(0).unwrap().unwrap();

        assert_eq!(first, "fresh");
    }

    #[test]
    fn ids_rise_even_when_the_clock_has_not_moved() {
        let queue = Queue::default();
        queue.push("a".to_owned());
        queue.push("b".to_owned());

        let (first, _) = queue.after(0).unwrap().unwrap();
        let (second, _) = queue.after(first).unwrap().unwrap();

        assert!(second > first);
    }
}
