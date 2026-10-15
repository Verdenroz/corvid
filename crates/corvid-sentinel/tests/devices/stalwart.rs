use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use axum::Json;
use axum::Router;
use axum::body::Bytes;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::post;
use serde_json::{Value, json};
use tokio::net::TcpListener;

pub const TOKEN: &str = "admin-api-key";
pub const KEYS_ACCOUNT: &str = "keys-id";
pub const MAIL_ACCOUNT: &str = "mail-id";
pub const INBOX: &str = "inbox-id";
pub const ARCHIVE: &str = "archive-id";

#[derive(Clone, Debug, PartialEq)]
pub struct Node {
    pub id: String,
    pub name: String,
    pub parent: Option<String>,
    pub blob: Option<String>,
    pub share: Value,
}

#[derive(Default)]
struct Files {
    nodes: Vec<Node>,
    blobs: BTreeMap<String, Vec<u8>>,
    next: u64,
}

/// A stand-in for Stalwart's JMAP endpoint, holding only the FileNodes the sentinel writes. It
/// accepts the admin key alone, and node blobs are named apart from uploads as Stalwart's are.
pub struct Stalwart {
    pub url: String,
    shared: Shared,
}

/// The mail account's messages, each tagged with the state that created it. A state the fake
/// never issued reads as one it can no longer calculate changes from.
#[derive(Default)]
struct Mail {
    emails: Vec<(u64, Value)>,
    state: u64,
}

#[derive(Clone)]
struct Shared {
    mail: Arc<Mutex<Mail>>,
    files: Arc<Mutex<Files>>,
    refuse_creates: Arc<AtomicBool>,
    calls: Arc<AtomicU64>,
}

impl Stalwart {
    pub async fn start() -> Self {
        let shared = Shared {
            mail: Arc::default(),
            files: Arc::default(),
            refuse_creates: Arc::default(),
            calls: Arc::default(),
        };
        let app = Router::new()
            .route("/jmap", post(jmap))
            .route("/jmap/upload/{account}/", post(upload))
            .with_state(shared.clone());
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        Self { url, shared }
    }

    /// Delivers a message to the mail account, as SMTP would before the WebHook fires.
    pub fn deliver(&self, email: Value) {
        let mut mail = self.shared.mail.lock().unwrap();
        mail.state += 1;
        let state = mail.state;
        mail.emails.push((state, email));
    }

    pub fn refuse_creates(&self) {
        self.shared.refuse_creates.store(true, Ordering::SeqCst);
    }

    pub fn calls(&self) -> u64 {
        self.shared.calls.load(Ordering::SeqCst)
    }

    pub fn nodes(&self) -> Vec<Node> {
        self.shared.files.lock().unwrap().nodes.clone()
    }

    pub fn nodes_named(&self, name: &str) -> Vec<Node> {
        let mut nodes = self.nodes();
        nodes.retain(|node| node.name == name);
        nodes
    }

    pub fn node(&self, name: &str) -> Option<Node> {
        self.nodes_named(name).into_iter().next()
    }

    pub fn content(&self, node: &Node) -> Vec<u8> {
        self.shared.files.lock().unwrap().blobs[node.blob.as_ref().unwrap()].clone()
    }

    pub fn remove(&self, name: &str) {
        self.shared
            .files
            .lock()
            .unwrap()
            .nodes
            .retain(|node| node.name != name);
    }

    pub fn plant_folder(&self, name: &str) -> Node {
        self.push(name, None, None)
    }

    /// What a sharee with any access to the account can do: add a file under any folder.
    pub fn plant(&self, name: &str, parent: &str, content: &[u8]) -> Node {
        self.push(name, Some(parent), Some(content))
    }

    fn push(&self, name: &str, parent: Option<&str>, content: Option<&[u8]>) -> Node {
        let mut files = self.shared.files.lock().unwrap();
        files.next += 1;
        let blob = content.map(|content| {
            let blob = format!("planted-{}", files.next);
            files.blobs.insert(blob.clone(), content.to_vec());
            blob
        });
        let node = Node {
            id: format!("n{}", files.next),
            name: name.to_owned(),
            parent: parent.map(str::to_owned),
            blob,
            share: Value::Null,
        };
        files.nodes.push(node.clone());
        node
    }
}

fn authorized(headers: &HeaderMap) -> bool {
    headers
        .get("authorization")
        .is_some_and(|value| value == format!("Bearer {TOKEN}").as_str())
}

