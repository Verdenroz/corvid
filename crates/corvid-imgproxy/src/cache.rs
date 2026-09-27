use std::num::NonZeroUsize;
use std::sync::Mutex;

use bytes::Bytes;
use lru::LruCache;

#[derive(Clone)]
pub struct Image {
    pub content_type: String,
    pub body: Bytes,
}

pub struct Cache {
    entries: Mutex<LruCache<String, Image>>,
}

impl Cache {
    pub fn new(capacity: NonZeroUsize) -> Self {
        Self {
            entries: Mutex::new(LruCache::new(capacity)),
        }
    }

    pub fn get(&self, key: &str) -> Option<Image> {
        self.entries.lock().unwrap().get(key).cloned()
    }

    pub fn put(&self, key: String, image: Image) {
        self.entries.lock().unwrap().put(key, image);
    }
}
