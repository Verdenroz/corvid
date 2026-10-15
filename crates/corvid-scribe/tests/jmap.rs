use std::sync::{Arc, Mutex, mpsc};
use std::thread;

use axum::routing::{get, post};
use axum::{Json, Router};
use corvid_scribe::{Cursors, Jmap, Mailbox};
use serde_json::{Value, json};

type Log = Arc<Mutex<Vec<(String, Value)>>>;

/// A JMAP server on a thread of its own, answering each method through `answer`, which returns the
/// method's result or `Err` with a JMAP error type.
fn serve(
    answer: impl Fn(&str, &Value) -> Result<Value, &'static str> + Send + Sync + 'static,
) -> (String, Log) {
    let log = Log::default();
    let (sender, receiver) = mpsc::channel();
    let seen = log.clone();
    let answer = Arc::new(answer);
    thread::spawn(move || {
        tokio::runtime::Runtime::new().unwrap().block_on(async move {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            sender.send(listener.local_addr().unwrap()).unwrap();
            let app = Router::new()
                .route(
                    "/.well-known/jmap",
                    get(|| async {
                        Json(json!({
                            "apiUrl": "https://mail.test/jmap/",
                            "downloadUrl": "https://mail.test/jmap/download/{accountId}/{blobId}/{name}",
                            "uploadUrl": "https://mail.test/jmap/upload/{accountId}/",
                            "primaryAccounts": {"urn:ietf:params:jmap:mail": "a1"},
                        }))
                    }),
                )
                .route(
                    "/jmap/",
                    post(move |Json(request): Json<Value>| {
                        let call = &request["methodCalls"][0];
                        let name = call[0].as_str().unwrap().to_owned();
                        seen.lock().unwrap().push((name.clone(), call[1].clone()));
                        let response = match answer(&name, &call[1]) {
                            Ok(result) => json!([name, result, "c0"]),
                            Err(kind) => json!(["error", {"type": kind}, "c0"]),
                        };
                        async move { Json(json!({"methodResponses": [response]})) }
                    }),
                );
            axum::serve(listener, app).await.unwrap();
        });
    });
    (format!("http://{}", receiver.recv().unwrap()), log)
}

fn cursors() -> Cursors {
    Cursors {
        email: "e1".to_owned(),
        thread: "t1".to_owned(),
    }
}

fn states(method: &str, _: &Value) -> Result<Value, &'static str> {
    match method {
        "Email/get" => Ok(json!({"state": "e9", "list": []})),
        "Thread/get" => Ok(json!({"state": "t9", "list": []})),
        _ => unreachable!("{method}"),
    }
}

#[test]
fn a_first_run_adopts_both_positions_and_reports_nothing() {
    let (url, _) = serve(states);
    let jmap = Jmap::connect(&url, "u", "p").unwrap();

    let changes = jmap.changes(None).unwrap();

    assert!(changes.threads.is_empty());
    assert_eq!(
        changes.cursors,
        Cursors {
            email: "e9".to_owned(),
            thread: "t9".to_owned()
        }
    );
}

#[test]
fn changed_emails_and_changed_threads_both_name_threads_to_judge() {
    let (url, _) = serve(|method, arguments| match method {
        "Email/changes" => {
            Ok(json!({"created": ["m1"], "updated": ["m2", "m3"], "newState": "e2"}))
        }
        "Email/get" => {
            assert_eq!(arguments["properties"], json!(["threadId"]));
            Ok(json!({"list": [
                {"id": "m1", "threadId": "T1"},
                {"id": "m2", "threadId": "T1"},
                {"id": "m3", "threadId": "T2"},
            ]}))
        }
        "Thread/changes" => {
            Ok(json!({"created": ["T4"], "updated": ["T3"], "destroyed": ["T5"], "newState": "t2"}))
        }
        _ => unreachable!("{method}"),
    });
    let jmap = Jmap::connect(&url, "u", "p").unwrap();

    let changes = jmap.changes(Some(&cursors())).unwrap();

    assert_eq!(changes.threads, ["T1", "T2", "T3", "T4", "T5"]);
    assert_eq!(changes.cursors.email, "e2");
    assert_eq!(changes.cursors.thread, "t2");
}

#[test]
fn a_cursor_the_server_cannot_calculate_from_is_replaced_by_its_current_position() {
    let (url, log) = serve(|method, arguments| match method {
        "Email/changes" => Err("cannotCalculateChanges"),
        other => states(other, arguments),
    });
    let jmap = Jmap::connect(&url, "u", "p").unwrap();

    let changes = jmap.changes(Some(&cursors())).unwrap();

    assert!(changes.threads.is_empty());
    assert_eq!(changes.cursors.email, "e9");
    assert_eq!(changes.cursors.thread, "t9");
    assert_eq!(log.lock().unwrap().len(), 3);
}

#[test]
fn a_thread_cursor_that_is_lost_is_replaced_too() {
    let (url, _) = serve(|method, arguments| match method {
        "Email/changes" => Ok(json!({"created": [], "updated": [], "newState": "e2"})),
        "Thread/changes" => Err("cannotCalculateChanges"),
        other => states(other, arguments),
    });
    let jmap = Jmap::connect(&url, "u", "p").unwrap();

    let changes = jmap.changes(Some(&cursors())).unwrap();

    assert_eq!(changes.cursors.thread, "t9");
}

#[test]
fn members_ask_for_ids_and_folders_only_and_mails_ask_for_bodies() {
    let (url, log) = serve(|method, arguments| match method {
        "Thread/get" => Ok(json!({"list": [{"id": "T1", "emailIds": ["m1", "m2"]}]})),
        "Email/get" if arguments["properties"] == json!(["id", "mailboxIds"]) => {
            Ok(json!({"list": [
                {"id": "m1", "mailboxIds": {"inbox": true}},
                {"id": "m2", "mailboxIds": {"junk": true}},
            ]}))
        }
        "Email/get" => Ok(json!({"list": [{
            "id": "m1",
            "from": [{"email": "ann@x.test"}],
            "receivedAt": "2026-10-01T09:00:00Z",
            "textBody": [{"partId": "1", "type": "text/plain"}],
            "bodyValues": {"1": {"value": "Hello"}},
        }]})),
        _ => unreachable!("{method}"),
    });
    let jmap = Jmap::connect(&url, "u", "p").unwrap();

    let members = jmap.members("T1").unwrap();
    let requested = log.lock().unwrap().len();
    let mails = jmap.mails(&["m1".to_owned()]).unwrap();

    assert_eq!(members.len(), 2);
    assert!(members[1].mailbox_ids.contains("junk"));
    assert_eq!(mails[0].body, "Hello");
    let log = log.lock().unwrap();
    assert!(
        log[..requested]
            .iter()
            .all(|(_, arguments)| arguments.get("fetchTextBodyValues").is_none())
    );
    assert_eq!(log[requested].1["fetchTextBodyValues"], true);
}

#[test]
fn a_thread_that_no_longer_exists_has_no_members() {
    let (url, _) = serve(|method, _| match method {
        "Thread/get" => Ok(json!({"list": [], "notFound": ["T1"]})),
        _ => unreachable!("{method}"),
    });
    let jmap = Jmap::connect(&url, "u", "p").unwrap();

    assert!(jmap.members("T1").unwrap().is_empty());
}