async fn upload(
    State(shared): State<Shared>,
    Path(account): Path<String>,
    headers: HeaderMap,
    body: Bytes,
) -> Result<Json<Value>, StatusCode> {
    if !authorized(&headers) || account != KEYS_ACCOUNT {
        return Err(StatusCode::UNAUTHORIZED);
    }
    let mut files = shared.files.lock().unwrap();
    files.next += 1;
    let blob = format!("upload-{}", files.next);
    files.blobs.insert(blob.clone(), body.to_vec());
    Ok(Json(json!({"blobId": blob})))
}

async fn jmap(
    State(shared): State<Shared>,
    headers: HeaderMap,
    Json(request): Json<Value>,
) -> Result<Json<Value>, StatusCode> {
    if !authorized(&headers) {
        return Err(StatusCode::UNAUTHORIZED);
    }
    shared.calls.fetch_add(1, Ordering::SeqCst);
    let mut responses: Vec<Value> = Vec::new();
    for call in request["methodCalls"].as_array().unwrap() {
        let [method, arguments, id] = &call.as_array().unwrap()[..] else {
            panic!("a method call is a name, arguments and an id");
        };
        let arguments = resolve(arguments, &responses);
        let answer = match method.as_str().unwrap() {
            "x:Account/query" => query_account(&arguments),
            "x:Account/get" => get_accounts(&arguments),
            "FileNode/query" => query_nodes(&shared, &arguments),
            "FileNode/get" => get_nodes(&shared, &arguments),
            "FileNode/set" => set_nodes(&shared, &arguments),
            "Mailbox/query" => json!({"ids": [INBOX]}),
            "Email/query" => query_emails(&shared, &arguments),
            "Email/get" => get_emails(&shared, &arguments),
            "Email/changes" => match email_changes(&shared, &arguments) {
                Some(answer) => answer,
                None => {
                    responses.push(json!(["error", {"type": "cannotCalculateChanges"}, id]));
                    continue;
                }
            },
            other => panic!("unexpected method {other}"),
        };
        responses.push(json!([method, answer, id]));
    }
    Ok(Json(json!({"methodResponses": responses})))
}

/// Replaces a `#ids` back-reference with the value it points at in an earlier answer.
fn resolve(arguments: &Value, responses: &[Value]) -> Value {
    let mut arguments = arguments.clone();
    if let Some(reference) = arguments.as_object_mut().unwrap().remove("#ids") {
        let earlier = responses
            .iter()
            .find(|response| {
                response[2] == reference["resultOf"] && response[0] == reference["name"]
            })
            .expect("a reference names an earlier call");
        let path = reference["path"].as_str().unwrap();
        assert_eq!(
            path, "/ids",
            "Stalwart resolves references into query results only"
        );
        arguments["ids"] = earlier[1]["ids"].clone();
    }
    arguments
}

/// The mail domain also holds an account named like the keys account, as a Pocket-ID user of that
/// name would get, so a lookup by local part alone is ambiguous.
const ACCOUNTS: [(&str, &str, &str); 3] = [
    ("namesake-id", "corvid-keys", "corvid-keys@rig.test"),
    (KEYS_ACCOUNT, "corvid-keys", "corvid-keys@keys.rig.test"),
    (MAIL_ACCOUNT, "mailuser", "mailuser@rig.test"),
];

fn query_account(arguments: &Value) -> Value {
    let name = arguments["filter"]["name"].as_str().unwrap();
    let ids: Vec<&str> = ACCOUNTS
        .iter()
        .filter(|(_, local, _)| *local == name)
        .map(|(id, _, _)| *id)
        .collect();
    json!({"ids": ids})
}

fn get_accounts(arguments: &Value) -> Value {
    let wanted = arguments["ids"].as_array().unwrap();
    let list: Vec<Value> = ACCOUNTS
        .iter()
        .filter(|(id, _, _)| wanted.contains(&json!(id)))
        .map(|(id, _, address)| json!({"id": id, "emailAddress": address}))
        .collect();
    json!({"list": list})
}

fn get_emails(shared: &Shared, arguments: &Value) -> Value {
    assert_eq!(arguments["accountId"], MAIL_ACCOUNT);
    let mail = shared.mail.lock().unwrap();
    let wanted = arguments["ids"].as_array().unwrap();
    let list: Vec<Value> = mail
        .emails
        .iter()
        .map(|(_, email)| email)
        .filter(|email| wanted.contains(&email["id"]))
        .cloned()
        .collect();
    json!({"state": mail.state.to_string(), "list": list})
}

