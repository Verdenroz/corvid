#!/usr/bin/env python3
"""Type-check a plan against Stalwart's schema.

The schema is `stalwart-schema-v<version>.json` beside this script, matching the pinned image.
Without it, the newest schema stalwart-cli cached from a server is used instead.

`apply --dry-run` validates object names, variant tags and reference syntax. It does not check
field shapes, so a duration written as "12h" where the schema wants milliseconds reports a clean
plan and fails partway through a real apply, after earlier objects have been written.

`--dry-run` is not an alternative: it needs working credentials, so it cannot gate a plan in CI,
on a fresh clone, or during review. This can.

The cache is written by any successful stalwart-cli call and needs no credentials to read.
With neither schema, there is nothing to check against and that is reported rather than passed.

Only one thing is counted rather than failed: a shape this cannot reach. A checker that fails a
plan it does not understand is worse than none, because the next person silences it. Everything
the schema can settle is a finding, including a name or a variant tag that is simply not in it.

Reference resolution needs no schema, so it runs even with no schema at all.

Required fields are not expressible here. The schema carries no `required` anywhere, so a plan
omitting one still dry-runs and applies cleanly until the server refuses it. Absence from a
`defaults` map looks like a proxy and is not one, since most optional fields lack defaults too.
"""

import glob
import json
import os
import re
import sys

# The schema's type vocabulary once serialized. A `set` is written as a map of member to true,
# and every reference kind is a string.
ACCEPTS = {
    "boolean": (bool,),
    "number": (int, float),
    "string": (str,),
    "enum": (str,),
    "objectId": (str,),
    "blobId": (str,),
    "utcDateTime": (str,),
    "set": (dict,),
    "map": (dict,),
    "object": (dict,),
    # A map keyed by stringified integers. Stalwart refuses an array with invalidPatch.
    "objectList": (dict,),
}
PINNED = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stalwart-schema-v0.16.21.json")
CACHE = os.path.expanduser("~/.cache/stalwart-cli/*/schema-*.json")
TEMPLATE = re.compile(r"\$\{[A-Z_]+\}")
WHOLESALE = {"aliases", "credentials", "quotas"}


def load_schema():
    if os.path.exists(PINNED):
        return json.load(open(PINNED)), "the pinned schema"
    found = sorted(glob.glob(CACHE), key=os.path.getmtime, reverse=True)
    return (json.load(open(found[0])), "the cached schema") if found else (None, None)


def objects_in(entry):
    """A plan entry holds one object for `update`, or a map of id to object otherwise."""
    value = entry.get("value")
    if not isinstance(value, dict):
        return []
    if entry.get("@type") == "update":
        return [value]
    return [v for v in value.values() if isinstance(v, dict)]


def resolve(key, obj, schema):
    """Properties for a schema key, and a complaint if `@type` names something that is not a variant.

    Returns `(None, None)` when the shape is simply unreachable, which is counted, not failed.
    """
    direct = schema["fields"].get(key)
    if direct is not None:
        return direct.get("properties", {}), None

    variants = schema["schemas"].get(key, {}).get("variants")
    if not variants:
        return None, None

    tag = obj.get("@type")
    named = next((v for v in variants if v["name"] == tag), None)
    if named is None:
        return None, f"@type {tag!r} is not one of {', '.join(v['name'] for v in variants)}"
    # A variant with no schema of its own is the tag and nothing else.
    if not named.get("schemaName"):
        return {}, None
    carrying = schema["fields"].get(named["schemaName"])
    return (carrying.get("properties", {}) if carrying else None), None


def wrong_value(where, kind, value, schema):
    """What the server would reject about a value the plan can hold."""
    want = kind.get("type")
    accepts = ACCEPTS.get(want)
    if accepts and (
        isinstance(value, bool) != (want == "boolean") or not isinstance(value, accepts)
    ):
        return f"{where}: schema wants {want}, plan has {value!r}"

    if want == "enum":
        members = [m["name"] for m in schema["enums"].get(kind.get("enumName", ""), [])]
        if members and value not in members:
            return f"{where}: not a member of {kind['enumName']}, plan has {value!r}"

    if want == "set" and kind.get("minItems"):
        # A set is serialized as member -> bool, so only the true members are in it.
        present = sum(1 for member in value.values() if member is True)
        if present < kind["minItems"]:
            return f"{where}: needs at least {kind['minItems']} member(s) set true, plan has {present}"

    if want == "number":
        if kind.get("format") == "unsignedInteger" and (not isinstance(value, int) or value < 0):
            return f"{where}: wants an unsigned integer, plan has {value!r}"
        low, high = kind.get("min"), kind.get("max")
        if low is not None and value < low:
            return f"{where}: below the schema minimum {low}, plan has {value!r}"
        if high is not None and value > high:
            return f"{where}: above the schema maximum {high}, plan has {value!r}"
    return None


