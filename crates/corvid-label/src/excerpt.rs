/// Takes at most `max_bytes` of `text`, ending on a character boundary.
///
/// Everything that reaches a prompt passes through here. A sender controls every field's length,
/// so an unbounded one could spend the model's whole context or push the question out of it.
///
/// It does not escape or delimit anything. The answer is read against a closed set, so text that
/// talks its way past a delimiter still cannot name a label that does not exist.
pub fn excerpt(text: &str, max_bytes: usize) -> &str {
    if text.len() <= max_bytes {
        return text;
    }
    let mut end = max_bytes;
    while end > 0 && !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn short_text_is_untouched() {
        assert_eq!(excerpt("work", 16), "work");
        assert_eq!(excerpt("", 16), "");
    }

    #[test]
    fn long_text_is_cut_to_the_bound() {
        assert_eq!(excerpt("abcdefgh", 4), "abcd");
        assert_eq!(excerpt("abcdefgh", 8), "abcdefgh");
    }

    #[test]
    fn a_cut_never_splits_a_character() {
        let text = "aé漢";
        for max in 0..=text.len() {
            let cut = excerpt(text, max);
            assert!(text.starts_with(cut));
            assert!(cut.len() <= max);
        }
    }

    // A bound of nothing is a caller error, not something to paper over with a minimum.
    #[test]
    fn a_bound_of_zero_yields_nothing() {
        assert_eq!(excerpt("漢字", 0), "");
        assert_eq!(excerpt("漢字", 2), "");
    }
}
