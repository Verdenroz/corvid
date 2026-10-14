//! The model endpoint: OpenAI-compatible chat completions, read for the probability of each
//! option letter rather than for generated text. The endpoint must return `logprobs`.
//!
//! The model host is off most of the time, so an unreachable model is the ordinary case, not a
//! fault. Nothing here retries; a pass that cannot reach it labels nothing.

use std::io;
use std::time::Duration;

use reqwest::blocking::Client as Http;
use serde_json::{Map, Value, json};

const TIMEOUT: Duration = Duration::from_secs(120);

/// Short, because the poll loop shares this thread and the usual answer is no route at all.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

/// Ranked tokens requested beyond the letters, since tokens that are not options such as
/// `<think>` can take places in the ranking.
const SPARE_RANKS: usize = 10;

/// The OpenAI API's ceiling on `top_logprobs`, which conforming servers enforce. Letters ranked
/// below it count as zero, which costs coverage only when many labels share the mass.
const MAX_RANKS: usize = 20;

/// The model the cold-start pass asks, so the pass can be exercised without a model host.
pub trait Oracle {
    /// The probability of each of `letters` being the whole answer, in `letters` order. A letter
    /// the model did not rank is zero, so the sum is how much of the answer fell on an option.
    ///
    /// An error is a condition every later message would hit too, so the pass stops on it.
    fn choose(&self, prompt: &str, letters: &[char]) -> io::Result<Vec<f64>>;
}

pub struct Model {
    http: Http,
    url: String,
    name: String,
    extra: Map<String, Value>,
}

impl Model {
    /// `extra` holds server-specific request fields, such as the switch that turns off a
    /// model's thinking block.
    pub fn connect(url: &str, name: &str, extra: Map<String, Value>) -> io::Result<Self> {
        let http = Http::builder()
            .timeout(TIMEOUT)
            .connect_timeout(CONNECT_TIMEOUT)
            .build()
            .map_err(io::Error::other)?;

        Ok(Self {
            http,
            url: url.to_owned(),
            name: name.to_owned(),
            extra,
        })
    }
}

impl Oracle for Model {
    fn choose(&self, prompt: &str, letters: &[char]) -> io::Result<Vec<f64>> {
        let request = request(&self.name, prompt, letters, &self.extra);
        let response: Value = self
            .http
            .post(&self.url)
            .json(&request)
            .send()
            .and_then(|response| response.error_for_status())
            .and_then(|response| response.json())
            .map_err(io::Error::other)?;

        scores_from(&response, letters)
    }
}

/// The chat request for one prompt. `extra` is applied first, so it can add fields but not
/// replace the ones the reading depends on.
fn request(name: &str, prompt: &str, letters: &[char], extra: &Map<String, Value>) -> Value {
    let Value::Object(core) = json!({
        "model": name,
        "temperature": 0,
        "max_tokens": 1,
        "logprobs": true,
        "top_logprobs": (letters.len() + SPARE_RANKS).min(MAX_RANKS),
        "messages": [{"role": "user", "content": prompt}],
    }) else {
        unreachable!("a json! object literal")
    };
    let mut request = extra.clone();
    request.extend(core);
    Value::Object(request)
}

