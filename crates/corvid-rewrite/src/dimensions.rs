pub fn is_zero_area(width: Option<i64>, height: Option<i64>) -> bool {
    matches!(
        (width, height),
        (Some(0), _) | (_, Some(0)) | (Some(1), Some(1))
    )
}

pub fn declared(attribute: Option<String>, style: Option<&str>, property: &str) -> Option<i64> {
    attribute
        .as_deref()
        .and_then(parse_length)
        .or_else(|| from_style(style?, property))
}

fn from_style(style: &str, property: &str) -> Option<i64> {
    style
        .split(';')
        .filter_map(|rule| rule.split_once(':'))
        .find(|(name, _)| name.trim().eq_ignore_ascii_case(property))
        .and_then(|(_, value)| parse_length(value))
}

fn parse_length(value: &str) -> Option<i64> {
    let value = value.trim();
    let digits = value.trim_end_matches(|c: char| c.is_ascii_alphabetic() || c == '%');
    digits.trim().parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_one_by_one_image_has_zero_area() {
        assert!(is_zero_area(Some(1), Some(1)));
    }

    #[test]
    fn either_dimension_being_zero_is_enough() {
        assert!(is_zero_area(Some(0), Some(600)));
        assert!(is_zero_area(Some(600), Some(0)));
    }

    #[test]
    fn a_single_dimension_of_one_is_not_enough() {
        assert!(!is_zero_area(Some(1), Some(400)));
        assert!(!is_zero_area(Some(1), None));
    }

    #[test]
    fn an_undeclared_image_is_left_alone() {
        assert!(!is_zero_area(None, None));
    }

    #[test]
    fn reads_lengths_from_attributes_and_styles() {
        assert_eq!(declared(Some("1".into()), None, "width"), Some(1));
        assert_eq!(
            declared(None, Some("width:0px;height:0px"), "width"),
            Some(0)
        );
        assert_eq!(declared(None, Some("HEIGHT : 1px ;"), "height"), Some(1));
        assert_eq!(declared(None, Some("border:1px solid red"), "width"), None);
    }

    #[test]
    fn an_attribute_wins_over_a_style() {
        assert_eq!(
            declared(Some("600".into()), Some("width:1px"), "width"),
            Some(600)
        );
    }
}
