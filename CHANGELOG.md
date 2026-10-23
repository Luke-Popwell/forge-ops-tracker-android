# Changelog

## 0.17.0 (2026-10-23)

- PII scrubbing catches card numbers however they're grouped (bare, 4-4-4-4, Amex's 4-6-5, or next to a
  security code) when the digits have a card network's prefix and pass the Luhn check, so long ids stay
  readable; IBANs, compact or in groups of four, by their own check digits; and fields named `iban`,
  `account_number`, `routing_number` or `bank_account`. The rules are in sdks/PII_SCRUBBING.md, the same in
  every SDK.

## 0.16.0 (2026-10-22)

- Strict privacy: the message fingerprint now also replaces email addresses with `<email>` and quoted
  values (`"..."`, and `'...'` when the quotes aren't part of a word) with `<str>`, before the existing
  steps. `KeyError: 'Jane Doe'` and `KeyError: 'Bob Smith'` now group together, and no guessable value sits
  behind the hash. Errors whose messages contain quotes or emails get new fingerprints once, so they open
  new issues after upgrading.

## 0.15.1 (2026-10-22)

- The README says what strict privacy does in its opening lines.
- The source is public at https://github.com/Luke-Popwell/forge-ops-tracker-android, and the package links to it.

## 0.15.0 (2026-10-20)

- Strict privacy: a raw path now keeps only its first segment, instead of only losing its digit
  segments, since a later segment can name a person even without a digit (`/patients/janedoe`).
  `GET /patients/jane-doe/12` is sent as `GET /patients/:path`, `/42/edit` as `/:id/:path`. A route
  template (any segment such as `:id`, `{id}`, `<int:id>`, `[slug]` or `*path`) is kept as it is, so
  `GET /v1/orders/{id}` is no longer sent as `GET /:id/orders/{id}`. Applies to span names and
  performance sample transaction names, the same rule the server applies. New
  `StrictPrivacy.strictPath`; `pathWithoutIds` still works (deprecated) and now calls it.

## 0.14.0 (2026-10-20)

- Strict privacy, for apps that can't let personal or health data leave the device. New
  `Configuration.strictPrivacy` (or `FORGE_OPS_STRICT_PRIVACY=true`) sends only structured facts:
  the message of a crash, ANR or captured error is replaced by `message_fingerprint`, a hash of it
  (after PII scrubbing) with numbers and ids taken out, so errors still group; breadcrumbs aren't
  recorded or sent; the SQL statement isn't sent; the user is sent as its `id` only; and context
  and tags keep only the keys in `privacyAllowedFields` (or
  `FORGE_OPS_PRIVACY_ALLOWED_FIELDS=error_code,feature`) with a string, number or boolean value.
  Spans keep only method and host for outbound calls, a span named by a raw path has its ids taken
  out (`GET /patients/:id`, `/patients/:id`), and span data keeps only `db.statement` and
  `db.system`. Off by default; nothing changes unless it's turned on. ForgeOps applies the same
  rules on arrival when the project's own Strict privacy setting is on.

## 0.13.0 (2026-10-13)

- A delivery the server will never accept is no longer sent again and again. A response of 4xx
  (other than 408 and 429) now means the payload is let go: a crash or ANR report file is deleted,
  and a batch of performance samples, metrics or infrastructure readings is dropped. A network
  error, a timeout, a 5xx, 408 or 429 still keeps the payload for a later try, but not for good: a
  batch is dropped after five failed flushes in a row, at most 20 reports wait on disk (the oldest
  beyond that are deleted), and a report older than 7 days is deleted. Each drop is passed once to
  `config.logger`, if you set one, saying what was dropped and why. Before, any failure kept the
  payload, so a rejected report was uploaded at every launch and a rejected batch at every flush.
  New constants `CrashStore.MAX_PENDING_FILES`, `CrashStore.MAX_PENDING_AGE_MILLIS`,
  `PerformanceFlusher.MAX_CONSECUTIVE_FAILURES` and `MetricBuffer.MAX_CONSECUTIVE_FAILURES`. No
  public API changed: every `Client.deliver*` still returns `true` only for a 2xx.

## 0.12.0 (2026-10-03)

- **The default environment changed from `development` to `production`.** With `FORGE_OPS_ENVIRONMENT`
  unset and `config.environment` not assigned, the client used to report itself as `development`,
  which isn't one of the default enabled environments (`production`, `staging`), so an app set up
  with only a DSN silently sent nothing. It now defaults to `production`, the same as every other
  ForgeOps client, so a DSN alone is enough. A debug build is not treated as development. To keep
  the old behavior, set `config.environment = "development"` wherever you don't want errors sent,
  for example `config.environment = if (BuildConfig.DEBUG) "development" else "production"`.
- When a DSN is configured but the environment isn't enabled, `init` now says so once per process,
  as a warning in Logcat under the tag `ForgeOps`: `[ForgeOps] Not sending: this environment is
  "development", and only production, staging are enabled. Set config.environment = "production"
  (or add "development" to the enabled environments) to send from here.` Nothing is logged when no
  DSN is configured. New `Configuration.notSendingWarning` returns that message, or null.

## 0.11.0 (2026-09-29)

- SQL masking now matches the server's newer rules, so fewer values can slip through. A
  backslash-escaped quote no longer ends a string early (`'o\'brien'` and `E'o\'brien'` are each one
  `?`), a string's type prefix goes with it (`X'DEADBEEF'`, `N'...'`, `B'...'`, `U&'...'`), and hex,
  binary, exponent and leading-dot numbers are masked (`0x1F`, `0b101`, `3e10`, `1.5E-3`, `.5`). A
  `database` span whose `dbSystem` is `mysql` or `mariadb` also has its double-quoted strings
  masked, since those databases read `"..."` as a string, not a name. A string cut off right after a
  backslash is masked to the end too. Applies to database spans and to the statement sent with a
  database error.

## 0.10.0 (2026-09-25)

- A `database` span can now carry the SQL it ran, such as a local SQLite query: new `measureSpan`
  and `recordSpan` overloads (on `Trace` and the null-safe `Trace?` forms) take `statement` and
  optionally `dbSystem`, such as `"sqlite"`. The statement is masked on the device (every string and
  number literal becomes `?`), cut at 4000 characters, and sent in the span's data as `db.statement`,
  with `db.system` lowercased. A `db.statement` put in `data` directly is masked the same way. Both
  are ignored on spans of any other kind. The existing overloads are unchanged.

## 0.9.0 (2026-09-25)

- New `ForgeOpsTracker.recordChange(kind, title, details, environment, service, actor, url, id, occurredAt)` tells ForgeOps what changed in your app, typically from a feature flag or remote config change callback, so the change shows on the timeline next to the errors around it. `kind` is one of `Change.KINDS` (anything else is sent as `"other"`). Sent to `/api/v1/changes` on a private daemon thread, so it's safe to call from the main thread; it never throws, a failed request or a plan without change tracking is silent, and it's a no-op when reporting isn't enabled or before `init`. `ForgeOpsTracker.flushChanges()` blocks until every recorded change is sent. New `Configuration.changesUri` and `Client.deliverChange`.

## 0.8.0

- Follow a request from the app into your backend, using the W3C Trace Context standard (`traceparent`). `Trace.measureHttpSpan(method, url) { headers -> ... }` (for a blocking call) and `Trace.startHttpSpan(method, url)` (for a callback-style client; add `span.headers` to the request, then call `span.finish()` from its callbacks) record the request as an `http` span named after the method and host, and give you the `traceparent` header whose parent id is that span's own id, so the backend's root span nests under it. Both are also defined on a nullable `Trace?`. New `propagateTraces` (default true) and `tracePropagationTargets` (default null, meaning every host; or a list of host Strings, each matching that host and its subdomains, and/or Regexes) control where the header goes.
- Errors captured while a trace is open now carry a top-level `trace_id`, so ForgeOps can link them to the backend error from the same request. `captureException` uses the most recently started trace that hasn't finished, or the one passed as its new `trace` argument; uncaught exception and ANR reports carry it too. `ForgeOpsTracker.currentTraceId()` returns it, and `Trace.traceId` is now public. Errors captured with no open trace are unchanged.
- `startTrace` now returns a trace with `trackTracing = false` (previously null): it records and sends no spans, but still has an id for errors and the header, since that id is also what links an error here to one in your backend. It still returns null when reporting isn't enabled or before `init`.
- Trace and span ids were already W3C shaped (32 and 16 lowercase hex characters); they are now also guaranteed never to be all zeros, the one value the standard reserves as invalid.

This client has earlier tagged releases (see `forgeopstracker/build.gradle.kts`'s version history in source control), but no `CHANGELOG.md` existed for it before this entry; it starts here rather than backfilling every earlier version.
