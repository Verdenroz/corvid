use std::collections::BTreeSet;

use corvid_label::{Ask, Message};
use corvid_labeler::{Model, Oracle};

// Needs an endpoint that returns logprobs, named by CORVID_TEST_MODEL_URL and
// CORVID_TEST_MODEL_NAME, plus CORVID_TEST_MODEL_REQUEST_EXTRA for a thinking model. Run with
// cargo test -p corvid-labeler --test live -- --ignored --nocapture
#[test]
#[ignore]
fn a_live_model_separates_obvious_messages() {
    let model = Model::connect(
        &std::env::var("CORVID_TEST_MODEL_URL").unwrap(),
        &std::env::var("CORVID_TEST_MODEL_NAME").unwrap(),
        std::env::var("CORVID_TEST_MODEL_REQUEST_EXTRA")
            .ok()
            .filter(|extra| !extra.is_empty())
            .map(|extra| serde_json::from_str(&extra).unwrap())
            .unwrap_or_default(),
    )
    .unwrap();
    let labels: BTreeSet<String> = ["receipts", "newsletters", "travel", "work", "family"]
        .iter()
        .map(|l| (*l).to_owned())
        .collect();
    let cases = [
        (
            "noreply@airline.example",
            "Your flight to Denver is confirmed",
            Some("travel"),
        ),
        (
            "orders@shop.example",
            "Your order #4411 receipt",
            Some("receipts"),
        ),
        ("mom@family.example", "Dinner on Sunday?", Some("family")),
        (
            "digest@weekly.example",
            "This week in Rust, issue 612",
            Some("newsletters"),
        ),
    ];

    for (from, subject, expected) in cases {
        let ask = Ask::new(&labels, &Message { from, subject }).unwrap();
        let letters = ask.letters();
        let scores: Vec<Vec<f64>> = ask
            .prompts()
            .map(|prompt| model.choose(prompt, &letters).unwrap())
            .collect();
        let choice = ask.read(&scores);
        println!("{subject:45} -> {choice:?}");
        assert_eq!(choice.label.as_deref(), expected, "{subject}");
        assert!(choice.coverage > 0.9, "{subject}: {choice:?}");
    }
}
