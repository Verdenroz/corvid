/// Seconds since the epoch, from a Stalwart UTC timestamp such as `2026-09-07T12:34:56Z`.
pub fn seconds(created_at: &str) -> Option<i64> {
    let (date, time) = created_at.strip_suffix('Z')?.split_once('T')?;
    let (year, rest) = date.split_once('-')?;
    let (month, day) = rest.split_once('-')?;
    let (hour, rest) = time.split_once(':')?;
    let (minute, second) = rest.split_once(':')?;
    let days = civil_days(year.parse().ok()?, month.parse().ok()?, day.parse().ok()?);
    let hour: i64 = hour.parse().ok()?;
    let minute: i64 = minute.parse().ok()?;
    let second: i64 = second.parse().ok()?;
    Some(days * 86400 + hour * 3600 + minute * 60 + second)
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
    fn the_epoch_is_zero() {
        assert_eq!(seconds("1970-01-01T00:00:00Z"), Some(0));
    }

    #[test]
    fn an_hour_later_is_thirty_six_hundred_more() {
        assert_eq!(seconds("1970-01-01T01:00:00Z"), Some(3600));
    }

    #[test]
    fn a_captured_timestamp_parses() {
        assert_eq!(seconds("2026-09-27T02:08:58Z"), Some(1790474938));
    }

    #[test]
    fn rejects_something_that_is_not_a_timestamp() {
        assert_eq!(seconds("not a timestamp"), None);
        assert_eq!(seconds(""), None);
    }
}
