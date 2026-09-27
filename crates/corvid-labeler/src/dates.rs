/// Days since the epoch, from a JMAP UTC date such as `2026-09-07T12:34:56Z`.
///
/// Day granularity is enough: the prior's half-life is measured in months, and a whole-day
/// value makes a correction on the day of receipt retract exactly what it observed.
pub fn days(received_at: &str) -> Option<f64> {
    let (year, rest) = received_at.split_once('-')?;
    let (month, rest) = rest.split_once('-')?;
    let day = rest.get(..2)?;
    Some(civil_days(year.parse().ok()?, month.parse().ok()?, day.parse().ok()?) as f64)
}

fn civil_days(year: i64, month: i64, day: i64) -> i64 {
    let year = if month <= 2 { year - 1 } else { year };
    let era = if year >= 0 { year } else { year - 399 } / 400;
    let year_of_era = year - era * 400;
    let shifted = (month + 9) % 12;
    let day_of_year = (153 * shifted + 2) / 5 + day - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146097 + day_of_era - 719468
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_epoch_is_day_zero() {
        assert_eq!(days("1970-01-01T00:00:00Z"), Some(0.0));
    }

    #[test]
    fn a_day_later_is_one_more() {
        assert_eq!(days("1970-01-02T00:00:00Z"), Some(1.0));
    }

    #[test]
    fn counts_across_a_leap_day() {
        let before = days("2024-02-28T00:00:00Z").unwrap();
        let after = days("2024-03-01T00:00:00Z").unwrap();

        assert_eq!(after - before, 2.0, "2024 has a 29th");
    }

    #[test]
    fn the_time_of_day_does_not_move_it() {
        assert_eq!(days("2026-09-07T23:59:59Z"), days("2026-09-07T00:00:01Z"));
    }

    #[test]
    fn a_known_date_matches() {
        assert_eq!(days("2026-09-07T12:00:00Z"), Some(20703.0));
    }

    #[test]
    fn rejects_something_that_is_not_a_date() {
        assert_eq!(days("not a date"), None);
        assert_eq!(days(""), None);
    }
}
