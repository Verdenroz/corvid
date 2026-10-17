use std::sync::Arc;
use std::sync::atomic::{AtomicI64, Ordering};
use std::time::Duration;

use corvid_sentinel::devices::Devices;
use corvid_sentinel::rfc3339;
use corvid_sentinel::scribe::digest::Settings;
use jiff::civil::time;
use jiff::tz::TimeZone;
use serde_json::{Value, json};

use super::stalwart::INBOX;
use super::{Rig, common, rig, rig_digest};

/// 2026-10-14T00:00:00Z.
pub(super) const DAY: i64 = 1_791_936_000;
pub(super) const HOUR: i64 = 3600;
const CLOSING: i64 = 60;
const JUNK: &str = "junk-id";

pub(super) struct Digesting {
    pub(super) rig: Rig,
    clock: Arc<AtomicI64>,
}

async fn digesting(zone: &str, now: i64) -> Digesting {
    digesting_with(None, zone, now).await
}

pub(super) async fn digesting_with(fcm_origin: Option<&str>, zone: &str, now: i64) -> Digesting {
    let clock = Arc::new(AtomicI64::new(now));
    let reading = clock.clone();
    let settings = Settings {
        at: time(7, 0, 0, 0),
        zone: TimeZone::get(zone).unwrap(),
        poll: Duration::from_millis(10),
        clock: Arc::new(move || reading.load(Ordering::SeqCst)),
    };
    let rig = rig_digest(Devices::default(), fcm_origin, Some(settings)).await;
    Digesting { rig, clock }
}

pub(super) fn email(
    id: &str,
    thread: &str,
    mailbox: &str,
    received_at: i64,
    keywords: &[&str],
) -> Value {
    json!({
        "id": id,
        "threadId": thread,
        "mailboxIds": {(mailbox): true},
        "receivedAt": rfc3339(received_at),
        "from": [{"name": "Sender", "email": "sender@example.com"}],
        "subject": format!("subject of {id}"),
        "keywords": keywords.iter().map(|keyword| ((*keyword).to_owned(), json!(true))).collect::<serde_json::Map<_, _>>(),
    })
}

impl Digesting {
    pub(super) fn at(&self, now: i64) {
        self.clock.store(now, Ordering::SeqCst);
    }

    async fn get(&self, path: &str) -> reqwest::Response {
        reqwest::get(format!("{}/corvid/digest{path}", self.rig.url))
            .await
            .unwrap()
    }

    async fn ids(&self) -> Vec<String> {
        let listed: Value = self.get("").await.json().await.unwrap();
        listed["digests"]
            .as_array()
            .unwrap()
            .iter()
            .map(|digest| digest["id"].as_str().unwrap().to_owned())
            .collect()
    }