def check(where, key, obj, schema, unchecked):
    properties, problem = resolve(key, obj, schema)
    if problem:
        return [f"{where}: {problem}"]
    if properties is None:
        unchecked.append(where)
        return []

    found = []
    for name, value in obj.items():
        if name == "@type":
            continue
        # A JSON-pointer patch (e.g. "quotas/maxAppPasswords") checks only the base property, unless
        # it names one whole item of an object list ("aliases/0"), whose shape the schema gives.
        base, _, leaf = name.partition("/")
        prop = properties.get(base)
        at = f"{where}.{name}"
        if prop is None:
            found.append(f"{at}: not a property of this object")
            continue
        kind = prop.get("type", {})
        if leaf.isdigit() and kind.get("type") == "objectList" and isinstance(value, dict):
            found += check(at, kind["objectName"], value, schema, unchecked)
            continue
        if leaf:
            unchecked.append(at)
            continue

        if value is None and kind.get("nullable"):
            continue
        # A template stands in for a value of any type, so it cannot be checked here.
        if isinstance(value, str) and TEMPLATE.fullmatch(value):
            continue
        wrong = wrong_value(at, kind, value, schema)
        if wrong:
            found.append(wrong)
            continue

        nested = kind.get("objectName")
        if not nested:
            continue
        if kind.get("type") == "object":
            found += check(at, nested, value, schema, unchecked)
        elif kind.get("type") == "objectList":
            for key, item in value.items():
                if not key.isdigit():
                    found.append(f"{at}: object list keys are stringified integers, plan has {key!r}")
                elif isinstance(item, dict):
                    found += check(f"{at}[{key}]", nested, item, schema, unchecked)
    return found


def strings_in(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for inner in value.values():
            yield from strings_in(inner)
    elif isinstance(value, list):
        for inner in value:
            yield from strings_in(inner)


def dangling(entries):
    """References naming nothing the plan defines.

    A warning rather than a failure: whether `#name` may also name an object already on the
    server is unsettled, and if it may, a second apply against a configured host would be failed
    for being correct. Settle it by dry-running a plan with one bad reference against a real
    server, then make this fatal if references turn out to be plan-local.
    """
    defined = set()
    for entry in entries:
        if entry.get("@type") != "update" and isinstance(entry.get("value"), dict):
            defined |= set(entry["value"])

    found = []
    for number, entry in enumerate(entries, 1):
        for value in strings_in(entry.get("value")):
            if value.startswith("#") and value[1:] not in defined:
                found.append(f"line {number}: {value} names nothing this plan defines")
    return found


def wholesale(entries):
    """Account properties a plan may only patch by key.

    Each replaces the server's whole map, so naming one in an Account entry erases whatever the
    host added at runtime on every deploy: minted aliases, app passwords, quotas set by hand.
    """
    found = []
    for number, entry in enumerate(entries, 1):
        if entry.get("object") != "Account":
            continue
        for obj in objects_in(entry):
            for name in sorted(WHOLESALE & set(obj)):
                found.append(f"line {number}: Account.{name} replaces the server's whole map; patch {name}/<key> instead")
    return found


def complaints(entry, schema, unchecked):
    name = entry.get("object")
    if f"x:{name}" not in schema["objects"]:
        return [f"{name}: not an object in this schema"]
    found = []
    for obj in objects_in(entry):
        found += check(name, f"x:{name}", obj, schema, unchecked)
    return found


def main():
    plan = sys.argv[1] if len(sys.argv) > 1 else "deploy/stalwart.plan.ndjson"
    entries = [json.loads(line) for line in open(plan) if line.strip()]
    warnings = dangling(entries)

    schema, source = load_schema()
    problems, unchecked = wholesale(entries), []
    if schema is not None:
        for number, entry in enumerate(entries, 1):
            problems += [f"line {number}: {c}" for c in complaints(entry, schema, unchecked)]

    # The verdict travels with its reasons, or a log that keeps only one of the two streams
    # reads a failure with nothing saying why.
    out = sys.stderr if problems else sys.stdout
    for problem in problems:
        print(problem, file=out)
    for warning in warnings:
        print(f"warning: {warning}", file=out)
    if unchecked:
        print(f"  not checked, shape unreachable: {', '.join(sorted(set(unchecked)))}", file=out)

    # The verdict carries the warning count and whether the schema half ran, or it reads as a
    # clean bill of health for checks that did not happen.
    verdict = "FAIL" if problems else "ok"
    against = source if schema is not None else "references only, no schema"
    trailing = f", {len(warnings)} warning(s)" if warnings else ""
    print(f"{verdict}: {plan} against {against}{trailing}", file=out)
    if problems:
        return 65
    return 69 if schema is None else 0


if __name__ == "__main__":
    sys.exit(main())