/// Reads per-letter probabilities from the ranking of the first generated token.
///
/// `finish_reason` is ignored: with one token allowed, `length` is the normal finish. A response
/// without a ranking is an error rather than an empty answer, since it means the endpoint does
/// not report log probabilities and no message would fare better.
///
/// Matching is exact after trimming whitespace. A lowercase letter is not the option, since
/// folding it in would let a token the model did not offer count as one.
fn scores_from(response: &Value, letters: &[char]) -> io::Result<Vec<f64>> {
    let ranked = response["choices"][0]["logprobs"]["content"][0]["top_logprobs"]
        .as_array()
        .ok_or_else(|| {
            io::Error::other("response carries no top_logprobs; the endpoint must return logprobs")
        })?;

    let mut scores = vec![0.0; letters.len()];
    for entry in ranked {
        let (Some(token), Some(logprob)) = (entry["token"].as_str(), entry["logprob"].as_f64())
        else {
            return Err(io::Error::other("a ranked token has no token or logprob"));
        };
        let mut chars = token.trim().chars();
        if let (Some(c), None) = (chars.next(), chars.next())
            && let Some(slot) = letters.iter().position(|&l| l == c)
        {
            scores[slot] += logprob.exp();
        }
    }
    Ok(scores)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn captured() -> Value {
        serde_json::from_str(include_str!("llama-server-logprobs.json")).unwrap()
    }

    fn ranking(entries: Value) -> Value {
        json!({
            "choices": [{
                "finish_reason": "length",
                "logprobs": {"content": [{"token": "A", "logprob": 0.0, "top_logprobs": entries}]},
            }]
        })
    }

    #[test]
    fn reads_a_response_captured_from_llama_server() {
        let scores = scores_from(&captured(), &['A', 'B', 'C', 'D']).unwrap();

        let expected = [
            -2.479_872_941_970_825,
            -2.826_182_603_836_059_6,
            -0.300_891_250_371_933,
            -2.150_454_759_597_778_3,
        ];
        for (score, logprob) in scores.iter().zip(expected) {
            assert!((score - f64::exp(logprob)).abs() < 1e-12);
        }
        assert!(scores.iter().sum::<f64>() > 0.99);
    }

    #[test]
    fn tokens_that_are_not_offered_letters_are_not_counted() {
        let response = ranking(json!([
            {"token": "c", "logprob": -0.1},
            {"token": "<think>", "logprob": -0.2},
            {"token": "Z", "logprob": -0.3},
            {"token": "AB", "logprob": -0.4},
        ]));

        assert_eq!(scores_from(&response, &['A', 'B', 'C']).unwrap(), [0.0; 3]);
    }

    #[test]
    fn a_letter_with_and_without_a_leading_space_is_one_option() {
        let response = ranking(json!([
            {"token": "B", "logprob": f64::ln(0.5)},
            {"token": " B", "logprob": f64::ln(0.25)},
        ]));

        let scores = scores_from(&response, &['A', 'B']).unwrap();

        assert!((scores[1] - 0.75).abs() < 1e-12);
    }

    #[test]
    fn the_ranking_requested_stays_within_the_openai_ceiling() {
        let letters: Vec<char> = ('A'..='Z').collect();

        let request = request("m", "p", &letters, &Map::new());

        assert_eq!(request["top_logprobs"], MAX_RANKS);
    }

    #[test]
    fn extra_fields_are_sent_but_cannot_replace_the_ones_read_back() {
        let extra = json!({
            "chat_template_kwargs": {"enable_thinking": false},
            "max_tokens": 50,
            "logprobs": false,
        });
        let Value::Object(extra) = extra else {
            unreachable!()
        };

        let request = request("m", "p", &['A'], &extra);

        assert_eq!(request["chat_template_kwargs"]["enable_thinking"], false);
        assert_eq!(request["max_tokens"], 1);
        assert_eq!(request["logprobs"], true);
    }

    #[test]
    fn an_endpoint_without_log_probabilities_is_an_error() {
        let plain = json!({"choices": [{"finish_reason": "stop", "message": {"content": "A"}}]});

        for response in [json!({}), plain, ranking(json!([{"token": "A"}]))] {
            assert!(
                scores_from(&response, &['A']).is_err(),
                "{response} was read"
            );
        }
    }

    fn live_extra() -> Map<String, Value> {
        std::env::var("CORVID_TEST_MODEL_REQUEST_EXTRA")
            .ok()
            .filter(|extra| !extra.is_empty())
            .map(|extra| serde_json::from_str(&extra).unwrap())
            .unwrap_or_default()
    }

    // Needs an endpoint that returns logprobs; see tests/live.rs for the variables.
    #[test]
    #[ignore]
    fn a_live_endpoint_puts_its_answer_on_the_offered_letters() {
        let url = std::env::var("CORVID_TEST_MODEL_URL").unwrap();
        let name = std::env::var("CORVID_TEST_MODEL_NAME").unwrap();
        let prompt = "Which one label fits the message below best?\n\nA: receipts\nB: travel\n\
                      C: none of these labels\n\nAnswer with the letter of one option and nothing \
                      else.\n\nFrom: noreply@airline.example\nSubject: Your flight is confirmed";

        let scores = Model::connect(&url, &name, live_extra())
            .unwrap()
            .choose(prompt, &['A', 'B', 'C'])
            .unwrap();

        assert!(scores.iter().sum::<f64>() > 0.9, "{scores:?}");
    }
}