/// The ids of the messages in a mailbox received at or after a time, newest first.
fn query_emails(shared: &Shared, arguments: &Value) -> Value {
    assert_eq!(arguments["accountId"], MAIL_ACCOUNT);
    let filter = &arguments["filter"];
    let after = filter["after"].as_str().unwrap();
    let mail = shared.mail.lock().unwrap();
    let mut found: Vec<&Value> = mail
        .emails
        .iter()
        .map(|(_, email)| email)
        .filter(|email| {
            email["mailboxIds"][filter["inMailbox"].as_str().unwrap()] == true
                && email["receivedAt"].as_str().unwrap() >= after
        })
        .collect();
    found.sort_by_key(|email| std::cmp::Reverse(email["receivedAt"].as_str().unwrap()));
    json!({"ids": found.iter().map(|email| email["id"].clone()).collect::<Vec<_>>()})
}

fn email_changes(shared: &Shared, arguments: &Value) -> Option<Value> {
    let mail = shared.mail.lock().unwrap();
    let since: u64 = arguments["sinceState"].as_str().unwrap().parse().ok()?;
    let created: Vec<Value> = mail
        .emails
        .iter()
        .filter(|(state, _)| *state > since)
        .map(|(_, email)| email["id"].clone())
        .collect();
    Some(
        json!({"newState": mail.state.to_string(), "created": created, "updated": [],
                "destroyed": [], "hasMoreChanges": false}),
    )
}

fn describe(node: &Node) -> Value {
    json!({"id": node.id, "name": node.name, "parentId": node.parent, "blobId": node.blob,
           "shareWith": node.share})
}

fn query_nodes(shared: &Shared, arguments: &Value) -> Value {
    let name = arguments["filter"]["name"].as_str().unwrap();
    let files = shared.files.lock().unwrap();
    let ids: Vec<&str> = files
        .nodes
        .iter()
        .filter(|node| node.name == name)
        .map(|node| node.id.as_str())
        .collect();
    json!({"ids": ids})
}

fn get_nodes(shared: &Shared, arguments: &Value) -> Value {
    let files = shared.files.lock().unwrap();
    let wanted: Option<Vec<&str>> = arguments["ids"]
        .as_array()
        .map(|ids| ids.iter().map(|id| id.as_str().unwrap()).collect());
    let list: Vec<Value> = files
        .nodes
        .iter()
        .filter(|node| {
            wanted
                .as_ref()
                .is_none_or(|ids| ids.contains(&node.id.as_str()))
        })
        .map(describe)
        .collect();
    json!({"list": list})
}

fn set_nodes(shared: &Shared, arguments: &Value) -> Value {
    let mut files = shared.files.lock().unwrap();
    let mut answer = json!({"created": {}, "updated": {}, "notCreated": {}, "destroyed": [], "notDestroyed": {}});
    for (key, create) in arguments["create"].as_object().into_iter().flatten() {
        if shared.refuse_creates.load(Ordering::SeqCst) {
            answer["notCreated"][key] = json!({"type": "serverFail"});
            continue;
        }
        let parent = create["parentId"].as_str();
        if parent.is_some_and(|parent| files.nodes.iter().all(|node| node.id != parent)) {
            answer["notCreated"][key] = json!({"type": "notFound"});
            continue;
        }
        files.next += 1;
        let blob = create["blobId"].as_str().map(|upload| {
            let content = files.blobs[upload].clone();
            let node_blob = format!("node-blob-{}", files.next);
            files.blobs.insert(node_blob.clone(), content);
            node_blob
        });
        let node = Node {
            id: format!("n{}", files.next),
            name: create["name"].as_str().unwrap().to_owned(),
            parent: create["parentId"].as_str().map(str::to_owned),
            blob,
            share: create.get("shareWith").cloned().unwrap_or(Value::Null),
        };
        answer["created"][key] = json!({"id": node.id});
        files.nodes.push(node);
    }
    for (id, update) in arguments["update"].as_object().into_iter().flatten() {
        let node = files.nodes.iter_mut().find(|node| &node.id == id).unwrap();
        node.share = update["shareWith"].clone();
        answer["updated"][id] = Value::Null;
    }
    for id in arguments["destroy"].as_array().into_iter().flatten() {
        let id = id.as_str().unwrap();
        let before = files.nodes.len();
        files.nodes.retain(|node| node.id != id);
        if files.nodes.len() < before {
            answer["destroyed"].as_array_mut().unwrap().push(json!(id));
        } else {
            answer["notDestroyed"][id] = json!({"type": "notFound"});
        }
    }
    answer
}