    /// The newest digest once its id is not `after`.
    pub(super) async fn latest_after(&self, after: Option<&str>) -> Value {
        for _ in 0..250 {
            let response = self.get("/latest").await;
            if response.status() == 200 {
                let digest: Value = response.json().await.unwrap();
                if Some(digest["id"].as_str().unwrap()) != after {
                    return digest;
                }
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        panic!("no digest after {after:?}");
    }

    pub(super) async fn settle(&self) {
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
}

#[test]
fn a_state_file_written_before_digests_still_opens() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("scribe.json");
    let old = json!({"suggestions": [], "decided": [], "summaries": {}});
    corvid_store::save(&path, 1, &old).unwrap();

    let scribe = corvid_sentinel::scribe::open(&path).unwrap();

    assert!(scribe.digests.is_empty());
}

#[test]
fn a_digest_saved_without_sender_fields_still_opens() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("scribe.json");
    let item = json!({"thread_id": "T", "email_id": "m", "from": "a@x.test",
                      "subject": "s", "received_at": 1, "summary": null});
    let old = json!({"suggestions": [], "decided": [], "summaries": {}, "digests": [
        {"id": "2026-10-14", "from": 0, "to": 1, "groups": [{"label": null, "items": [item]}]}
    ]});
    corvid_store::save(&path, 1, &old).unwrap();

    let scribe = corvid_sentinel::scribe::open(&path).unwrap();

    let kept = &scribe.digests[0].groups[0].items[0];
    assert_eq!(kept.from_name, None);
    assert!(!kept.is_list);
}

#[tokio::test]
async fn no_digest_exists_before_the_first_is_built() {
    let sentinel = digesting("UTC", DAY + 6 * HOUR + 59 * 60).await;

    sentinel.settle().await;

    assert_eq!(sentinel.get("/latest").await.status(), 404);
    assert_eq!(sentinel.get("/2026-10-14").await.status(), 404);
    let listed: Value = sentinel.get("").await.json().await.unwrap();
    assert_eq!(listed, json!({"digests": []}));
    assert_eq!(
        sentinel.rig.stalwart.calls(),
        0,
        "the mail is not read before the digest is due"
    );
}

#[tokio::test]
async fn a_digest_is_built_once_a_day_after_the_time() {
    let sentinel = digesting("UTC", DAY + 6 * HOUR).await;
    let stalwart = &sentinel.rig.stalwart;
    stalwart.deliver(email("m1", "T1", INBOX, DAY + 3 * HOUR, &[]));

    sentinel.at(DAY + 7 * HOUR);
    let digest = sentinel.latest_after(None).await;
    stalwart.deliver(email("m2", "T2", INBOX, DAY + 8 * HOUR, &[]));
    sentinel.at(DAY + 12 * HOUR);
    let calls = stalwart.calls();
    sentinel.settle().await;

    assert_eq!(digest["id"], "2026-10-14");
    assert_eq!(digest["to"], rfc3339(DAY + 7 * HOUR - CLOSING));
    assert_eq!(
        digest["from"],
        rfc3339(DAY + 7 * HOUR - CLOSING - 24 * HOUR)
    );
    assert_eq!(sentinel.ids().await, ["2026-10-14"]);
    assert_eq!(
        stalwart.calls(),
        calls,
        "the mail is not read again that day"
    );
}

#[tokio::test]
async fn a_sentinel_that_starts_late_builds_the_days_digest_then() {
    let sentinel = digesting("UTC", DAY + 15 * HOUR).await;
    sentinel
        .rig
        .stalwart
        .deliver(email("m1", "T1", INBOX, DAY + 3 * HOUR, &[]));

    let digest = sentinel.latest_after(None).await;

    assert_eq!(digest["id"], "2026-10-14");
    assert_eq!(digest["groups"][0]["items"][0]["emailId"], "m1");
}

#[tokio::test]
async fn the_day_is_the_local_one() {
    let sentinel = digesting("America/Chicago", DAY + 11 * HOUR).await;

    sentinel.settle().await;
    assert_eq!(
        sentinel.get("/latest").await.status(),
        404,
        "06:00 in Chicago is before 07:00"
    );
    sentinel.at(DAY + 12 * HOUR);
    let digest = sentinel.latest_after(None).await;

    assert_eq!(digest["id"], "2026-10-14");
}

#[tokio::test]
async fn each_window_starts_where_the_previous_digest_ended() {
    let sentinel = digesting("UTC", DAY + 7 * HOUR).await;
    let stalwart = &sentinel.rig.stalwart;
    stalwart.deliver(email("old", "T1", INBOX, DAY + 3 * HOUR, &[]));
    stalwart.deliver(email("stale", "T0", INBOX, DAY - 30 * HOUR, &[]));
    let first = sentinel.latest_after(None).await;
    stalwart.deliver(email("new", "T2", INBOX, DAY + 10 * HOUR, &[]));

    sentinel.at(DAY + 24 * HOUR + 7 * HOUR);
    let second = sentinel.latest_after(Some("2026-10-14")).await;

    let emails = |digest: &Value| {
        digest["groups"]
            .as_array()
            .unwrap()
            .iter()
            .flat_map(|group| group["items"].as_array().unwrap())
            .map(|item| item["emailId"].as_str().unwrap().to_owned())
            .collect::<Vec<_>>()
    };
    assert_eq!(
        emails(&first),
        ["old"],
        "a first digest covers the last day"
    );
    assert_eq!(second["from"], first["to"]);
    assert_eq!(emails(&second), ["new"]);
}

#[tokio::test]
async fn a_message_on_the_edge_of_two_windows_lands_in_exactly_one() {
    let sentinel = digesting("UTC", DAY + 7 * HOUR).await;
    let stalwart = &sentinel.rig.stalwart;
    let close = DAY + 7 * HOUR - CLOSING;
    for (id, at) in [("at", close), ("after", close + 1), ("late", close + 59)] {
        stalwart.deliver(email(id, id, INBOX, at, &[]));
    }
    let first = sentinel.latest_after(None).await;

    sentinel.at(DAY + 24 * HOUR + 7 * HOUR);
    let second = sentinel.latest_after(Some("2026-10-14")).await;

    let emails = |digest: &Value| {
        let mut ids: Vec<String> = digest["groups"]
            .as_array()
            .unwrap()
            .iter()
            .flat_map(|group| group["items"].as_array().unwrap())
            .map(|item| item["emailId"].as_str().unwrap().to_owned())
            .collect();
        ids.sort();
        ids
    };
    assert_eq!(emails(&first), ["at"]);
    assert_eq!(emails(&second), ["after", "late"]);
}

#[tokio::test]
async fn only_the_newest_thirty_digests_are_kept() {
    let sentinel = digesting("UTC", DAY + 7 * HOUR).await;
    let mut previous = sentinel.latest_after(None).await["id"]
        .as_str()
        .unwrap()
        .to_owned();

    for day in 1..=30 {
        sentinel.at(DAY + day * 24 * HOUR + 7 * HOUR);
        previous = sentinel.latest_after(Some(&previous)).await["id"]
            .as_str()
            .unwrap()
            .to_owned();
    }

    let ids = sentinel.ids().await;
    assert_eq!(ids.len(), 30);
    assert_eq!(ids[0], "2026-11-13");
    assert_eq!(ids[29], "2026-10-15");
}

#[tokio::test]
async fn a_digest_groups_the_inbox_by_label_with_summaries_and_leaves_out_junk() {
    let sentinel = digesting("UTC", DAY + 6 * HOUR).await;
    let stalwart = &sentinel.rig.stalwart;
    stalwart.deliver(email("m1", "T1", INBOX, DAY + HOUR, &["banking", "$seen"]));
    stalwart.deliver(email("m2", "T2", INBOX, DAY + 2 * HOUR, &["corvid-news"]));
    stalwart.deliver(email("m3", "T1", INBOX, DAY + 3 * HOUR, &["banking"]));
    stalwart.deliver(email("m4", "T3", JUNK, DAY + 4 * HOUR, &[]));
    stalwart.deliver(email("m5", "T4", INBOX, DAY + 5 * HOUR, &[]));
    post_summary(&sentinel, "T1", "They agreed on Friday.").await;

    sentinel.at(DAY + 7 * HOUR);
    let digest = sentinel.latest_after(None).await;

    let item = |email: &str, thread: &str, hour: i64, summary: Value| {
        json!({"threadId": thread, "emailId": email, "from": "sender@example.com",
               "fromName": "Sender", "isList": false,
               "subject": format!("subject of {email}"),
               "receivedAt": rfc3339(DAY + hour * HOUR), "summary": summary})
    };
    assert_eq!(
        digest["groups"],
        json!([
            {"label": "banking", "items": [item("m3", "T1", 3, json!("They agreed on Friday."))]},
            {"label": "news", "items": [item("m2", "T2", 2, Value::Null)]},
            {"label": null, "items": [item("m5", "T4", 5, Value::Null)]},
        ])
    );
    let by_id: Value = sentinel.get("/2026-10-14").await.json().await.unwrap();
    assert_eq!(by_id, digest);
    let listed: Value = sentinel.get("").await.json().await.unwrap();
    assert_eq!(
        listed,
        json!({"digests": [{"id": "2026-10-14", "to": rfc3339(DAY + 7 * HOUR - CLOSING), "count": 3}]})
    );
}

#[tokio::test]
async fn a_digest_item_names_its_sender_and_flags_list_mail() {
    let sentinel = digesting("UTC", DAY + 6 * HOUR).await;
    let stalwart = &sentinel.rig.stalwart;
    let mut list = email("m1", "T1", INBOX, DAY + HOUR, &[]);
    list["header:List-Id:asText"] = json!("<news.example.com>");
    let mut nameless = email("m2", "T2", INBOX, DAY + 2 * HOUR, &[]);
    nameless["from"] = json!([{"name": "  ", "email": "sender@example.com"}]);
    stalwart.deliver(list);
    stalwart.deliver(nameless);

    sentinel.at(DAY + 7 * HOUR);
    let digest = sentinel.latest_after(None).await;

    let items = &digest["groups"][0]["items"];
    assert_eq!(items[0]["threadId"], "T2");
    assert_eq!(items[0]["fromName"], Value::Null);
    assert_eq!(items[0]["isList"], false);
    assert_eq!(items[1]["fromName"], "Sender");
    assert_eq!(items[1]["isList"], true);
}

async fn post_summary(sentinel: &Digesting, thread: &str, text: &str) {
    let body = json!({"sent_at": corvid_sentinel::now_secs(),
        "upserts": [{"thread_id": thread, "input_hash": "h", "text": text, "model": "m", "generated_at": 1}],
        "deletes": []});
    let body = serde_json::to_vec(&body).unwrap();
    let response = reqwest::Client::new()
        .post(format!("{}/internal/scribe/summaries", sentinel.rig.url))
        .header(
            "X-Signature",
            common::signature(common::SCRIBE_SECRET, &body),
        )
        .body(body)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 204);
}

#[tokio::test]
async fn the_digest_routes_answer_503_where_no_digest_is_configured() {
    let without_scribe = rig().await;
    let without_digest = common::ScribeRig::new().await;

    for url in [&without_scribe.url, &without_digest.url] {
        for path in [
            "/corvid/digest",
            "/corvid/digest/latest",
            "/corvid/digest/x",
        ] {
            let response = reqwest::get(format!("{url}{path}")).await.unwrap();
            assert_eq!(response.status(), 503);
            let body: Value = response.json().await.unwrap();
            assert_eq!(body, json!({"error": "digest_off"}));
        }
    }
}
