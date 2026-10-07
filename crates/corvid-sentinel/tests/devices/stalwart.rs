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
    files: Arc<Mutex<Files>>,
    refuse_creates: Arc<AtomicBool>,
    calls: Arc<AtomicU64>,
}

#[derive(Clone)]
struct Shared {
    files: Arc<Mutex<Files>>,
    refuse_creates: Arc<AtomicBool>,
    calls: Arc<AtomicU64>,
}

impl Stalwart {
    pub async fn start() -> Self {
        let shared = Shared {
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
        Self {
            url,
            files: shared.files,
            refuse_creates: shared.refuse_creates,
            calls: shared.calls,
        }
    }

    pub fn refuse_creates(&self) {
        self.refuse_creates.store(true, Ordering::SeqCst);
    }

    pub fn calls(&self) -> u64 {
        self.calls.load(Ordering::SeqCst)
    }

    pub fn nodes(&self) -> Vec<Node> {
        self.files.lock().unwrap().nodes.clone()
    }

    pub fn node(&self, name: &str) -> Option<Node> {
        self.nodes().into_iter().find(|node| node.name == name)
    }

    pub fn content(&self, node: &Node) -> Vec<u8> {
        self.files.lock().unwrap().blobs[node.blob.as_ref().unwrap()].clone()
    }

    pub fn plant_folder(&self, name: &str) -> Node {
        let mut files = self.files.lock().unwrap();
        files.next += 1;
        let node = Node {
            id: format!("n{}", files.next),
            name: name.to_owned(),
            parent: None,
            blob: None,
            share: Value::Null,
        };
        files.nodes.push(node.clone());
        node
    }

    /// What a sharee with any access to the account can do: add a file under any folder.
    pub fn plant(&self, name: &str, parent: &str, content: &[u8]) -> Node {
        let mut files = self.files.lock().unwrap();
        files.next += 1;
        let blob = format!("planted-{}", files.next);
        files.blobs.insert(blob.clone(), content.to_vec());
        let node = Node {
            id: format!("n{}", files.next),
            name: name.to_owned(),
            parent: Some(parent.to_owned()),
            blob: Some(blob),
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
    let [method, arguments, id] = &request["methodCalls"][0].as_array().unwrap()[..] else {
        panic!("one method call expected");
    };
    let answer = match method.as_str().unwrap() {
        "x:Account/query" => query_account(arguments),
        "FileNode/get" => get_nodes(&shared, arguments),
        "FileNode/set" => set_nodes(&shared, arguments),
        other => panic!("unexpected method {other}"),
    };
    Ok(Json(json!({"methodResponses": [[method, answer, id]]})))
}

fn query_account(arguments: &Value) -> Value {
    let ids: Vec<&str> = match arguments["filter"]["name"].as_str().unwrap() {
        "corvid-keys" => vec![KEYS_ACCOUNT],
        "mailuser" => vec![MAIL_ACCOUNT],
        _ => vec![],
    };
    json!({"ids": ids})
}

fn describe(node: &Node) -> Value {
    json!({"id": node.id, "name": node.name, "parentId": node.parent, "blobId": node.blob,
           "shareWith": node.share})
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
