#!/usr/bin/env python3
"""The Cloudflare calls install.sh makes, run on the server with the token on stdin.

    cloudflare.py zones                  one line per zone: NAME ID edit|read-only
    cloudflare.py a-records ZONE NAME    the addresses NAME's A records point to
    cloudflare.py create-a ZONE NAME IP  adds an A record, not proxied
    cloudflare.py srv-records ZONE NAME  one line per SRV record: ID PORT TARGET
    cloudflare.py create-srv ZONE NAME PORT TARGET   adds an SRV record, priority 0 weight 5
    cloudflare.py create-txt ZONE NAME CONTENT [COMMENT]   adds a TXT record
    cloudflare.py update-record ZONE ID CONTENT  replaces a record's content
    cloudflare.py records ZONE NAME TYPE one line per record: ID CONTENT
    cloudflare.py delete ZONE ID         removes one record
    cloudflare.py dkim ZONE DOMAIN       one line per DKIM key record: ID SELECTOR
    cloudflare.py dnssec ZONE            the zone's DNSSEC status, then its DS record if any
    cloudflare.py enable-dnssec ZONE     turns DNSSEC on, then prints the same
    cloudflare.py account ZONE           the id of the account that owns ZONE
    cloudflare.py worker-owner ACCOUNT HOSTNAME    the service name bound to HOSTNAME, or empty
    cloudflare.py worker-txt ZONE NAME COMMENT   "ID CONTENT" of the TXT record at NAME whose
                                          comment is COMMENT, identified that way rather than by
                                          content, or empty
    cloudflare.py deploy-worker ACCOUNT ZONE NAME HOSTNAME   uploads the module on stdin
                                          (after the token line) and binds it to HOSTNAME. Fails
                                          naming the permission it lacks, not a bare 403.
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
import uuid

# Overridable so the test suite can point this at a fake server instead of the real Cloudflare.
API = os.environ.get("CORVID_CLOUDFLARE_API", "https://api.cloudflare.com/client/v4/")

# Pinned rather than "today": a stable date means a rerun never changes what already works.
WORKER_COMPATIBILITY_DATE = "2024-01-01"


def multipart(fields):
    """Encodes fields as multipart/form-data: (name, filename_or_None, content_type, bytes)."""
    boundary = uuid.uuid4().hex
    body = bytearray()
    for name, filename, content_type, content in fields:
        disposition = f'form-data; name="{name}"'
        if filename:
            disposition += f'; filename="{filename}"'
        body += f"--{boundary}\r\nContent-Disposition: {disposition}\r\n".encode()
        body += f"Content-Type: {content_type}\r\n\r\n".encode()
        body += content + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    return f"multipart/form-data; boundary={boundary}", bytes(body)


def call(token, path, body=None, method=None, content_type="application/json", missing_permission=None):
    if content_type == "application/json":
        data = None if body is None else json.dumps(body).encode()
    else:
        data = body
    request = urllib.request.Request(
        API + path,
        data=data,
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
    elif command == "srv-records":
        zone, name = args
        query = urllib.parse.urlencode({"type": "SRV", "name": name})
        for record in call(token, f"zones/{zone}/dns_records?{query}"):
            print(record["id"], record["data"]["port"], record["data"]["target"])
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
    elif command == "create-srv":
        zone, name, port, target = args
        call(token, f"zones/{zone}/dns_records",
             {"type": "SRV", "name": name, "ttl": 1,
              "data": {"priority": 0, "weight": 5, "port": int(port), "target": target}})
    elif command == "create-txt":
        zone, name, content = args[:3]
        record = {"type": "TXT", "name": name, "content": content, "ttl": 1}
        if len(args) > 3:
            record["comment"] = args[3]
        call(token, f"zones/{zone}/dns_records", record)
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
    elif command == "worker-txt":
        zone, name, comment = args
        query = urllib.parse.urlencode({"type": "TXT", "name": name})
        for record in call(token, f"zones/{zone}/dns_records?{query}"):
            if record.get("comment") == comment:
                print(record["id"], record["content"])
                break
    elif command == "deploy-worker":
        account, zone, name, hostname = args
        module = f"{name}.mjs"
        metadata = json.dumps({
            "main_module": module,
            "compatibility_date": WORKER_COMPATIBILITY_DATE,
        }).encode()
        content_type, body = multipart([
            ("metadata", None, "application/json", metadata),
            (module, module, "application/javascript+module", sys.stdin.read().encode()),
        ])
        call(token, f"accounts/{account}/workers/scripts/{name}", body, method="PUT",
             content_type=content_type, missing_permission="Workers Scripts:Edit")
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
