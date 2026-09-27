#!/usr/bin/env python3
"""The Cloudflare calls install.sh makes, run on the server with the token on stdin.

    cloudflare.py zones                  one line per zone: NAME ID edit|read-only
    cloudflare.py a-records ZONE NAME    the addresses NAME's A records point to
    cloudflare.py create-a ZONE NAME IP  adds an A record, not proxied
    cloudflare.py create-txt ZONE NAME CONTENT   adds a TXT record
    cloudflare.py update-record ZONE ID CONTENT  replaces a record's content
    cloudflare.py records ZONE NAME TYPE one line per record: ID CONTENT
    cloudflare.py delete ZONE ID         removes one record
    cloudflare.py dkim ZONE DOMAIN       one line per DKIM key record: ID SELECTOR
    cloudflare.py dnssec ZONE            the zone's DNSSEC status, then its DS record if any
    cloudflare.py enable-dnssec ZONE     turns DNSSEC on, then prints the same
    cloudflare.py account ZONE           the id of the account that owns ZONE
    cloudflare.py worker-owner ACCOUNT HOSTNAME    the service name bound to HOSTNAME, or empty
    cloudflare.py worker-permission ACCOUNT ZONE   fails naming the permission it lacks
    cloudflare.py deploy-worker ACCOUNT ZONE NAME HOSTNAME   uploads the script on stdin
                                          (after the token line) and binds it to HOSTNAME
    cloudflare.py remove-worker ACCOUNT NAME HOSTNAME   removes the custom domain and script,
                                          doing nothing if neither exists

Exits 1 with Cloudflare's own error message when a call fails.
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

# Overridable so the test suite can point this at a fake server instead of the real Cloudflare.
API = os.environ.get("CORVID_CLOUDFLARE_API", "https://api.cloudflare.com/client/v4/")


def call(token, path, body=None, method=None, content_type="application/json", missing_permission=None):
    data = body if content_type != "application/json" else (None if body is None else json.dumps(body))
    request = urllib.request.Request(
        API + path,
        data=None if data is None else data.encode(),
        method=method or ("GET" if body is None else "POST"),
        headers={"Authorization": "Bearer " + token, "Content-Type": content_type},
    )
    status = 200
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            answer = json.load(response)
    except urllib.error.HTTPError as error:
        status = error.code
        answer = json.load(error)
    if not answer.get("success"):
        errors = "; ".join(e.get("message", "") for e in answer.get("errors", []))
        errors = errors or "Cloudflare refused the request"
        if missing_permission and status == 403:
            sys.exit(f"{errors}: add the {missing_permission!r} permission")
        sys.exit(errors)
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
    elif command == "create-txt":
        zone, name, content = args
        call(token, f"zones/{zone}/dns_records",
             {"type": "TXT", "name": name, "content": content, "ttl": 1})
    elif command == "update-record":
        zone, record, content = args
        call(token, f"zones/{zone}/dns_records/{record}", {"content": content}, method="PATCH")
    elif command == "account":
        (zone,) = args
        print(call(token, f"zones/{zone}")["account"]["id"])
    elif command == "worker-owner":
        account, hostname = args
        query = urllib.parse.urlencode({"hostname": hostname})
        domains = call(token, f"accounts/{account}/workers/domains?{query}")
        print(domains[0]["service"] if domains else "")
    elif command == "worker-permission":
        account, zone = args
        call(token, f"accounts/{account}/workers/scripts", missing_permission="Workers Scripts:Edit")
        call(token, f"accounts/{account}/workers/domains?zone_id={zone}",
             missing_permission="Workers Routes:Edit")
    elif command == "deploy-worker":
        account, zone, name, hostname = args
        script = sys.stdin.read()
        call(token, f"accounts/{account}/workers/scripts/{name}", script, method="PUT",
             content_type="application/javascript", missing_permission="Workers Scripts:Edit")
        call(token, f"accounts/{account}/workers/domains",
             {"environment": "production", "hostname": hostname, "service": name, "zone_id": zone},
             method="PUT", missing_permission="Workers Routes:Edit")
    elif command == "remove-worker":
        account, name, hostname = args
        query = urllib.parse.urlencode({"hostname": hostname})
        for domain in call(token, f"accounts/{account}/workers/domains?{query}"):
            if domain.get("service") == name:
                call(token, f"accounts/{account}/workers/domains/{domain['id']}", method="DELETE")
        if any(script["id"] == name for script in call(token, f"accounts/{account}/workers/scripts")):
            call(token, f"accounts/{account}/workers/scripts/{name}", method="DELETE")
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
