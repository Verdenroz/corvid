use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use reqwest::dns::{Addrs, Name, Resolve, Resolving};

// Filtering inside the resolver rather than checking the URL first is what closes the
// rebinding window: reqwest connects to the addresses vetted here, not a second lookup.
pub struct PublicOnlyResolver;

impl Resolve for PublicOnlyResolver {
    fn resolve(&self, name: Name) -> Resolving {
        Box::pin(async move {
            let addrs: Vec<SocketAddr> = tokio::net::lookup_host((name.as_str(), 0))
                .await?
                .filter(|addr| is_public(addr.ip()))
                .collect();
            if addrs.is_empty() {
                return Err(format!("{} has no public address", name.as_str()).into());
            }
            Ok(Box::new(addrs.into_iter()) as Addrs)
        })
    }
}

pub fn is_public(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => is_public_v4(ip),
        IpAddr::V6(ip) => match ip.to_ipv4_mapped() {
            Some(mapped) => is_public_v4(mapped),
            None => is_public_v6(ip),
        },
    }
}

fn is_public_v4(ip: Ipv4Addr) -> bool {
    let [a, b, ..] = ip.octets();
    !(ip.is_unspecified()
        || ip.is_loopback()
        || ip.is_private()
        || ip.is_link_local()
        || ip.is_broadcast()
        || ip.is_documentation()
        || ip.is_multicast()
        || (a == 100 && (64..128).contains(&b))
        || (a == 198 && (18..20).contains(&b))
        || a >= 240)
}

fn is_public_v6(ip: Ipv6Addr) -> bool {
    let segments = ip.segments();
    !(ip.is_unspecified()
        || ip.is_loopback()
        || ip.is_multicast()
        || (segments[0] & 0xfe00) == 0xfc00
        || (segments[0] & 0xffc0) == 0xfe80
        || (segments[0] == 0x2001 && segments[1] == 0x0db8))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn public(literal: &str) -> bool {
        is_public(literal.parse().unwrap())
    }

    #[test]
    fn rejects_the_cloud_metadata_address() {
        assert!(!public("169.254.169.254"));
    }

    #[test]
    fn rejects_loopback_and_private_space() {
        for literal in [
            "127.0.0.1",
            "10.0.0.1",
            "192.168.1.1",
            "172.16.0.1",
            "0.0.0.0",
        ] {
            assert!(!public(literal), "{literal} accepted");
        }
    }

    #[test]
    fn rejects_carrier_grade_nat_and_benchmarking_ranges() {
        for literal in [
            "100.64.0.1",
            "100.127.255.255",
            "198.18.0.1",
            "198.19.255.255",
        ] {
            assert!(!public(literal), "{literal} accepted");
        }
    }

    #[test]
    fn accepts_the_addresses_adjacent_to_those_ranges() {
        for literal in [
            "100.63.255.255",
            "100.128.0.1",
            "198.17.255.255",
            "198.20.0.1",
        ] {
            assert!(public(literal), "{literal} rejected");
        }
    }

    #[test]
    fn rejects_ipv6_local_and_documentation_space() {
        for literal in [
            "::1",
            "::",
            "fc00::1",
            "fd00::1",
            "fe80::1",
            "2001:db8::1",
            "ff02::1",
        ] {
            assert!(!public(literal), "{literal} accepted");
        }
    }

    #[test]
    fn sees_through_ipv4_mapped_ipv6() {
        assert!(!public("::ffff:127.0.0.1"));
        assert!(!public("::ffff:169.254.169.254"));
        assert!(public("::ffff:93.184.216.34"));
    }

    #[test]
    fn accepts_ordinary_public_addresses() {
        assert!(public("93.184.216.34"));
        assert!(public("2606:2800:220:1:248:1893:25c8:1946"));
    }
}
