//! A mailbox path as one string: segments joined by an unescaped `/`. Mailbox names are not
//! unique across parents, so the archive must keep the whole chain to recreate it on restore.

pub fn join(segments: &[String]) -> String {
    segments
        .iter()
        .map(|s| escape(s))
        .collect::<Vec<_>>()
        .join("/")
}

fn escape(name: &str) -> String {
    name.replace('\\', "\\\\").replace('/', "\\/")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_segments_join_with_a_slash() {
        assert_eq!(
            join(&["Parent".to_owned(), "Child".to_owned()]),
            "Parent/Child"
        );
    }

    #[test]
    fn a_slash_inside_a_name_is_escaped() {
        assert_eq!(join(&["A/B".to_owned(), "Child".to_owned()]), "A\\/B/Child");
    }
}
