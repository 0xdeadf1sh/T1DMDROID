# T1DMSERVER HTTP & WebSocket API

This is the complete wire contract for the T1DMSERVER appliance — the
specification this app implements against, and the surface `:sync` writes to.

It is a copy, kept in step by hand. The normative original is `docs/API.md` in
the T1DMSERVER repository: where the two disagree, that one is right and this
one is the defect. Contract version 0.2.0.

All routes are prefixed with `/v1` and exchange `application/json` unless noted
otherwise. The server binds `server.bind:server.port` (default `0.0.0.0:8443`).

## Contents

- [Conventions](#conventions)
- [Authentication](#authentication)
  - [Login QR payload](#login-qr-payload)
- [Errors](#errors)
- [Object schemas](#object-schemas)
  - [Sample row](#sample-row)
  - [Meal event](#meal-event)
  - [Dose event](#dose-event)
  - [Basal schedule](#basal-schedule)
  - [Prediction](#prediction)
  - [Note](#note)
  - [Photo (metadata)](#photo-metadata)
  - [Alert](#alert)
  - [Model](#model)
  - [Stats](#stats)
- [Write endpoints (require `rw`)](#write-endpoints-require-rw)
  - [POST /v1/ingest](#post-v1ingest)
  - [PUT /v1/meals](#put-v1meals)
  - [PUT /v1/doses](#put-v1doses)
  - [PUT /v1/basal-schedule](#put-v1basal-schedule)
  - [PUT /v1/predictions](#put-v1predictions)
  - [PUT /v1/stats](#put-v1stats)
  - [POST /v1/notes](#post-v1notes)
  - [POST /v1/photos](#post-v1photos)
  - [POST /v1/alerts](#post-v1alerts)
- [Read endpoints (`ro` or `rw`)](#read-endpoints-ro-or-rw)
  - [GET /v1/series](#get-v1series)
  - [GET /v1/meals](#get-v1meals)
  - [GET /v1/doses](#get-v1doses)
  - [GET /v1/basal-schedule](#get-v1basal-schedule)
  - [GET /v1/predictions](#get-v1predictions)
  - [GET /v1/predictions/latest](#get-v1predictionslatest)
  - [GET /v1/notes](#get-v1notes)
  - [GET /v1/alerts](#get-v1alerts)
  - [GET /v1/photos](#get-v1photos)
  - [GET /v1/photos/{id}](#get-v1photosid)
  - [GET /v1/models](#get-v1models)
  - [GET /v1/models/{id}/meta](#get-v1modelsidmeta)
  - [GET /v1/models/{id}/download](#get-v1modelsiddownload)
  - [GET /v1/stats](#get-v1stats)
  - [GET /v1/health](#get-v1health)
    - [The store epoch and the re-mirror handshake](#the-store-epoch-and-the-re-mirror-handshake)
- [WebSocket](#websocket)
  - [GET /v1/stream?token=&lt;secret&gt;](#get-v1streamtokensecret)

## Conventions

- **Timestamps** are integers, milliseconds since the Unix epoch (UTC).
- **The 5-minute grid.** Samples, meal events, and dose events sit on a fixed
  five-minute grid: `ts % 300000 == 0`. The phone snaps a meal or dose event to
  the nearest grid point before sending it; an off-grid `ts` is rejected with
  `400` on [`POST /v1/ingest`](#post-v1ingest), [`PUT /v1/meals`](#put-v1meals),
  and [`PUT /v1/doses`](#put-v1doses). Notes and alerts carry a wall-clock `ts`
  and are not snapped.
- **`tz_offset`** is the client's UTC offset in minutes at the record's
  timestamp (e.g. `-300` for UTC−5), carried alongside the timestamp for
  local-time rendering. Samples, meal and dose events, basal slots, and notes
  each carry one.
- **Phone-authored identity.** Every physiologic record is authored on the
  phone. A sample is keyed by its grid `ts`; a meal, dose, basal slot, note, or
  alert carries a phone-minted `client_id`, unique and stable for the record's
  life. The server's own integer `id` is assigned internally and is never
  accepted on a write.
- **Client `updated_at` is verbatim.** The millisecond `updated_at` a write
  carries is the phone's clock; the server stores and returns it exactly as
  sent, never re-stamping it. It is the ordering key for the idempotent upsert:
  a redelivery with an equal or older `updated_at` is a no-op, a newer one
  replaces the record in place. The guard is strictly-newer for meals, doses,
  basal slots, notes, predictions, and statistics blocks.
  [`POST /v1/ingest`](#post-v1ingest) is the exception: it accepts an
  equal-or-newer `updated_at`, so two partial fills of one grid slot sharing a
  single `updated_at` both land.
- **`received_at` never crosses the wire.** The server's internal arrival stamp
  for a phone-authored record is present in neither a read response nor a stream
  frame. The server-assigned `id` and `created_at` are read-only but *are*
  surfaced, on the records that carry them — [Note](#note),
  [Photo](#photo-metadata), and [Alert](#alert). A meal, dose, basal slot, or
  prediction carries neither.
- **Storage units are fixed:** blood glucose in mg/dL; heart rate in bpm, steps
  as a count, and mood as a small integer; `sleep` and `exercise` are stored as
  plain scalar magnitudes. The mg/dL ↔ mmol/L toggle is display-only and never
  affects the wire format.
- **Meals and doses are curves, not sample columns.** A meal's carbohydrate
  appearance curve and a dose's insulin action curve travel as self-describing
  [meal](#meal-event) and [dose](#dose-event) events. A resolved `custom_curve`
  is a JSON array of `f64` sampled on the fixed 300000 ms grid with bucket 0 at
  the event `ts`; a parametric meal or dose instead carries its curve parameters
  and leaves `custom_curve` `null`.
- **Null asymmetry.** On a write (phone → server) an absent optional field may
  be omitted or sent as explicit `null` — the two are equivalent — except
  `tz_offset` on [`POST /v1/notes`](#post-v1notes), which must be omitted
  rather than nulled. For a sample, either form leaves the stored column
  untouched. On a read (server → phone) a missing value is explicit `null`,
  never omitted. This holds for the optional curve parameters of a
  [meal](#meal-event) or [dose](#dose-event) event too — the key is always
  present on the REST read and on the stream frame alike — even though a
  writer may omit it.

## Authentication

Access is by opaque bearer token — 32 random bytes rendered as hex. Tokens are
minted and revoked from the TUI only; there is no HTTP endpoint for token
management.

REST requests present the secret in a header:

```
Authorization: Bearer <secret>
```

The WebSocket carries it as a query parameter (browsers cannot set headers on a
WebSocket handshake): `GET /v1/stream?token=<secret>`.

The middleware resolves the secret to a live token, upserts a session (keyed on
token, client IP, and device/user-agent), enforces the token kind, and then
dispatches the handler. Sessions persist across WebSocket reconnects.

| Kind | Grants |
| --- | --- |
| `rw` | Every endpoint. At most one live `rw` token exists at a time. |
| `ro` | Read endpoints only. One per device, with an optional operator label. |

Every `/v1` endpoint requires a valid bearer token — `GET /v1/health` included. There is no
unauthenticated route.

### Login QR payload

The Sessions pane renders a login QR encoding a single JSON object:

```json
{ "type": "t1dm-login", "token": "<secret>", "addr": "100.64.0.1", "port": 8443 }
```

- `type` — the constant tag `t1dm-login`.
- `token` — the bearer secret (the same value used for `Authorization: Bearer` and `?token=`).
- `addr` / `port` — the operator-configured advertised endpoint (`[qr]` in `config.toml`, typically the
  Tailscale address). A client composes its base URL as `http://addr:port` (transport TLS is moot on the
  tailnet). Minting a fresh `rw` token revokes the prior one, so an older QR's `token` stops working.

## Errors

An error returns the mapped HTTP status with a JSON body:

```json
{ "error": "bad request: unknown series \"foo\"" }
```

| Status | Condition |
| --- | --- |
| 400 Bad Request | Malformed body or query — a body that is not valid JSON or does not match the endpoint's schema, a missing or mistyped field, a missing or non-`application/json` `Content-Type` on a JSON route, a bad field name, an unrecognized stats window, an off-grid `ts` on `POST /v1/ingest`, `PUT /v1/meals`, or `PUT /v1/doses`, a missing multipart part |
| 401 Unauthorized | Missing, unknown, or revoked bearer token |
| 403 Forbidden | A valid `ro` token on a write endpoint |
| 404 Not Found | Unknown resource id (photo, model) |
| 405 Method Not Allowed | A registered path addressed with a method it does not serve |
| 413 Payload Too Large | A request body exceeding the 16 MiB ceiling |
| 500 Internal Server Error | Store or filesystem failure |

Every request body is capped at 16 MiB; a larger one is rejected with `413` and
nothing is stored. A body the endpoint cannot decode — invalid JSON, a missing
or mistyped field, a missing `Content-Type` — is a `400`. Both carry the
envelope above, never a bare `415` or `422`. The one status raised outside it is
`405`, which the router answers before any handler, with an empty body.

## Object schemas

These canonical shapes are referenced throughout. In a read response every key
is present and an absent optional is explicit `null`, [meal](#meal-event) and
[dose](#dose-event) events included.

### Sample row

```json
{
  "ts": 1735689600000,
  "tz_offset": 0,
  "bg": 112.0,
  "hr": 68.0,
  "steps": 30.0,
  "sleep": 0.0,
  "exercise": 0.0,
  "mood": 4,
  "updated_at": 1735689605000
}
```

The six scalar series are `bg, hr, steps, sleep, exercise, mood`. All are
floating point except `mood`, which is an integer. `updated_at` is the phone's
millisecond clock when the row was authored, stored verbatim. Carbohydrates,
bolus, and basal are no longer sample columns — they are [meal](#meal-event)
and [dose](#dose-event) events.

### Meal event

```json
{
  "client_id": "018f9c8a-...-e7",
  "ts": 1735689600000,
  "tz_offset": 0,
  "updated_at": 1735689605000,
  "grams": 40.0,
  "duration_min": 180.0,
  "gi": 52.0,
  "k": 2.0,
  "theta": 30.0,
  "custom_curve": null,
  "note": "oatmeal"
}
```

- `client_id` — a phone-minted, stable identity; the idempotency key.
- `ts` — the grid-snapped event time; bucket 0 of the appearance curve.
- `grams` — carbohydrate mass ingested.
- `duration_min` — the appearance curve's active span, in minutes.
- `gi` — glycaemic index (`0..=100`); the phone derives the appearance gamma
  from it. A parametric meal therefore arrives with `gi` beside the `k`/`theta`
  already resolved from it; the server stores and re-serves the value rather
  than re-deriving a shape the event already carries.
- `k`, `theta` — shape and scale of the parametric appearance curve.
- `custom_curve` — a resolved appearance curve as `[f64]` on the 5-minute grid,
  carried by a mixed or builder meal (whose `gi`/`k`/`theta` are then `null`);
  `null` on a parametric meal, the case shown above.
- `note` — optional free text.

### Dose event

```json
{
  "client_id": "018f9c8a-...-a1",
  "ts": 1735689600000,
  "tz_offset": 0,
  "updated_at": 1735689605000,
  "kind": "bolus",
  "units": 4.0,
  "duration_min": 300.0,
  "k": 2.0,
  "theta": 40.0,
  "ka_per_hour": null,
  "ke_per_hour": null,
  "custom_curve": null,
  "note": null
}
```

- `kind` — `bolus` (a gamma action curve) or `basal` (a Bateman action curve).
- `units` — insulin units delivered.
- `duration_min` — the action curve's active span, in minutes.
- `k`, `theta` — gamma action-curve parameters of a `bolus`.
- `ka_per_hour`, `ke_per_hour` — Bateman absorption and elimination rates of a
  discrete `basal`; `null` on a `bolus`, as above.
- `custom_curve` — a resolved action curve as `[f64]` on the 5-minute grid,
  `null` when the dose is parametric.
- `client_id`, `ts`, `note` — as for a [meal event](#meal-event).

### Basal schedule

```json
{
  "schedule_id": "default",
  "active": true,
  "slots": [
    {
      "client_id": "018f9c8a-...-b2",
      "label": "overnight",
      "time_of_day_min": 0,
      "dose_u": 0.8,
      "duration_min": 480.0,
      "ka_per_hour": 0.9,
      "ke_per_hour": 0.5,
      "tz_offset": 0,
      "updated_at": 1735689605000
    }
  ]
}
```

- `schedule_id` — the template's identity.
- `active` — `true` for the live schedule.
- `slots` — the daily-repeating dose slots. Each slot carries its own
  `client_id`, a `label`, `time_of_day_min` (minutes past local midnight), a
  `dose_u`, a `duration_min`, Bateman `ka_per_hour`/`ke_per_hour`, a `tz_offset`,
  and an `updated_at`. The TUI tiles the slots across the day for display.

### Prediction

```json
{
  "made_at": 1735689600000,
  "model_id": "lstm-v3",
  "updated_at": 1735689605000,
  "horizon_steps": 24,
  "line": [112.0, 118.0, 123.0, "…"],
  "fan": [[…], […], […], […], […], […], […]],
  "circadian": {
    "probs": [0.0, 0.0, 0.1, 0.3, "…"],
    "predicted_hour": 7.5,
    "resultant_r": 0.8,
    "n_bins": 12,
    "bin_hours": 2.0
  }
}
```

- `made_at` — the phone's cycle timestamp for the forecast, stored verbatim;
  with `model_id` it forms the idempotency key.
- `line` — the predicted median series, length `horizon_steps`, in mg/dL.
- `fan` — a `7 × horizon_steps` matrix, one row per quantile level in the exact
  ascending order `[0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95]`. Row index 3 (the
  `0.5` level) equals `line`.
- `circadian` — the model's circadian belief, or `null` when the model has no
  circadian head. `probs` is a distribution over `n_bins` time-of-day bins each
  `bin_hours` wide; `predicted_hour` is the predicted hour-of-day and
  `resultant_r` the concentration of that belief (`0..=1`).

### Note

```json
{ "id": 7, "client_id": "018f9c8a-...-c3", "ts": 1735689600000, "tz_offset": 0, "text": "felt low before lunch", "updated_at": 1735689605000, "created_at": 1735689601000 }
```

A note keeps its wall-clock `ts` and is editable; `client_id` is its identity
and `updated_at` orders edits. `id` is the server's internal row id and
`created_at` its server-side insertion stamp; both are read-only and survive an
edit unchanged.

### Photo (metadata)

```json
{
  "id": 3,
  "ts": 1735689600000,
  "path": "photos/9f86d0…d3.jpg",
  "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "width": 1024,
  "height": 768,
  "bytes": 184320,
  "created_at": 1735689601000
}
```

`path` is relative to `storage.data_dir`. The binary is fetched via
`GET /v1/photos/{id}`.

### Alert

```json
{
  "id": 11,
  "client_id": "018f9c8a-...-d4",
  "ts": 1735689600000,
  "kind": "low",
  "payload": { "bg": 58 },
  "origin_token": 2,
  "created_at": 1735689601000
}
```

`client_id` is the alert's phone-minted identity; an alert is immutable.
`payload` is opaque JSON, echoed verbatim. `origin_token` is the id of the token
that raised the alert (or `null`); the origin token's own sessions are excluded
from the live-stream fan-out. `id` is the server's internal row id and
`created_at` its server-side insertion stamp; both are read-only.

### Model

The `id` is the artifact's full filename (extension included), so format
variants of one model — `lstm-v3.pt`, `lstm-v3.onnx` — register as distinct
entries. `name` is the filename stem; `ext` is the lowercased extension (empty
when the file has none).

```json
{
  "id": "lstm-v3.pt",
  "name": "lstm-v3",
  "ext": "pt",
  "path": "models/lstm-v3.pt",
  "meta": { "arch": "lstm", "params": 1200000, "trained": "2026-06-01" },
  "sha256": "…",
  "bytes": 4823104,
  "discovered_at": 1735689000000
}
```

`meta` is **opaque JSON**: stored, served, and rendered verbatim, never
interpreted. `path` is relative to `storage.data_dir`.

### Stats

```json
{
  "window": "7d",
  "updated_at": 1735689605000,
  "tir": 0.72,
  "time_below": 0.04,
  "time_above": 0.24,
  "mean_bg": 148.3,
  "gmi": 6.9,
  "cv": 34.1,
  "sd": 50.6,
  "hypo_events": { "count": 2, "duration_ms": 3600000 },
  "hyper_events": { "count": 5, "duration_ms": 14400000 },
  "mean_daily_carbs": 0.0,
  "tdd": 0.0,
  "bolus_basal_ratio": 0.0,
  "mean_hr": 0.0,
  "bg_hr_corr": 0.0,
  "n_samples": 264
}
```

A stats block is computed on the phone and stored by the server verbatim.
`window` is one of `7d`, `30d`, `90d`. `updated_at` is the phone's millisecond
clock when the block was computed. The three time-fraction fields (`tir`,
`time_below`, `time_above`) are fractions in `0..=1` about the target range
configured on the phone (default 70–180 mg/dL); the range itself is not carried
on the wire, so a served fraction cannot be reinterpreted against any other
pair. `gmi` and `cv` are percentages; `n_samples` is the number of grid samples
that contributed BG to the window.

`hypo_events` and `hyper_events` carry the producer's notion of an excursion: a
maximal run of at least two consecutive BG-bearing samples past the configured
target edge, banded on the same edges as the time fractions. `count` is the number of such
runs; `duration_ms` is their total, each run measured as its last timestamp
minus its first — one grid step short of the span the run covers. A dropout of
up to 30 minutes is bridged into a single episode; a longer one splits the run,
and either fragment shorter than two samples is discarded.

Five fields are carried by the schema but not populated by the current phone
build, and so arrive as `0.0`. `mean_hr` and `bg_hr_corr` — nominally a mean
heart rate and a Pearson correlation of BG and HR in `-1..=1` — are not computed
on the phone at all. `mean_daily_carbs`, `tdd` (nominally units/day) and
`bolus_basal_ratio` are reductions over sample columns that no longer exist:
carbohydrate and insulin totals travel as [meal](#meal-event) and
[dose](#dose-event) curve events, so the sums they reduce are empty.

The field set of a pushed block is the phone's, not the server's. The server
peels off only `window` and `updated_at` — to key and guard the upsert — and
persists the rest of the body verbatim, then echoes that body verbatim, so a
block pushed with a field missing reads back with that field missing. Only the
block the server synthesises for a window no phone has ever pushed is
guaranteed complete: it carries the whole set above, all-zero — every numeric
field `0`, both event counts and durations `0`, and `updated_at: 0`. A zero
`updated_at` is therefore the marker of a never-pushed window, distinct from a
pushed block whose metrics happen to be zero.

---

## Write endpoints (require `rw`)

### POST /v1/ingest

Atomic five-minute bundle of the scalar series. Physiologic fields are
optional; those present overwrite the row at `ts` in place, those absent leave
the existing values untouched. Writing the row broadcasts a `sample` event to
every live-stream session except those of the origin token.

Request:

```json
{
  "ts": 1735689600000,
  "tz_offset": 0,
  "updated_at": 1735689605000,
  "bg": 112.0,
  "hr": 68.0, "steps": 30.0, "sleep": 0.0, "exercise": 0.0, "mood": 4
}
```

`ts`, `tz_offset`, and `updated_at` are required; the six physiologic scalars
are optional. `updated_at` is the phone's clock and is stored verbatim; the row
is upserted on `ts`, and a partial redelivery re-applies when its `updated_at`
is equal or newer, and is ignored when older. Predictions and notes are written
through their own endpoints ([`PUT /v1/predictions`](#put-v1predictions),
[`POST /v1/notes`](#post-v1notes)), not embedded here.

Response `200`:

```json
{ "ok": true, "ts": 1735689600000 }
```

### PUT /v1/meals

Batch upsert of [meal events](#meal-event). The body is a JSON array — a
single-element array for a freshly logged meal, an N-element array when
re-mirroring history.

Request:

```json
[
  {
    "client_id": "018f9c8a-...-e7",
    "ts": 1735689600000,
    "tz_offset": 0,
    "updated_at": 1735689605000,
    "grams": 40.0,
    "duration_min": 180.0,
    "gi": 52.0,
    "k": 2.0,
    "theta": 30.0,
    "note": "oatmeal"
  }
]
```

`client_id`, `ts`, `tz_offset`, `updated_at`, `grams`, and `duration_min` are
required; the curve parameters, `custom_curve`, and `note` are optional, and an
absent one may be omitted or sent as `null`. A parametric meal carries
`gi`/`k`/`theta`; a mixed or builder meal carries its resolved appearance curve
in `custom_curve` instead. Idempotent by `client_id`: a redelivery is a no-op,
a newer `updated_at` replaces the row in place. Fans out a `meal` event to
every session except the origin.

Response `200` — the stored `client_id`s, in input order:

```json
{ "ok": true, "ids": ["018f9c8a-...-e7"] }
```

### PUT /v1/doses

Batch upsert of [dose events](#dose-event). Same batch convention as
[meals](#put-v1meals).

Request:

```json
[
  {
    "client_id": "018f9c8a-...-a1",
    "ts": 1735689600000,
    "tz_offset": 0,
    "updated_at": 1735689605000,
    "kind": "bolus",
    "units": 4.0,
    "duration_min": 300.0,
    "k": 2.0,
    "theta": 40.0
  }
]
```

`client_id`, `ts`, `tz_offset`, `updated_at`, `kind`, `units`, and
`duration_min` are required; the curve parameters, `custom_curve`, and `note`
are optional, and an absent one may be omitted or sent as `null`. A `bolus`
carries gamma `k`/`theta`; a discrete `basal` carries Bateman
`ka_per_hour`/`ke_per_hour`; either may instead carry a resolved
`custom_curve`. Idempotent by `client_id`: a redelivery is a no-op, a newer
`updated_at` replaces in place. Fans out a `dose` event to every session except
the origin.

Response `200` — the stored `client_id`s, in input order:

```json
{ "ok": true, "ids": ["018f9c8a-...-a1"] }
```

### PUT /v1/basal-schedule

Full-replace the daily-repeating basal template. The body is one
[schedule](#basal-schedule) with its slots; the server replaces the named
schedule's slots wholesale.

Request:

```json
{
  "schedule_id": "default",
  "active": true,
  "slots": [
    {
      "client_id": "018f9c8a-...-b2",
      "label": "overnight",
      "time_of_day_min": 0,
      "dose_u": 0.8,
      "duration_min": 480.0,
      "ka_per_hour": 0.9,
      "ke_per_hour": 0.5,
      "tz_offset": 0,
      "updated_at": 1735689605000
    }
  ]
}
```

`schedule_id`, `active`, and `slots` are required, as is every field of every
slot — a slot has no optional member. Each slot is idempotent by its
`client_id`; a newer `updated_at` replaces a slot in place. Fans out a
`basal_schedule` event to every session except the origin.

Response `200` — the stored slot `client_id`s:

```json
{ "ok": true, "ids": ["018f9c8a-...-b2"] }
```

### PUT /v1/predictions

Upsert one or more predictions. The body is a JSON array of
[prediction](#prediction) write objects.

Request:

```json
[
  {
    "made_at": 1735689600000,
    "model_id": "lstm-v3",
    "updated_at": 1735689605000,
    "horizon_steps": 24,
    "line": [112.0],
    "fan": [[…], […], […], […], […], […], […]],
    "circadian": { "probs": [0.0, 0.1], "predicted_hour": 7.5, "resultant_r": 0.8, "n_bins": 12, "bin_hours": 2.0 }
  }
]
```

`made_at`, `model_id`, `updated_at`, `horizon_steps`, `line`, and `fan` are
required; `circadian` is `null` when the model has no circadian head. `made_at`
is the phone's cycle timestamp, stored verbatim. Keyed idempotent on `(made_at,
model_id)` and guarded on the phone clock, as meals, doses, basal slots, and
statistics blocks are: a re-run carrying a newer `updated_at` overwrites the
forecast in place, while a redelivery with an equal or older `updated_at` is a
no-op. Fans out a `prediction` event to every session except the origin.

Response `200` — the canonical row ids, in input order:

```json
{ "ok": true, "ids": [42] }
```

### PUT /v1/stats

Push a computed [statistics block](#stats) for one window. The phone computes
the block; the server stores it verbatim and serves it back through
[`GET /v1/stats`](#get-v1stats), never re-deriving it.

Request:

```json
{
  "window": "7d",
  "updated_at": 1735689605000,
  "tir": 0.72,
  "time_below": 0.04,
  "time_above": 0.24,
  "mean_bg": 148.3,
  "gmi": 6.9,
  "cv": 34.1,
  "sd": 50.6,
  "hypo_events": { "count": 2, "duration_ms": 3600000 },
  "hyper_events": { "count": 5, "duration_ms": 14400000 },
  "mean_daily_carbs": 0.0,
  "tdd": 0.0,
  "bolus_basal_ratio": 0.0,
  "mean_hr": 0.0,
  "bg_hr_corr": 0.0,
  "n_samples": 264
}
```

`window` and `updated_at` are required. `window` must be one of `7d`, `30d`, or
`90d`; any other value is rejected with `400` and nothing is stored. Keyed
idempotent on `window`: a newer `updated_at` replaces the stored block in place.
Fans out a `stats` event to every session except the origin.

Response `200`:

```json
{ "ok": true, "window": "7d" }
```

### POST /v1/notes

Request:

```json
{ "client_id": "018f9c8a-...-c3", "ts": 1735689600000, "tz_offset": 0, "text": "note body", "updated_at": 1735689605000 }
```

`client_id`, `ts`, `text`, and `updated_at` are required. `tz_offset` is
optional only here — every other write that carries one requires it — and it
must be *omitted* rather than nulled: an absent key defaults to `0`, an
explicit `null` is rejected. A note keeps its wall-clock `ts` and is editable —
keyed by `client_id`, a newer `updated_at` replaces its text in place. Fans out
a `note` event to every session except the origin.

Response `200`:

```json
{ "ok": true, "id": "018f9c8a-...-c3" }
```

### POST /v1/photos

`multipart/form-data` with two parts: a text field `ts`, and an image file in a
field named `image`, `file`, or `photo`. The file extension determines the
stored format; the binary is written under `<data_dir>/photos/<sha256>.<ext>`.
Broadcasts a `photo` event. Dimensions are `0` here and filled in by the
importer/TUI when the image is decoded.

The multipart body is capped at 16 MiB. An upload that exceeds it is rejected
with `413 Payload Too Large` and nothing is stored; the client must downscale
or recompress before retrying.

Response `200`:

```json
{ "ok": true, "id": 3, "sha256": "9f86d081…" }
```

### POST /v1/alerts

Raise an application alert. The caller's token is recorded as the alert origin,
and the hub broadcasts the alert to every connected session **except** those of
the origin token.

Request:

```json
{ "client_id": "018f9c8a-...-d4", "ts": 1735689600000, "kind": "low", "payload": { "bg": 58 } }
```

`client_id`, `ts`, and `kind` are required; `payload` is optional and opaque
(defaults to `null`). An alert is immutable — a redelivery of the same
`client_id` is ignored.

Response `200`:

```json
{ "ok": true, "id": "018f9c8a-...-d4" }
```

---

## Read endpoints (`ro` or `rw`)

### GET /v1/series

Fetch wide sample rows over a time range, paginated forward by timestamp.

Query parameters:

| Param | Type | Meaning |
| --- | --- | --- |
| `fields` | csv | Comma-separated series allowlist (e.g. `bg,hr,steps`); default all. An unknown name is `400`. |
| `from` | int | Inclusive lower `ts` bound. |
| `to` | int | Inclusive upper `ts` bound. |
| `limit` | int | Maximum rows to return; default `10000`. |
| `cursor` | int | Continuation cursor; rows with `ts <= cursor` are excluded. |

Rows are returned in ascending `ts` order and always carry the full wide schema
(every series column present, `null` for gaps) regardless of `fields`.

Response `200`:

```json
{
  "rows": [
    { "ts": 1735689600000, "tz_offset": 0, "bg": 112.0, "hr": 68.0, "steps": null, "sleep": null, "exercise": null, "mood": null, "updated_at": 1735689605000 }
  ],
  "next_cursor": 1735689600000
}
```

`next_cursor` is the `ts` of the last row returned. It is `null` when the page
is empty, and also when a page falls short of an explicitly requested `limit` —
a short page is the last one, so no further request is owed. When `limit` is
omitted the server applies its own default bound and `next_cursor` is the last
row's `ts`. To page, pass it back as `cursor` until a request returns no rows.

### GET /v1/meals

Fetch [meal events](#meal-event) over a time range. Query: `from`, `to`
(inclusive bounds on the grid `ts`) and `limit` (maximum events returned).
Events are returned in ascending `ts` order.

Response `200`:

```json
{
  "meals": [
    { "client_id": "018f9c8a-...-e7", "ts": 1735689600000, "tz_offset": 0, "updated_at": 1735689605000, "grams": 40.0, "duration_min": 180.0, "gi": 52.0, "k": 2.0, "theta": 30.0, "custom_curve": null, "note": "oatmeal" }
  ]
}
```

Every field is present; an absent optional is explicit `null`. The server's
internal `id` and stamps are not returned.

This route is **not paginated** and returns no cursor. `limit` is a plain cap on
the number of rows, not a page size: a caller that supplies it and receives
exactly that many rows has no way to ask for the rest, so omit it to fetch the
whole range. Unlike `samples`, whose `ts` is a primary key, several meals may
share one grid `ts` — two foods logged in the same five-minute slot — so a
`ts`-keyed cursor could only either repeat or skip the events at a page
boundary, and none is offered.

### GET /v1/doses

Fetch [dose events](#dose-event) over a time range. Query: `from`, `to`
(inclusive bounds on the grid `ts`) and `limit` (maximum events returned).
Events are returned in ascending `ts` order.

Response `200`:

```json
{
  "doses": [
    { "client_id": "018f9c8a-...-a1", "ts": 1735689600000, "tz_offset": 0, "updated_at": 1735689605000, "kind": "bolus", "units": 4.0, "duration_min": 300.0, "k": 2.0, "theta": 40.0, "ka_per_hour": null, "ke_per_hour": null, "custom_curve": null, "note": null }
  ]
}
```

`limit` behaves exactly as for [`GET /v1/meals`](#get-v1meals), and this route
is likewise not paginated.

### GET /v1/basal-schedule

The active basal template, returned as a bare
[Basal schedule](#basal-schedule) object — no wrapper key.

Response `200`:

```json
{
  "schedule_id": "default",
  "active": true,
  "slots": [
    { "client_id": "018f9c8a-...-b2", "label": "overnight", "time_of_day_min": 0, "dose_u": 0.8, "duration_min": 480.0, "ka_per_hour": 0.9, "ke_per_hour": 0.5, "tz_offset": 0, "updated_at": 1735689605000 }
  ]
}
```

The response body is one [Basal schedule](#basal-schedule) object, or the
literal `null` when no schedule is active. The `basal_schedule` stream frame
carries the same fields inline alongside its `type` discriminant.

### GET /v1/predictions

Query: `from`, `to` (inclusive bounds on `made_at`). Newest first.

Response `200`:

```json
{ "predictions": [ { "made_at": 1735689600000, "model_id": "lstm-v3", "…": "…" } ] }
```

### GET /v1/predictions/latest

The single most recent prediction, or `null`.

Response `200`:

```json
{ "prediction": { "made_at": 1735689600000, "…": "…" } }
```

### GET /v1/notes

Query: `from`, `to` (inclusive bounds on `ts`). Newest first.

Response `200`:

```json
{ "notes": [ { "id": 7, "client_id": "018f9c8a-...-c3", "ts": 1735689600000, "tz_offset": 0, "text": "…", "updated_at": 1735689605000, "created_at": 1735689601000 } ] }
```

### GET /v1/alerts

Query: `from`, `to`. Newest first.

Response `200`:

```json
{ "alerts": [ { "id": 11, "client_id": "018f9c8a-...-d4", "ts": 1735689600000, "kind": "low", "payload": { "bg": 58 }, "origin_token": 2, "created_at": 1735689601000 } ] }
```

### GET /v1/photos

Photo metadata over a range. Query: `from`, `to`. Newest first. Returns the
[Photo](#photo-metadata) objects, not the binaries.

```json
{ "photos": [ { "id": 3, "ts": 1735689600000, "path": "photos/…jpg", "sha256": "…", "width": 1024, "height": 768, "bytes": 184320, "created_at": 1735689601000 } ] }
```

### GET /v1/photos/{id}

The image binary. Responds with the appropriate `Content-Type`
(`image/jpeg`, `image/png`, or `image/webp`). `404` if the id is unknown.

### GET /v1/models

The discovered model registry. Any file dropped into `models/` (of any
extension) is registered; `.json` meta sidecars and dotfiles are not. The
registry mirrors the directory — a file removed from `models/` is dropped from
the registry on the next scan, so every listed id remains downloadable.

```json
{ "models": [
  { "id": "lstm-v3.pt", "name": "lstm-v3", "ext": "pt", "path": "models/lstm-v3.pt", "meta": { "…": "…" }, "sha256": "…", "bytes": 4823104, "discovered_at": 1735689000000 },
  { "id": "lstm-v3.onnx", "name": "lstm-v3", "ext": "onnx", "path": "models/lstm-v3.onnx", "meta": { "…": "…" }, "sha256": "…", "bytes": 3910016, "discovered_at": 1735689100000 }
] }
```

### GET /v1/models/{id}/meta

The opaque `meta` JSON for one model, returned verbatim (not wrapped). `404` if
the id is unknown.

```json
{ "arch": "lstm", "params": 1200000, "trained": "2026-06-01" }
```

### GET /v1/models/{id}/download

Streams the model artifact as `application/octet-stream`, regardless of its
format. Response headers carry `Content-Length`, `X-SHA256` (the artifact's
content hash) for integrity verification, and `Content-Disposition` with the
artifact's real filename and extension. `404` if the id is unknown.

### GET /v1/stats

Query: `window` = `7d` | `30d` | `90d` (default `7d`). An unrecognized window
is `400`.

Returns the most recent block the phone pushed for that window via
[`PUT /v1/stats`](#put-v1stats), verbatim — the server never computes or
re-derives statistics. When no block has been pushed for the window, an
all-zero block is returned, with `updated_at: 0` marking it as never pushed.

Response `200`:

```json
{ "stats": { "window": "7d", "updated_at": 1735689605000, "tir": 0.72, "…": "…" } }
```

See the [Stats](#stats) schema for the full field set.

### GET /v1/health

Liveness probe, plus the server's clock, its store identity, and a snapshot of
the host. Requires a valid bearer token, like every other endpoint.

```json
{
  "status": "ok",
  "ws_clients": 3,
  "time_ms": 1735689605000,
  "store_epoch": "1f0c4a8e5b3d47a29c6e0f81b7d3a5c4",
  "system": {
    "mem_total_bytes": 493355008,
    "mem_used_bytes": 214958080,
    "mem_available_bytes": 268435456,
    "cpus": 4,
    "uptime_secs": 918273,
    "load_avg": [0.31, 0.24, 0.19]
  }
}
```

- `status` — the constant `ok`.
- `ws_clients` — the number of connected WebSocket clients. The TUI's permanent
  in-process subscriber is discounted, so an appliance with no client attached
  reports `0`.
- `time_ms` — the server's wall clock at the moment of the response, in epoch
  milliseconds.
- `store_epoch` — the opaque store-identity marker; see below.
- `system` — a host snapshot: `mem_total_bytes`, `mem_used_bytes`, and
  `mem_available_bytes` in bytes; `cpus`, the host's available parallelism (`0`
  when it cannot be determined); `uptime_secs`, the host uptime in seconds; and
  `load_avg`, a 3-element array of the 1-, 5-, and 15-minute load averages.

#### The store epoch and the re-mirror handshake

`store_epoch` is an opaque store-identity string — a random hex marker, not a
timestamp — and is never to be parsed, ordered, or compared for anything but
equality. It is minted once when the store's schema is first created and
re-minted whenever the store is torn down and recreated, an operation the
operator reaches from the TUI's Developer pane.

The field is `null` whenever the server cannot report the marker: before the
first mint, but equally when the read of it fails. A `null` therefore means
*unknown*, not *changed*, and is never grounds for a replay; only a non-null
value differing from the one the client holds means the store was replaced.

A client persists the last `store_epoch` it observed alongside its mirrored
state and compares the served value against that on every poll:

- **Equal to the persisted value** — the server still holds the history the
  client mirrored, and ordinary incremental catch-up through the range reads
  suffices.
- **A non-null value differing from the persisted one, or the first non-null
  value the client has ever seen** — the server is a different store and
  retains nothing of the client's history. The client must replay whatever
  subset of its authoritative history it holds through the write endpoints —
  scalar samples via [`POST /v1/ingest`](#post-v1ingest), then meals, doses,
  the active basal schedule, and the latest block for each statistics window —
  and only then persist the new epoch.
- **`null`** — the marker is unknown; the client leaves its persisted value
  untouched and replays nothing.

Every write is idempotent on its phone-minted key, so a replay is safe to
interrupt and resume, and cannot duplicate a record.

A teardown discards every record the store holds, not only those the replay
above restores: the notes, predictions, alerts, and photo binaries written
through [`POST /v1/notes`](#post-v1notes),
[`PUT /v1/predictions`](#put-v1predictions),
[`POST /v1/alerts`](#post-v1alerts), and [`POST /v1/photos`](#post-v1photos)
are gone with the rest, and exist on the new store only once re-posted through
those same endpoints. Model artifacts are the sole exception — they live on
disk, survive the wipe, and are re-registered by the next scan.

---

## WebSocket

### GET /v1/stream?token=&lt;secret&gt;

A server-to-client push stream. Authentication is the `token` query parameter;
an invalid or revoked token is rejected with `401` before the upgrade. After the
upgrade the server sends events as they occur; inbound frames from the client
are ignored (a Close frame ends the stream). Either `rw` or `ro` tokens may
subscribe. Sessions — and thus the record of a connected viewer — persist across
reconnects.

The server also closes the socket on its own initiative. Authentication is
resolved once, at the upgrade, so an upgraded stream re-checks every 30 seconds
that its token is still live and closes when the token has been revoked — or
when the check cannot be answered, the store being the arbiter of liveness. The
close carries no error body; the client sees an ordinary socket closure and
should reconnect with backoff. A reconnect on a revoked token is refused with
`401` before the upgrade, and the client must obtain a freshly minted token
before the stream is available again. The other server-initiated close follows
a `lagged` frame, described below.

Each event is a JSON object with a `"type"` discriminant and the event's fields
inlined alongside it. The nine record types — `sample`, `prediction`, `note`,
`photo`, `alert`, `meal`, `dose`, `basal_schedule`, and `stats` — carry,
respectively, the [Sample row](#sample-row), [Prediction](#prediction),
[Note](#note), [Photo](#photo-metadata), [Alert](#alert),
[Meal event](#meal-event), [Dose event](#dose-event),
[Basal schedule](#basal-schedule), and [Stats](#stats) schemas.

`stats` is the sole exception to the inlining: its frame carries `window` and
`updated_at` at the top level and nests the pushed statistics block whole under
a `json` key. The block under `json` is the phone's body echoed verbatim, so it
repeats its own `window` and `updated_at`. The REST shape differs —
[`GET /v1/stats`](#get-v1stats) returns the metrics flat under a `stats` key —
so a client must decode the two separately.

A tenth type, `lagged`, carries no record and has no REST counterpart. The
server emits it when the broadcast buffer overran and events were dropped
before this session could read them; `missed` is how many. It is the last frame
of that socket — the server closes immediately after sending it. The dropped
events are gone from the stream, so a client that receives one holds an
incomplete mirror and must resynchronise through the range reads from its own
high-water marks rather than trust its incremental cursor.

```json
{ "type": "sample",         "ts": 1735689600000, "tz_offset": 0, "bg": 112.0, "hr": 68.0, "steps": null, "sleep": null, "exercise": null, "mood": null, "updated_at": 1735689605000 }
```

```json
{ "type": "prediction",     "made_at": 1735689600000, "model_id": "lstm-v3", "updated_at": 1735689605000, "horizon_steps": 24, "line": [112.0], "fan": [[…]], "circadian": { "probs": [0.0, 0.1], "predicted_hour": 7.5, "resultant_r": 0.8, "n_bins": 12, "bin_hours": 2.0 } }
```

```json
{ "type": "note",           "id": 7, "client_id": "018f9c8a-...-c3", "ts": 1735689600000, "tz_offset": 0, "text": "felt low before lunch", "updated_at": 1735689605000, "created_at": 1735689601000 }
```

```json
{ "type": "photo",          "id": 3, "ts": 1735689600000, "path": "photos/…jpg", "sha256": "…", "width": 1024, "height": 768, "bytes": 184320, "created_at": 1735689601000 }
```

```json
{ "type": "alert",          "id": 11, "client_id": "018f9c8a-...-d4", "ts": 1735689600000, "kind": "low", "payload": { "bg": 58 }, "origin_token": 2, "created_at": 1735689601000 }
```

```json
{ "type": "meal",           "client_id": "018f9c8a-...-e7", "ts": 1735689600000, "tz_offset": 0, "updated_at": 1735689605000, "grams": 40.0, "duration_min": 180.0, "gi": 52.0, "k": 2.0, "theta": 30.0, "custom_curve": null, "note": "oatmeal" }
```

```json
{ "type": "dose",           "client_id": "018f9c8a-...-a1", "ts": 1735689600000, "tz_offset": 0, "updated_at": 1735689605000, "kind": "bolus", "units": 4.0, "duration_min": 300.0, "k": 2.0, "theta": 40.0, "ka_per_hour": null, "ke_per_hour": null, "custom_curve": null, "note": null }
```

```json
{ "type": "basal_schedule", "schedule_id": "default", "active": true, "slots": [ { "client_id": "018f9c8a-...-b2", "label": "overnight", "time_of_day_min": 0, "dose_u": 0.8, "duration_min": 480.0, "ka_per_hour": 0.9, "ke_per_hour": 0.5, "tz_offset": 0, "updated_at": 1735689605000 } ] }
```

```json
{ "type": "stats",          "window": "7d", "updated_at": 1735689605000, "json": { "window": "7d", "updated_at": 1735689605000, "tir": 0.72, "…": "…" } }
```

```json
{ "type": "lagged",         "missed": 128 }
```

**Every write fans out except the origin.** A write is delivered to every
connected session **except** those belonging to the token that authored it, so a
client never receives an echo of its own write. Because the phone is the sole
read-write author, in practice it never receives a live `sample`, `meal`,
`dose`, `basal_schedule`, or `stats` event; it hydrates any history missed while
offline through REST catch-up (`GET /v1/series`, `GET /v1/meals`,
`GET /v1/doses`), not the stream.
