use sha2::{Digest, Sha256};

use crate::webpush::random_bytes;

const ALPHABET: &[u8; 32] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

/// 80 random bits as four base32 groups of four characters.
pub fn generate() -> String {
    let mut bits: u128 = 0;
    for byte in random_bytes::<10>() {
        bits = bits << 8 | u128::from(byte);
    }
    let characters: Vec<char> = (0..16)
        .rev()
        .map(|index| char::from(ALPHABET[(bits >> (index * 5) & 31) as usize]))
        .collect();
    characters
        .chunks(4)
        .map(|group| group.iter().collect::<String>())
        .collect::<Vec<_>>()
        .join("-")
}

/// SHA-256, hex, of a code as typed: case, dashes and spaces do not matter.
pub fn hash(code: &str) -> String {
    let normal: String = code
        .chars()
        .filter(|c| !c.is_whitespace() && *c != '-')
        .map(|c| c.to_ascii_uppercase())
        .collect();
    hex::encode(Sha256::digest(normal))
}
