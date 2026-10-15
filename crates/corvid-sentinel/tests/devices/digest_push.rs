use serde_json::{Value, json};

use super::digest::{DAY, Digesting, HOUR, digesting_with, email};
use super::push::{BASE, Fcm, Phone};
use super::stalwart::INBOX;

struct Pushing {
    sentinel: Digesting,
    fcm: Fcm,
}

async fn pushing() -> Pushing {
    let (fcm, url) = Fcm::start().await;
    let sentinel = digesting_with(Some(&url), "UTC", DAY + 6 * HOUR).await;
    Pushing { sentinel, fcm }
}

impl Pushing {
    async fn put(&self, name: &str, path: &str, body: &[u8]) -> reqwest::Response {
        reqwest::Client::new()
            .put(format!(
                "{}/corvid/devices/{name}/{path}",
                self.sentinel.rig.url
            ))
            .header("content-type", "application/json")
            .body(body.to_vec())
            .send()
            .await
            .unwrap()
    }

    async fn enroll(&self, name: &str, phone: &Phone, endpoint: &str) {
        self.sentinel.rig.approved(name).await;
        let body = phone
            .registration(&format!("{BASE}/{endpoint}"))
            .to_string();
        assert_eq!(self.put(name, "push", body.as_bytes()).await.status(), 204);
    }

    async fn ask_for(&self, name: &str, kinds: Value) -> reqwest::Response {
        let body = json!({"kinds": kinds}).to_string();
        self.put(name, "push/kinds", body.as_bytes()).await
    }

    fn registration(&self) -> Option<corvid_sentinel::devices::Push> {
        corvid_sentinel::devices::open(&self.sentinel.rig.state)
            .unwrap()
            .approved
            .get("phone")
            .and_then(|device| device.push.clone())
    }
}

#[tokio::test]
async fn only_a_device_that_asked_for_digests_gets_the_content_free_push() {
    let rig = pushing().await;
    let (phone, tablet) = (Phone::new(), Phone::other());
    rig.enroll("phone", &phone, "phone-endpoint").await;
    rig.enroll("tablet", &tablet, "tablet-endpoint").await;
    assert_eq!(rig.ask_for("phone", json!(["digest"])).await.status(), 204);
    rig.sentinel
        .rig
        .stalwart
        .deliver(email("m1", "T1", INBOX, DAY + 3 * HOUR, &[]));

    rig.sentinel.at(DAY + 7 * HOUR);
    let digest = rig.sentinel.latest_after(None).await;
    rig.fcm.wait_for(1).await;
    rig.sentinel.settle().await;

    assert_eq!(rig.fcm.count(), 1, "the tablet asked for nothing");
    let posted = rig.fcm.posted.lock().unwrap().remove(0);
    assert_eq!(posted.target, "/fcm/send/phone-endpoint");
    assert_eq!(
        phone.decrypt(&posted.body),
        json!({"v": 2, "kind": "digest", "id": digest["id"]})
    );
}

#[tokio::test]
async fn a_digest_with_no_mail_is_kept_but_not_pushed() {
    let rig = pushing().await;
    rig.enroll("phone", &Phone::new(), "phone-endpoint").await;
    assert_eq!(rig.ask_for("phone", json!(["digest"])).await.status(), 204);

    rig.sentinel.at(DAY + 7 * HOUR);
    let digest = rig.sentinel.latest_after(None).await;
    rig.sentinel.settle().await;

    assert_eq!(digest["groups"], json!([]));
    assert_eq!(rig.fcm.count(), 0);
}

#[tokio::test]
async fn asking_for_kinds_leaves_the_push_registration_alone_and_can_be_withdrawn() {
    let rig = pushing().await;
    rig.enroll("phone", &Phone::new(), "phone-endpoint").await;
    let held = rig.registration();

    assert_eq!(rig.ask_for("phone", json!(["digest"])).await.status(), 204);
    assert_eq!(rig.registration(), held);
    assert_eq!(rig.ask_for("phone", json!([])).await.status(), 204);
    rig.sentinel.at(DAY + 7 * HOUR);
    rig.sentinel.latest_after(None).await;
    rig.sentinel.settle().await;

    assert_eq!(rig.fcm.count(), 0);
}

#[tokio::test]
async fn kinds_this_build_does_not_know_are_refused() {
    let rig = pushing().await;
    rig.enroll("phone", &Phone::new(), "phone-endpoint").await;

    for body in [
        json!({"kinds": ["digest", "weather"]}),
        json!({"kinds": ["digest"], "extra": 1}),
        json!({"kinds": "digest"}),
    ] {
        let response = rig
            .put("phone", "push/kinds", body.to_string().as_bytes())
            .await;
        assert_eq!(response.status(), 400, "{body}");
    }
    assert_eq!(rig.ask_for("ghost", json!(["digest"])).await.status(), 404);
}
