#!/usr/bin/env python3
"""RFC 6238 TOTP, the codes totp.sh checks a fresh enrolment against before it writes anything.

    totp.py code SECRET [TIME] [DIGITS]     the code SECRET produces at TIME (now if omitted)
    totp.py verify SECRET CODE [TIME] [DIGITS]   exit 0 if CODE is valid for SECRET within one
                                          30s step of TIME (now if omitted), else 1

SECRET is base32. TIME is a Unix timestamp; DIGITS defaults to 6.
"""
import base64
import hashlib
import hmac
import struct
import sys
import time

PERIOD = 30
WINDOW = 1


def hotp(key, counter, digits):
    mac = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = mac[-1] & 0x0F
    truncated = struct.unpack(">I", mac[offset : offset + 4])[0] & 0x7FFFFFFF
    return str(truncated % (10**digits)).zfill(digits)


def code_at(secret_b32, for_time, digits=6):
    key = base64.b32decode(secret_b32.upper() + "=" * (-len(secret_b32) % 8))
    return hotp(key, int(for_time // PERIOD), digits)


def verify(secret_b32, code, for_time, digits=6):
    counter = int(for_time // PERIOD)
    key = base64.b32decode(secret_b32.upper() + "=" * (-len(secret_b32) % 8))
    return any(hotp(key, counter + step, digits) == code for step in range(-WINDOW, WINDOW + 1))


def main():
    command, args = sys.argv[1], sys.argv[2:]
    if command == "code":
        secret = args[0]
        for_time = float(args[1]) if len(args) > 1 else time.time()
        digits = int(args[2]) if len(args) > 2 else 6
        print(code_at(secret, for_time, digits))
    elif command == "verify":
        secret, code = args[0], args[1]
        for_time = float(args[2]) if len(args) > 2 else time.time()
        digits = int(args[3]) if len(args) > 3 else 6
        sys.exit(0 if verify(secret, code, for_time, digits) else 1)
    else:
        sys.exit(f"unknown command {command}")


if __name__ == "__main__":
    main()
