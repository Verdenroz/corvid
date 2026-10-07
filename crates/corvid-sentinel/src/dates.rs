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

/// The current time as a Stalwart-style UTC timestamp, for a notice that carries no event
/// timestamp of its own.
pub fn rfc3339_now() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    rfc3339(secs)
}

/// A Stalwart-style UTC timestamp for `secs` since the epoch.
pub fn rfc3339(secs: i64) -> String {
    let (days, remainder) = (secs.div_euclid(86400), secs.rem_euclid(86400));
    let (year, month, day) = civil_from_days(days);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z",
        remainder / 3600,
        (remainder % 3600) / 60,
        remainder % 60
    )
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

/// The inverse of [`civil_days`]: year, month, day from days since the epoch.
fn civil_from_days(days: i64) -> (i64, i64, i64) {
    let z = days + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let day_of_era = z - era * 146097;
    let year_of_era =
        (day_of_era - day_of_era / 1460 + day_of_era / 36524 - day_of_era / 146096) / 365;
    let year = year_of_era + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let mp = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    (if month <= 2 { year + 1 } else { year }, month, day)
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
    fn civil_from_days_undoes_civil_days_across_a_wide_range() {
        for days in (-800_000..800_000).step_by(37) {
            let (year, month, day) = civil_from_days(days);
            assert_eq!(civil_days(year, month, day), days, "{days}");
        }
    }

    #[test]
    fn rfc3339_now_parses_back_to_a_recent_time() {
        let now = rfc3339_now();
        let parsed = seconds(&now).unwrap();
        let clock = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs() as i64;
        assert!((clock - parsed).abs() <= 2, "{now}");
    }
}
