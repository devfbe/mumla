# Backlog

Open requests, newest first. Move an item out once it ships.

## Client certificates

- **Delete a client certificate.** User request (2026-10-05): one's own certificates cannot be
  removed. The database already has `MumlaDatabase.removeCertificate(id)`, but no screen calls it.
  Open points: what happens to the default when it is the deleted one (fall back to "no
  certificate"), and a confirmation dialog that suggests exporting first, since a deleted
  certificate loses every server registration tied to it.
- **Rename a client certificate.** User request (2026-10-05): rename the certificate (the name
  shown in the list, e.g. `mumla_2026-09-20-16-09-33.p12`). Needs a database update method and
  a UI entry point next to select, import and export. The name only labels the stored entry; the
  export file name derives from it.
- **Distinguish generated certificates per variant.** Generated names use
  `certificate_export_format` (`mumla_%s.p12`) in every flavor, so beta and release certificates
  look alike; consider a flavor-specific prefix.

## Connection

- **Reconnect uses the old session configuration.** `SessionManager.reconnect()` reconnects with
  the last session's `SessionConfig`, so a certificate (or TCP/Tor) change made since then is
  ignored until the user connects from the server list again. Rebuild the configuration from the
  current settings instead.
- **Silent connect without a certificate.** When a stored certificate cannot be decrypted (Android
  Keystore key lost, e.g. after a device transfer), `SessionSettings.forServer` connects without
  one and says nothing (`TODO(acomminos)` there). Tell the user and offer to re-import.
