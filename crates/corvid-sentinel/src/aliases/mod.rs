//! Per-sender aliases on the mail account: minted as `label.tag`, listed, and disabled or
//! re-enabled, never deleted, since deleting an alias moves every later one to a new key.

pub mod stalwart;
pub mod state;

use crate::webpush::random_bytes;

const LABEL_LENGTH: std::ops::RangeInclusive<usize> = 1..=20;
const TAG_LENGTH: usize = 8;
const BASE32: &[u8; 32] = b"abcdefghijklmnopqrstuvwxyz234567";

/// The label a caller asked for, lowercased, or `None` if anything in it is outside `[a-z0-9-]`.
pub fn label(raw: &str) -> Option<String> {
    let label = raw.to_ascii_lowercase();
    (LABEL_LENGTH.contains(&label.len()) && label.bytes().all(label_byte)).then_some(label)
}

/// A fresh `label.tag` name. Eight base32 characters are 40 bits, too many to find by probing RCPT.
pub fn mint(label: &str) -> String {
    let bits = random_bytes::<5>()
        .iter()
        .fold(0_u64, |bits, &byte| bits << 8 | u64::from(byte));
    let tag: String = (0..TAG_LENGTH)
        .rev()
        .map(|digit| char::from(BASE32[(bits >> (5 * digit) & 31) as usize]))
        .collect();
    format!("{label}.{tag}")
}

/// Whether `name` has the minted form. Nothing else, postmaster and abuse included, can be
/// disabled or enabled through the sentinel.
pub fn minted(name: &str) -> bool {
    let Some((label, tag)) = name.rsplit_once('.') else {
        return false;
    };
    LABEL_LENGTH.contains(&label.len())
        && label.bytes().all(label_byte)
        && tag.len() == TAG_LENGTH
        && tag.bytes().all(|byte| BASE32.contains(&byte))
}

fn label_byte(byte: u8) -> bool {
    byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-'
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_label_is_lowercased() {
        assert_eq!(label("Shop-2"), Some("shop-2".to_owned()));
    }

    #[test]
    fn a_label_with_anything_outside_letters_digits_and_hyphens_is_refused() {
        for raw in [
            "shop.x",
            "shop/0",
            "a b",
            "",
            "ünïcode",
            "x\"y",
            "aliases/2",
        ] {
            assert_eq!(label(raw), None, "{raw}");
        }
    }

    #[test]
    fn a_label_longer_than_twenty_characters_is_refused() {
        assert_eq!(label(&"a".repeat(21)), None);
        assert!(label(&"a".repeat(20)).is_some());
    }

    #[test]
    fn a_minted_name_is_the_label_and_eight_base32_characters() {
        let name = mint("shop");

        assert!(minted(&name), "{name}");
        assert_eq!(name.len(), "shop.".len() + 8);
    }

    #[test]
    fn two_mints_of_one_label_differ() {
        assert_ne!(mint("shop"), mint("shop"));
    }

    #[test]
    fn names_outside_the_minted_form_are_not_minted() {
        for name in [
            "postmaster",
            "abuse",
            "mailuser",
            "shop.abcdefg",
            "shop.abcdefghi",
            "shop.abcdefg1",
            "shop.ABCDEFGH",
            ".abcdefgh",
            "a.b.abcdefgh",
            &format!("{}.abcdefgh", "a".repeat(21)),
        ] {
            assert!(!minted(name), "{name}");
        }
    }
}
