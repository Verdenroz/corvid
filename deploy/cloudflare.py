#!/usr/bin/env python3
"""The Cloudflare calls install.sh makes, run on the server with the token on stdin.

    cloudflare.py zones                  one line per zone: NAME ID edit|read-only
    cloudflare.py a-records ZONE NAME    the addresses NAME's A records point to
    cloudflare.py create-a ZONE NAME IP  adds an A record, not proxied
    cloudflare.py records ZONE NAME TYPE one line per record: ID CONTENT
    cloudflare.py delete ZONE ID         removes one record
    cloudflare.py dkim ZONE DOMAIN       one line per DKIM key record: ID SELECTOR
    cloudflare.py dnssec ZONE            the zone's DNSSEC status, then its DS record if any
    cloudflare.py enable-dnssec ZONE     turns DNSSEC on, then prints the same

Exits 1 with Cloudflare's own error message when a call fails.
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.cloudflare.com/client/v4/"


def call(token, path, body=None, method=None):
    request = urllib.request.Request(
        API + path,
        data=None if body is None else json.dumps(body).encode(),
        method=method or ("GET" if body is None else "POST"),
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            answer = json.load(response)
    except urllib.error.HTTPError as error:
        answer = json.load(error)
    if not answer.get("success"):
        errors = "; ".join(e.get("message", "") for e in answer.get("errors", []))
        sys.exit(errors or "Cloudflare refused the request")
    return answer["result"]


def main():
    token = sys.stdin.readline().strip()
    command, args = sys.argv[1], sys.argv[2:]
    if command == "zones":
        for zone in call(token, "zones?per_page=50"):
            access = "edit" if "#dns_records:edit" in zone.get("permissions", []) else "read-only"
            print(zone["name"], zone["id"], access)
    elif command == "a-records":
        zone, name = args
        query = urllib.parse.urlencode({"type": "A", "name": name})
        for record in call(token, f"zones/{zone}/dns_records?{query}"):
            print(record["content"])
    elif command == "records":
        zone, name, kind = args
        query = urllib.parse.urlencode({"type": kind, "name": name})
        for record in call(token, f"zones/{zone}/dns_records?{query}"):
            print(record["id"], record["content"])
    elif command == "dkim":
        zone, domain = args
        suffix = "._domainkey." + domain
        page = 1
        while True:
            records = call(token, f"zones/{zone}/dns_records?type=TXT&per_page=100&page={page}")
            for record in records:
                if record["name"].endswith(suffix) and record["content"].strip('"').startswith("v=DKIM1"):
                    print(record["id"], record["name"][: -len(suffix)])
            if len(records) < 100:
                break
            page += 1
    elif command == "delete":
        zone, record = args
        call(token, f"zones/{zone}/dns_records/{record}", method="DELETE")
    elif command == "create-a":
        zone, name, ip = args
        call(token, f"zones/{zone}/dns_records",
             {"type": "A", "name": name, "content": ip, "ttl": 1, "proxied": False})
    elif command == "dnssec":
        (zone,) = args
        state = call(token, f"zones/{zone}/dnssec")
        print(state["status"], state.get("ds") or "")
    elif command == "enable-dnssec":
        (zone,) = args
        state = call(token, f"zones/{zone}/dnssec", {"status": "active"}, method="PATCH")
        print(state["status"], state.get("ds") or "")
    else:
        sys.exit(f"unknown command {command}")


if __name__ == "__main__":
    main()
