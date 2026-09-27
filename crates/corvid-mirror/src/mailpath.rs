//! A mailbox path as one string: segments joined by an unescaped `/`. Mailbox names are not
//! unique across parents, so the archive must keep the whole chain to recreate it on restore.

pub fn join(segments: &[String]) -> String {
    segments
        .iter()
        .map(|s| escape(s))
        .collect::<Vec<_>>()
        .join("/")
}

pub fn split(path: &str) -> Vec<String> {
    let mut segments = Vec::new();
    let mut current = String::new();
    let mut chars = path.chars();
    while let Some(c) = chars.next() {
        match c {
            '\\' => current.extend(chars.next()),
            '/' => segments.push(std::mem::take(&mut current)),
            _ => current.push(c),
        }
    }
    segments.push(current);
    segments
}

fn escape(name: &str) -> String {
    name.replace('\\', "\\\\").replace('/', "\\/")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_segments_round_trip() {
        let path = join(&["Parent".to_owned(), "Child".to_owned()]);

        assert_eq!(path, "Parent/Child");
        assert_eq!(split(&path), vec!["Parent".to_owned(), "Child".to_owned()]);
    }

    #[test]
    fn a_slash_inside_a_name_round_trips() {
        let path = join(&["A/B".to_owned(), "Child".to_owned()]);

        assert_eq!(split(&path), vec!["A/B".to_owned(), "Child".to_owned()]);
    }

    #[test]
    fn a_backslash_inside_a_name_round_trips() {
        let path = join(&["A\\B".to_owned()]);

        assert_eq!(split(&path), vec!["A\\B".to_owned()]);
    }
}
