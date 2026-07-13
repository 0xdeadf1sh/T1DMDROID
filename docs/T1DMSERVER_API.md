# T1DMSERVER HTTP & WebSocket API

This is the complete wire contract for the T1DMSERVER appliance — the
specification a companion client (for example the Android app) implements
against.

All routes are prefixed with `/v1` and exchange `application/json` unless noted
otherwise. The server binds `server.bind:server.port` (default `0.0.0.0:8443`).

## Contents

- [Conventions](#conventions)
- [Authentication](#authentication)
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
- [WebSocket](#websocket)
  - [GET /v1/stream?token=&lt;secret&gt;](#get-v1streamtokensecret)

## Conventions

- **Timestamps** are integers, milliseconds since the Unix epoch (UTC).
- **The 5-minute grid.** Sample, meal, and dose timestamps sit on a fixed
  five-minute grid: `ts % 300000 == 0`. A sample timestamp off the grid is
  rejected; a meal or dose timestamp is snapped to the nearest grid point by the
  client before it is sent. Note and alert timestamps are wall-clock instants and
  are not snapped.
- **`tz_offset`** is the client's UTC offset in minutes at the event time
  (e.g. `-300` for UTC−5), carried alongside the timestamp for local-time
  rendering.
- **Storage units are fixed:** blood glucose in mg/dL; heart rate in bpm; steps
  as a count; mood as a small integer; `sleep` and `exercise` as plain scalar
  magnitudes. Carbohydrate, bolus, and basal quantities are not sample columns —
  each is carried as its own curve event (see [Meal event](#meal-event) and
  [Dose event](#dose-event)). The mg/dL ↔ mmol/L toggle is display-only and never
  affects the wire format.
- **The client owns event identity.** Meals, doses, basal-schedule slots, notes,
  and alerts are keyed by a client-assigned `client_id` — an opaque string,
  unique per record. The server stores it as the idempotency key: a redelivery of
  the same `client_id` is a no-op, so a lost acknowledgement is safely retried.
- **`updated_at` is client-authored.** Every mutable row carries an `updated_at`,
  the client's wall-clock at write time. The server stores and returns it
  verbatim and never re-stamps it. It is also the idempotency tiebreak: a write
  whose `updated_at` is newer replaces the stored row in place, while an
  equal-or-older one leaves it untouched.
- **Server receipt clocks stay internal.** The server records when it received a
  row, but this internal timestamp is never serialized — it appears on no read or
  stream frame. (The note, photo, alert, and model rows additionally carry a
  server-assigned `created_at`/`discovered_at` marking when the server first
  stored or discovered them.)
- **Curves are grid-sampled vectors.** A `custom_curve` is a JSON array of `f64`,
  one value per five-minute bucket on the same 300000 ms grid: the resolved
  carbohydrate-appearance curve for a meal, or the resolved insulin-action curve
  for a dose. When present it is authoritative and is echoed byte-for-byte; when
  absent, the curve is reconstructed from the row's parametric fields.
- **Gaps are explicit, with a direction asymmetry.** In a read response a missing
  value is `null`, never omitted. On a client write the two are equivalent: an
  omitted optional field and an explicit `null` both mean "absent", so a client
  may omit any optional field it has no value for.

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

The server's Sessions pane renders a login QR encoding a single JSON object:

```json
{ "type": "t1dm-login", "token": "<secret>", "addr": "100.68.1.119", "port": 8443 }
```

- `type` — the constant tag `t1dm-login`.
- `token` — the bearer secret (used for both `Authorization: Bearer` and the WS `?token=`).
- `addr` / `port` — the operator-advertised endpoint; the client composes its base URL as
  `http://addr:port` (transport TLS is moot on the tailnet). Minting a fresh `rw` token revokes the
  prior one, so an older QR's `token` stops working.

## Errors

Errors return the mapped HTTP status with a JSON body:

```json
{ "error": "bad request: unknown series \"foo\"" }
```

| Status | Condition |
| --- | --- |
| 400 Bad Request | Malformed body or query — bad field name, unparseable window, missing multipart part |
| 401 Unauthorized | Missing, unknown, or revoked bearer token |
| 403 Forbidden | A valid `ro` token on a write endpoint |
| 404 Not Found | Unknown resource id (photo, model) |
| 500 Internal Server Error | Store or filesystem failure |

## Object schemas

These canonical shapes are referenced throughout. In read responses every field
is present; optional physiologic fields are `null` when absent.

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
floating point except `mood`, which is an integer. Carbohydrates, bolus, and
basal are no longer sample columns — each is carried as its own
[meal](#meal-event) or [dose](#dose-event) event. `updated_at` is the client's
wall-clock at the moment it last wrote the row, stored verbatim.

### Meal event

A carbohydrate intake, carried as its resolved appearance (Ra) curve or the
parameters to reconstruct one. Keyed by the client-assigned `client_id`.

```json
{
  "client_id": "018f6b2e-3c7a-7e11-9a44-2b6f0e5d9c31",
  "ts": 1735689600000,
  "tz_offset": 0,
  "updated_at": 1735689605000,
  "grams": 40.0,
  "duration_min": 180.0,
  "gi": 52.0,
  "k": 2.0,
  "theta": 24.0,
  "custom_curve": null,
  "note": "lunch"
}
```

- `client_id` — client-assigned opaque id; the idempotency key.
- `ts` — grid-snapped event time (`ts % 300000 == 0`).
- `grams` — carbohydrate grams.
- `duration_min` — modelled appearance duration in minutes.
- `gi` — glycaemic index (`0..=100`), or `null`.
- `k`, `theta` — shape and scale of the parametric appearance curve, or `null`
  when a `custom_curve` is supplied.
- `custom_curve` — the resolved appearance curve as an `f64` array on the
  five-minute grid, or `null` for a parametric meal. When present it is
  authoritative.
- `note` — free text, or `null`.
- `updated_at` — client clock, verbatim.

### Dose event

An insulin dose, carried as its resolved action (PK) curve or the parameters to
reconstruct one. A `bolus` is modelled with the gamma parameters `k`/`theta`; a
`basal` with the Bateman rates `ka_per_hour`/`ke_per_hour`.

```json
{
  "client_id": "018f6b2e-40aa-7c02-8f19-7d3c1a9b4e88",
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

- `client_id` — client-assigned opaque id; the idempotency key.
- `ts` — grid-snapped event time (`ts % 300000 == 0`).
- `kind` — `bolus` or `basal`.
- `units` — insulin units.
- `duration_min` — modelled action duration in minutes.
- `k`, `theta` — gamma parameters of a bolus action curve, or `null`.
- `ka_per_hour`, `ke_per_hour` — Bateman absorption and elimination rates of a
  basal action curve, or `null`.
- `custom_curve` — the resolved action curve as an `f64` array on the five-minute
  grid, or `null` for a parametric dose. When present it is authoritative.
- `note` — free text, or `null`.
- `updated_at` — client clock, verbatim.

### Basal schedule

The active daily-repeating basal template — a set of slots that tile a 24-hour
day. Sent and returned whole; a `PUT` fully replaces the active schedule.

```json
{
  "schedule_id": "sched-2026-07",
  "active": true,
  "slots": [
    {
      "client_id": "018f6b2e-5100-7a33-b0c2-9e4f2d7a1b60",
      "label": "overnight",
      "time_of_day_min": 0,
      "dose_u": 0.8,
      "duration_min": 1440.0,
      "ka_per_hour": 0.5,
      "ke_per_hour": 0.7,
      "tz_offset": 0,
      "updated_at": 1735689605000
    }
  ]
}
```

- `schedule_id` — id of the schedule as a whole.
- `active` — whether this is the live schedule.
- `slots` — the per-time-of-day basal doses; each slot carries:
  - `client_id` — slot idempotency key.
  - `label` — human label.
  - `time_of_day_min` — minutes past local midnight at which the slot applies.
  - `dose_u` — units delivered by the slot.
  - `duration_min` — action duration in minutes.
  - `ka_per_hour`, `ke_per_hour` — Bateman rates of the slot's action curve.
  - `tz_offset`, `updated_at` — as elsewhere.

### Prediction

```json
{
  "made_at": 1735689600000,
  "model_id": "lstm-v3",
  "horizon_steps": 24,
  "line": [112.0, 118.0, 123.0, "…"],
  "fan": [[…], […], […], […], […], […], […]],
  "circadian": {
    "probs": [0.0, 0.0, 0.1, 0.3, "…"],
    "predicted_hour": 7.5,
    "resultant_r": 0.71,
    "n_bins": 12,
    "bin_hours": 2.0
  },
  "updated_at": 1735689605000
}
```

- `made_at` — the client's forecast-cycle timestamp, stored verbatim; together
  with `model_id` it is the idempotency key.
- `line` — the predicted median series, length `horizon_steps`, in mg/dL.
- `fan` — a `7 × horizon_steps` matrix, one row per quantile level in the exact
  ascending order `[0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95]`. Row index 3 (the
  `0.5` level) equals `line`.
- `circadian` — the model's time-of-day head, or `null` when the model has none.
  `probs` is the per-bin probability distribution; `predicted_hour` the resolved
  hour of day; `resultant_r` the circular concentration (confidence) in `0..=1`;
  `n_bins` the number of bins; `bin_hours` the hours each bin spans.
- `updated_at` — client clock, verbatim.

### Note

```json
{ "id": 7, "client_id": "018f6b2e-6200-7b44-a1d3-0f5e3c8b2a71", "ts": 1735689600000, "tz_offset": 0, "text": "felt low before lunch", "updated_at": 1735689605000, "created_at": 1735689601000 }
```

`client_id` is the client-assigned idempotency key; `updated_at` is the client's
wall-clock at the last edit, stored verbatim. `ts` is a wall-clock instant and is
not grid-snapped.

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
  "client_id": "018f6b2e-7300-7c55-b2e4-1a6f4d9c3b82",
  "ts": 1735689600000,
  "kind": "low",
  "payload": { "bg": 58 },
  "origin_token": 2,
  "created_at": 1735689601000
}
```

`client_id` is the client-assigned idempotency key. `payload` is opaque JSON,
echoed verbatim. `origin_token` is the id of the token that raised the alert (or
`null`), and is excluded from the live-stream fan-out.

### Model

```json
{
  "id": "lstm-v3.pte",
  "name": "lstm-v3",
  "ext": "pte",
  "path": "models/lstm-v3.pte",
  "meta": { "arch": "lstm", "params": 1200000, "trained": "2026-06-01" },
  "sha256": "…",
  "bytes": 4823104,
  "discovered_at": 1735689000000
}
```

`id` is the full artifact filename, so format variants of one logical model
(`net.pt`, `net.onnx`) register as distinct rows. `name` is the filename stem
and `ext` is its lowercased extension without the dot (`pt`, `onnx`, `pte`;
empty when the file has none), so a consumer learns the format without parsing
the server-local `path`. `meta` is **opaque JSON**: stored, served, and rendered
verbatim, never interpreted. `path` is relative to `storage.data_dir`.

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
  "mean_daily_carbs": 172.0,
  "tdd": 38.5,
  "bolus_basal_ratio": 1.4,
  "mean_hr": 71.2,
  "bg_hr_corr": -0.18,
  "n_samples": 264
}
```

`window` is one of `7d`, `30d`, `90d`; `updated_at` is the client's wall-clock
when the block was computed, stored verbatim. The three time-fraction fields (`tir`,
`time_below`, `time_above`) are fractions in `0..=1` about the 70–180 mg/dL
range. `gmi` and `cv` are percentages; `tdd` is units/day; `bg_hr_corr` is a
Pearson correlation in `-1..=1`; `n_samples` is the number of grid samples that
contributed BG to the window.

---

## Write endpoints (require `rw`)

### POST /v1/ingest

Atomic five-minute scalar bundle. Physiologic scalar fields are optional; those
present overwrite the row at `ts` in place (guarded so a write with a newer
`updated_at` wins), those absent leave the existing values untouched.
Carbohydrate, bolus, and basal are no longer part of ingest — log them as
[meal](#put-v1meals) and [dose](#put-v1doses) events. Writing the row fans out a
`sample` event to every connected session **except** the origin token's.

Request:

```json
{
  "ts": 1735689600000,
  "tz_offset": 0,
  "updated_at": 1735689605000,
  "bg": 112.0, "hr": 68.0, "steps": 30.0, "sleep": 0.0, "exercise": 0.0, "mood": 4
}
```

`ts`, `tz_offset`, and `updated_at` are required; the scalar series are all
optional.

Response `200`:

```json
{ "ok": true, "ts": 1735689600000 }
```

### PUT /v1/meals

Batch upsert of meal events. The body is a JSON array of
[Meal event](#meal-event) objects (write form: omit any optional field that is
absent). Idempotent by `client_id` — a redelivery is a no-op, a newer
`updated_at` replaces the row in place. Fans out one `meal` event per row to
every session **except** the origin token's.

Request:

```json
[
  {
    "client_id": "018f6b2e-3c7a-7e11-9a44-2b6f0e5d9c31",
    "ts": 1735689600000, "tz_offset": 0, "updated_at": 1735689605000,
    "grams": 40.0, "duration_min": 180.0, "gi": 52.0, "k": 2.0, "theta": 24.0,
    "note": "lunch"
  }
]
```

Response `200` — the accepted `client_id`s, in input order:

```json
{ "ok": true, "ids": ["018f6b2e-3c7a-7e11-9a44-2b6f0e5d9c31"] }
```

### PUT /v1/doses

Batch upsert of dose events. The body is a JSON array of
[Dose event](#dose-event) objects. Idempotent by `client_id` — a redelivery is a
no-op, a newer `updated_at` replaces the row in place. Fans out one `dose` event
per row to every session **except** the origin token's.

Request:

```json
[
  {
    "client_id": "018f6b2e-40aa-7c02-8f19-7d3c1a9b4e88",
    "ts": 1735689600000, "tz_offset": 0, "updated_at": 1735689605000,
    "kind": "bolus", "units": 4.0, "duration_min": 300.0, "k": 2.0, "theta": 40.0
  }
]
```

Response `200` — the accepted `client_id`s, in input order:

```json
{ "ok": true, "ids": ["018f6b2e-40aa-7c02-8f19-7d3c1a9b4e88"] }
```

### PUT /v1/basal-schedule

Full-replace the active basal schedule. The body is a single
[Basal schedule](#basal-schedule) object; its `slots` replace the stored active
schedule wholesale. Each slot is idempotent by its `client_id`, and a newer
`updated_at` replaces a slot in place. Fans out a `basal_schedule` event to every
session **except** the origin token's.

Request:

```json
{
  "schedule_id": "sched-2026-07",
  "active": true,
  "slots": [
    {
      "client_id": "018f6b2e-5100-7a33-b0c2-9e4f2d7a1b60",
      "label": "overnight", "time_of_day_min": 0, "dose_u": 0.8,
      "duration_min": 1440.0, "ka_per_hour": 0.5, "ke_per_hour": 0.7,
      "tz_offset": 0, "updated_at": 1735689605000
    }
  ]
}
```

Response `200` — the accepted slot `client_id`s:

```json
{ "ok": true, "ids": ["018f6b2e-5100-7a33-b0c2-9e4f2d7a1b60"] }
```

### PUT /v1/predictions

Insert or replace one or more predictions. The body is a JSON array of
[Prediction](#prediction) objects. Idempotent on `(made_at, model_id)`: a re-run
of the same cycle overwrites in place, a byte-identical redelivery is a no-op.
Fans out a `prediction` event to every session **except** the origin token's.

Request:

```json
[
  {
    "made_at": 1735689600000, "model_id": "lstm-v3", "updated_at": 1735689605000,
    "horizon_steps": 24, "line": [112.0], "fan": [[…]],
    "circadian": { "probs": [0,0,0,0,0,0,0,0,0,0,0,0], "predicted_hour": 7.5, "resultant_r": 0.71, "n_bins": 12, "bin_hours": 2.0 }
  }
]
```

`circadian` may be `null` or omitted when the model has no time-of-day head.

Response `200` — the server-assigned ids, in input order:

```json
{ "ok": true, "ids": [42] }
```

### PUT /v1/stats

Push one precomputed statistics block for a window. The server stores the block
verbatim and serves it back from [GET /v1/stats](#get-v1stats); it performs no
statistics computation of its own. Idempotent by `window` — a newer `updated_at`
replaces the stored block in place. Fans out a `stats` event to every session
**except** the origin token's.

Request:

```json
{
  "window": "7d", "updated_at": 1735689605000,
  "tir": 0.72, "time_below": 0.04, "time_above": 0.24,
  "mean_bg": 148.3, "gmi": 6.9, "cv": 34.1, "sd": 50.6,
  "hypo_events": { "count": 2, "duration_ms": 3600000 },
  "hyper_events": { "count": 5, "duration_ms": 14400000 },
  "mean_daily_carbs": 172.0, "tdd": 38.5, "bolus_basal_ratio": 1.4,
  "mean_hr": 71.2, "bg_hr_corr": -0.18, "n_samples": 264
}
```

`window` is one of `7d`, `30d`, `90d`. See the [Stats](#stats) schema for the
full field set.

Response `200`:

```json
{ "ok": true }
```

### POST /v1/notes

Request:

```json
{ "client_id": "018f6b2e-6200-7b44-a1d3-0f5e3c8b2a71", "ts": 1735689600000, "tz_offset": 0, "text": "note body", "updated_at": 1735689605000 }
```

`client_id` and `updated_at` are required; `tz_offset` defaults to `0` if
omitted. A note `ts` is a wall-clock instant (not grid-snapped). Idempotent by
`client_id` — a newer `updated_at` edits the note in place. Fans out a `note`
event to every connected session **except** the origin token's.

Response `200` — the note's `client_id`:

```json
{ "ok": true, "id": "018f6b2e-6200-7b44-a1d3-0f5e3c8b2a71" }
```

### POST /v1/photos

`multipart/form-data` with two parts: a text field `ts`, and an image file in a
field named `image`, `file`, or `photo`. The file extension determines the
stored format; the binary is written under `<data_dir>/photos/<sha256>.<ext>`.
Broadcasts a `photo` event. Dimensions are `0` here and filled in by the
importer/TUI when the image is decoded.

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
{ "client_id": "018f6b2e-7300-7c55-b2e4-1a6f4d9c3b82", "ts": 1735689600000, "kind": "low", "payload": { "bg": 58 } }
```

`client_id` is required and is the idempotency key; an alert is immutable, so a
redelivery of the same `client_id` is a no-op. `payload` is optional and opaque
(defaults to `null`).

Response `200` — the alert's `client_id`:

```json
{ "ok": true, "id": "018f6b2e-7300-7c55-b2e4-1a6f4d9c3b82" }
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

`next_cursor` is the `ts` of the last row returned (or `null` when the page is
empty). To page, pass it back as `cursor` until a request returns no rows.

### GET /v1/meals

Fetch meal events over a time range. Query: `from`, `to` (inclusive bounds on
`ts`). Returned in ascending `ts` order; absent optional fields are explicit
`null`.

```json
{ "meals": [ { "client_id": "018f…", "ts": 1735689600000, "tz_offset": 0, "updated_at": 1735689605000, "grams": 40.0, "duration_min": 180.0, "gi": 52.0, "k": 2.0, "theta": 24.0, "custom_curve": null, "note": "lunch" } ] }
```

### GET /v1/doses

Fetch dose events over a time range. Query: `from`, `to` (inclusive bounds on
`ts`). Returned in ascending `ts` order; absent optional fields are explicit
`null`.

```json
{ "doses": [ { "client_id": "018f…", "ts": 1735689600000, "tz_offset": 0, "updated_at": 1735689605000, "kind": "bolus", "units": 4.0, "duration_min": 300.0, "k": 2.0, "theta": 40.0, "ka_per_hour": null, "ke_per_hour": null, "custom_curve": null, "note": null } ] }
```

### GET /v1/basal-schedule

The active basal schedule as a [Basal schedule](#basal-schedule) object, or
`null` when none is active.

```json
{ "schedule_id": "sched-2026-07", "active": true, "slots": [ { "client_id": "018f…", "label": "overnight", "time_of_day_min": 0, "dose_u": 0.8, "duration_min": 1440.0, "ka_per_hour": 0.5, "ke_per_hour": 0.7, "tz_offset": 0, "updated_at": 1735689605000 } ] }
```

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
{ "notes": [ { "id": 7, "client_id": "018f6b2e-6200-7b44-a1d3-0f5e3c8b2a71", "ts": 1735689600000, "tz_offset": 0, "text": "…", "updated_at": 1735689605000, "created_at": 1735689601000 } ] }
```

### GET /v1/alerts

Query: `from`, `to`. Newest first.

Response `200`:

```json
{ "alerts": [ { "id": 11, "client_id": "018f6b2e-7300-7c55-b2e4-1a6f4d9c3b82", "ts": 1735689600000, "kind": "low", "payload": { "bg": 58 }, "origin_token": 2, "created_at": 1735689601000 } ] }
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

The discovered model registry.

```json
{ "models": [ { "id": "lstm-v3", "name": "LSTM v3", "path": "models/lstm-v3.pt", "meta": { "…": "…" }, "sha256": "…", "bytes": 4823104, "discovered_at": 1735689000000 } ] }
```

### GET /v1/models/{id}/meta

The opaque `meta` JSON for one model, returned verbatim (not wrapped). `404` if
the id is unknown.

```json
{ "arch": "lstm", "params": 1200000, "trained": "2026-06-01" }
```

### GET /v1/models/{id}/download

Streams the model artifact of any format as `application/octet-stream`, in
bounded chunks so a large file is never buffered whole. Response headers carry
`Content-Length`, `X-SHA256` (the artifact's content hash) for integrity
verification, and a `Content-Disposition` filename carrying the artifact's real
extension. `404` if the id is unknown.

### GET /v1/stats

Query parameters:

| Param | Type | Meaning |
| --- | --- | --- |
| `window` | enum | `7d` \| `30d` \| `90d` (default `7d`). An unrecognized window is `400`. |

Returns the most recent **client-pushed** block for `window` (see
[PUT /v1/stats](#put-v1stats)), or an all-zero block when none has been pushed.
The server never computes statistics itself; it serves the stored block verbatim.

Response `200`:

```json
{ "stats": { "window": "7d", "updated_at": 1735689605000, "tir": 0.72, "…": "…" } }
```

See the [Stats](#stats) schema for the full field set.

### GET /v1/health

Liveness probe. Requires a valid bearer token, like every other endpoint.

```json
{ "status": "ok", "ws_clients": 3 }
```

`ws_clients` is the current number of connected WebSocket subscribers.

---

## WebSocket

### GET /v1/stream?token=&lt;secret&gt;

A server-to-client push stream. Authentication is the `token` query parameter;
an invalid or revoked token is rejected with `401` before the upgrade. After the
upgrade the server sends events as they occur; inbound frames from the client
are ignored (a Close frame ends the stream). Either `rw` or `ro` tokens may
subscribe. Sessions — and thus the record of a connected viewer — persist across
reconnects.

Each event is a JSON object with a `"type"` discriminant and the event's fields
inlined alongside it. The nine event types carry, respectively, the
[Sample row](#sample-row), [Prediction](#prediction), [Note](#note),
[Photo](#photo-metadata), [Alert](#alert), [Meal event](#meal-event),
[Dose event](#dose-event), [Basal schedule](#basal-schedule), and
[Stats](#stats) schemas — tagged `sample`, `prediction`, `note`, `photo`,
`alert`, `meal`, `dose`, `basal_schedule`, and `stats`.

```json
{ "type": "sample", "ts": 1735689600000, "tz_offset": 0, "bg": 112.0, "hr": 68.0, "steps": null, "sleep": null, "exercise": null, "mood": null, "updated_at": 1735689605000 }
```

```json
{ "type": "prediction", "made_at": 1735689600000, "model_id": "lstm-v3", "horizon_steps": 24, "line": [112.0], "fan": [[…]], "circadian": null, "updated_at": 1735689605000 }
```

```json
{ "type": "note", "id": 7, "client_id": "018f…", "ts": 1735689600000, "tz_offset": 0, "text": "felt low before lunch", "updated_at": 1735689605000, "created_at": 1735689601000 }
```

```json
{ "type": "photo", "id": 3, "ts": 1735689600000, "path": "photos/…jpg", "sha256": "…", "width": 1024, "height": 768, "bytes": 184320, "created_at": 1735689601000 }
```

```json
{ "type": "alert", "id": 11, "client_id": "018f…", "ts": 1735689600000, "kind": "low", "payload": { "bg": 58 }, "origin_token": 2, "created_at": 1735689601000 }
```

```json
{ "type": "meal", "client_id": "018f…", "ts": 1735689600000, "tz_offset": 0, "updated_at": 1735689605000, "grams": 40.0, "duration_min": 180.0, "gi": 52.0, "k": 2.0, "theta": 24.0, "custom_curve": null, "note": "lunch" }
```

```json
{ "type": "dose", "client_id": "018f…", "ts": 1735689600000, "tz_offset": 0, "updated_at": 1735689605000, "kind": "bolus", "units": 4.0, "duration_min": 300.0, "k": 2.0, "theta": 40.0, "ka_per_hour": null, "ke_per_hour": null, "custom_curve": null, "note": null }
```

```json
{ "type": "basal_schedule", "schedule_id": "sched-2026-07", "active": true, "slots": [ { "client_id": "018f…", "label": "overnight", "time_of_day_min": 0, "dose_u": 0.8, "duration_min": 1440.0, "ka_per_hour": 0.5, "ke_per_hour": 0.7, "tz_offset": 0, "updated_at": 1735689605000 } ] }
```

```json
{ "type": "stats", "window": "7d", "updated_at": 1735689605000, "tir": 0.72, "…": "…" }
```

**Fan-out is except-origin.** Every write — ingest, meal, dose, basal-schedule,
prediction, stats, note, photo, and alert — is delivered to every connected
session **except** those belonging to the token that made the write. A client
therefore never receives an echo of its own write, and reconciles any history it
missed while disconnected over REST catch-up (`GET /v1/series`, `/v1/meals`,
`/v1/doses`, and the other read endpoints), not from the stream.
