const WEEKDAYS: [&str; 7] = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const MONTHS: [&str; 12] = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];

struct Parts {
    year: i64,
    month: i64,
    day: i64,
    hour: i64,
    minute: i64,
    second: i64,
}

fn parse(created_at: &str) -> Option<Parts> {
    let (date, time) = created_at.strip_suffix('Z')?.split_once('T')?;
    let (year, rest) = date.split_once('-')?;
    let (month, day) = rest.split_once('-')?;
    let (hour, rest) = time.split_once(':')?;
    let (minute, second) = rest.split_once(':')?;
    Some(Parts {
        year: year.parse().ok()?,
        month: month.parse().ok()?,
        day: day.parse().ok()?,
        hour: hour.parse().ok()?,
        minute: minute.parse().ok()?,
        second: second.parse().ok()?,
    })
}

/// Seconds since the epoch, from a Stalwart UTC timestamp such as `2026-09-07T12:34:56Z`.
pub fn seconds(created_at: &str) -> Option<i64> {
    let p = parse(created_at)?;
    let days = civil_days(p.year, p.month, p.day);
    Some(days * 86400 + p.hour * 3600 + p.minute * 60 + p.second)
}

/// The same timestamp as an RFC 5322 `Date` header value, always at `+0000` since Stalwart's
/// events carry no other zone.
pub fn rfc2822(created_at: &str) -> Option<String> {
    let p = parse(created_at)?;
    let days = civil_days(p.year, p.month, p.day);
    // 1970-01-01 was a Thursday, index 4 with Sunday at 0.
    let weekday = WEEKDAYS[(days + 4).rem_euclid(7) as usize];
    let month = MONTHS[(p.month - 1) as usize];
    Some(format!(
        "{weekday}, {:02} {month} {} {:02}:{:02}:{:02} +0000",
        p.day, p.year, p.hour, p.minute, p.second
    ))
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

    #[test]
    fn the_epoch_formats_as_a_thursday() {
        assert_eq!(
            rfc2822("1970-01-01T00:00:00Z").as_deref(),
            Some("Thu, 01 Jan 1970 00:00:00 +0000")
        );
    }

    #[test]
    fn a_captured_timestamp_formats() {
        assert_eq!(
            rfc2822("2026-09-27T02:08:58Z").as_deref(),
            Some("Sun, 27 Sep 2026 02:08:58 +0000")
        );
    }
}
