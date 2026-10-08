# Real webhook bodies

These are raw `WebHook` POST bodies from a throwaway Stalwart v0.16.21 (docker, pinned image),
not constructed from the documentation. The docs say nothing about the body shape at all; a
fixture built from a guess would only test the guess against itself.

The `WebHook` was signed with `signatureKey: {"@type":"Value","secret":"test-sentinel-hmac-secret-0123456789"}`.
Account names in every body except `auth-success-primary-jmap.json` were changed to
`alice@corvid.test` after capture. That one is byte-for-byte as captured, since its signature is
checked.

`signature.rs`'s tests use that literal secret against the literal `X-Signature` header captured
alongside each body, so a change to the signing scheme fails a real comparison, not a
self-consistent one.

- `auth-success-primary-jmap.json`: a JMAP Basic-auth login with the account password. No
  `details` field at all for a primary-password sign-in.
- `auth-success-primary-imap.json`: the same account password over IMAPS. Same shape.
- `auth-success-app-password-imap.json`: an app password over IMAPS. Adds
  `"details":"Authenticated with app password"`, the one signal a body ever gives that a
  secondary credential was used; there is no credential id, index or description anywhere in it.
- `auth-failed.json`, `auth-failed-batch.json`: wrong-password attempts against the same
  account. The batch shows Stalwart can and does post several events in one request.
- `auth-too-many-attempts.json`: three failed IMAP logins on one connection. The account name
  sits only inside `data.causedBy.data`, one level down from every other field.
- `security-authentication-ban.json`: the ban Stalwart raised on this rig's own gateway address
  after lowering `Security.authBanRate` to provoke it. Restoring the account needed
  `delete BlockedIp` from a container on the same docker network, since the banned address was
  the docker bridge gateway the CLI itself was calling through.
- `auth-mfa-required-imap.json`, `auth-mfa-required-smtp.json`, `auth-mfa-required-jmap.json`:
  the account password entered correctly on an account with TOTP enabled, over each protocol
  that has no way to supply a code. Same shape on all three; `reason` is always "MFA token
  required" and there is no `details` field, since this only ever fires for the primary
  credential.
- `auth-success-after-mfa-jmap.json`: the `auth.success` that lands seconds later, in its own
  webhook delivery, once the same login's second request supplies the right code to
  `POST /api/auth`. `auth.mfa-required` and this `auth.success` never appear in the same
  delivery; Stalwart posts them as two separate requests, not a single request-response pair.

`rsa-test.der` is a throwaway RSA key made for the device approval tests, which sign tokens
with it and serve its public half as the fake issuer's key set. It protects nothing.
