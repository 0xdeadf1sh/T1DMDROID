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
  - [Prediction](#prediction)
  - [Note](#note)
  - [Photo (metadata)](#photo-metadata)
  - [Alert](#alert)
  - [Model](#model)
  - [Stats](#stats)
- [Write endpoints (require `rw`)](#write-endpoints-require-rw)
  - [POST /v1/ingest](#post-v1ingest)
  - [PUT /v1/series/{name}](#put-v1seriesname)
  - [PUT /v1/predictions](#put-v1predictions)
  - [POST /v1/notes](#post-v1notes)
  - [POST /v1/photos](#post-v1photos)
  - [POST /v1/alerts](#post-v1alerts)
- [Read endpoints (`ro` or `rw`)](#read-endpoints-ro-or-rw)
  - [GET /v1/series](#get-v1series)
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
- **The 5-minute grid.** Every physiologic timestamp sits on a fixed
  five-minute grid: `ts % 300000 == 0`. A timestamp off the grid is rejected.
- **`tz_offset`** is the client's UTC offset in minutes at the sample time
  (e.g. `-300` for UTC−5), carried alongside the timestamp for local-time
  rendering.
- **Storage units are fixed:** blood glucose in mg/dL; carbohydrates, bolus,
  and basal are the amount ingested or delivered *within that 5-minute bucket*
  (grams and units respectively), so a day of buckets sums to the daily total;
  heart rate in bpm, steps as a count, and mood as a small integer; `sleep` and
  `exercise` are stored as plain scalar magnitudes. The mg/dL ↔ mmol/L toggle is
  display-only and never affects the wire format.
- **Gaps are explicit.** A missing series value is `null`, never omitted, in
  read responses.
- **Total insulin** (`bolus + basal`) is derived at display time and never
  stored or returned as a field.

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

`GET /v1/health` is the sole unauthenticated endpoint.

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
  "carbs": 40.0,
  "bolus": 4.0,
  "basal": 0.8,
  "hr": 68.0,
  "steps": 30.0,
  "sleep": 0.0,
  "exercise": 0.0,
  "mood": 4,
  "updated_at": 1735689605000
}
```

The nine series are `bg, carbs, bolus, basal, hr, steps, sleep, exercise,
mood`. All are floating point except `mood`, which is an integer. `updated_at`
is the millisecond time the row was last written.

### Prediction

```json
{
  "id": 42,
  "made_at": 1735689600000,
  "model_id": "lstm-v3",
  "horizon_steps": 24,
  "line": [112.0, 118.0, 123.0, "…"],
  "fan": [[…], […], […], […], […], […], […]],
  "tod": [0.0, 0.0, 0.1, 0.3, "…"],
  "tod_conf": 0.71,
  "created_at": 1735689601000
}
```

- `line` — the predicted median series, length `horizon_steps`, in mg/dL.
- `fan` — a `7 × horizon_steps` matrix, one row per quantile level in the exact
  ascending order `[0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95]`. Row index 3 (the
  `0.5` level) equals `line`.
- `tod` — a twelve-bin circadian distribution over 24 hours (two hours per bin);
  bin units are hours.
- `tod_conf` — confidence scalar for the `tod` distribution.

### Note

```json
{ "id": 7, "ts": 1735689600000, "tz_offset": 0, "text": "felt low before lunch", "created_at": 1735689601000 }
```

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
  "ts": 1735689600000,
  "kind": "low",
  "payload": { "bg": 58 },
  "origin_token": 2,
  "created_at": 1735689601000
}
```

`payload` is opaque JSON, echoed verbatim. `origin_token` is the id of the
token that raised the alert (or `null`), and is excluded from the live-stream
fan-out.

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

`window` is one of `7d`, `30d`, `90d`. The three time-fraction fields (`tir`,
`time_below`, `time_above`) are fractions in `0..=1` about the 70–180 mg/dL
range. `gmi` and `cv` are percentages; `tdd` is units/day; `bg_hr_corr` is a
Pearson correlation in `-1..=1`; `n_samples` is the number of grid samples that
contributed BG to the window.

---

## Write endpoints (require `rw`)

### POST /v1/ingest

Atomic five-minute bundle. Physiologic fields are optional; those present
overwrite the row at `ts` in place, those absent leave the existing values
untouched. An embedded `prediction` and any `notes` are written in the same
transaction. Writing the row broadcasts a `sample` event to the live stream.

Request:

```json
{
  "ts": 1735689600000,
  "tz_offset": 0,
  "bg": 112.0, "carbs": 40.0, "bolus": 4.0, "basal": 0.8,
  "hr": 68.0, "steps": 30.0, "sleep": 0.0, "exercise": 0.0, "mood": 4,
  "prediction": {
    "model_id": "lstm-v3",
    "horizon_steps": 24,
    "line": [112.0, 118.0],
    "fan": [[…], […], […], […], […], […], […]],
    "tod": [0,0,0,0,0,0,0,0,0,0,0,0],
    "tod_conf": 0.71
  },
  "notes": ["felt low before lunch"]
}
```

Only `ts` and `tz_offset` are required. The embedded prediction object has the
same fields as the [Prediction](#prediction) schema minus the server-assigned
`id`, `made_at`, and `created_at`.

Response `200`:

```json
{ "ok": true, "ts": 1735689600000 }
```

### PUT /v1/series/{name}

Batch upsert, override, and backfill for one series. `name` is one of `bg,
carbs, bolus, basal, hr, steps, sleep, exercise, mood`; an unknown name is
`400`. Each point's `ts` is snapped to the grid; the value overwrites that
column in place, creating the row if needed.

Request:

```json
{ "samples": [ { "ts": 1735689600000, "value": 110.0 }, { "ts": 1735689900000, "value": 108.0 } ] }
```

Response `200`:

```json
{ "ok": true, "written": 2 }
```

### PUT /v1/predictions

Insert one or more predictions. The body is a JSON array of prediction objects
(the same shape as the embedded `prediction` in [ingest](#post-v1ingest)).

Request:

```json
[
  { "model_id": "lstm-v3", "horizon_steps": 24, "line": [112.0], "fan": [[…]], "tod": [0,0,0,0,0,0,0,0,0,0,0,0], "tod_conf": 0.71 }
]
```

Response `200` — the server-assigned ids, in input order:

```json
{ "ok": true, "ids": [42] }
```

### POST /v1/notes

Request:

```json
{ "ts": 1735689600000, "tz_offset": 0, "text": "note body" }
```

`tz_offset` defaults to `0` if omitted. Broadcasts a `note` event.

Response `200`:

```json
{ "ok": true, "id": 7 }
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
{ "ts": 1735689600000, "kind": "low", "payload": { "bg": 58 } }
```

`payload` is optional and opaque (defaults to `null`).

Response `200`:

```json
{ "ok": true, "id": 11 }
```

---

## Read endpoints (`ro` or `rw`)

### GET /v1/series

Fetch wide sample rows over a time range, paginated forward by timestamp.

Query parameters:

| Param | Type | Meaning |
| --- | --- | --- |
| `fields` | csv | Comma-separated series allowlist (e.g. `bg,carbs,hr`); default all. An unknown name is `400`. |
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
    { "ts": 1735689600000, "tz_offset": 0, "bg": 112.0, "carbs": null, "bolus": null, "basal": 0.8, "hr": 68.0, "steps": null, "sleep": null, "exercise": null, "mood": null, "updated_at": 1735689605000 }
  ],
  "next_cursor": 1735689600000
}
```

`next_cursor` is the `ts` of the last row returned (or `null` when the page is
empty). To page, pass it back as `cursor` until a request returns no rows.

### GET /v1/predictions

Query: `from`, `to` (inclusive bounds on `made_at`). Newest first.

Response `200`:

```json
{ "predictions": [ { "id": 42, "made_at": 1735689600000, "…": "…" } ] }
```

### GET /v1/predictions/latest

The single most recent prediction, or `null`.

Response `200`:

```json
{ "prediction": { "id": 42, "…": "…" } }
```

### GET /v1/notes

Query: `from`, `to` (inclusive bounds on `ts`). Newest first.

Response `200`:

```json
{ "notes": [ { "id": 7, "ts": 1735689600000, "tz_offset": 0, "text": "…", "created_at": 1735689601000 } ] }
```

### GET /v1/alerts

Query: `from`, `to`. Newest first.

Response `200`:

```json
{ "alerts": [ { "id": 11, "ts": 1735689600000, "kind": "low", "payload": { "bg": 58 }, "origin_token": 2, "created_at": 1735689601000 } ] }
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
| `refresh` | bool | When truthy (`1`/`true`), bypass the cache and compute fresh. |

Stats are **daily-cached**: each window is recomputed at most once per day (on a
server background task and lazily on first read past the TTL), and the cached
block is served on request. Pass `?refresh=1` to force a fresh compute,
bypassing the cache.

Response `200`:

```json
{ "stats": { "window": "7d", "tir": 0.72, "…": "…" } }
```

See the [Stats](#stats) schema for the full field set.

### GET /v1/health

Unauthenticated liveness probe.

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
inlined alongside it. The five event types carry, respectively, the
[Sample row](#sample-row), [Prediction](#prediction), [Note](#note),
[Photo](#photo-metadata), and [Alert](#alert) schemas.

```json
{ "type": "sample",     "ts": 1735689600000, "tz_offset": 0, "bg": 112.0, "carbs": null, "bolus": null, "basal": 0.8, "hr": 68.0, "steps": null, "sleep": null, "exercise": null, "mood": null, "updated_at": 1735689605000 }
```

```json
{ "type": "prediction", "id": 42, "made_at": 1735689600000, "model_id": "lstm-v3", "horizon_steps": 24, "line": [112.0], "fan": [[…]], "tod": [0,0,0,0,0,0,0,0,0,0,0,0], "tod_conf": 0.71, "created_at": 1735689601000 }
```

```json
{ "type": "note",       "id": 7, "ts": 1735689600000, "tz_offset": 0, "text": "felt low before lunch", "created_at": 1735689601000 }
```

```json
{ "type": "photo",      "id": 3, "ts": 1735689600000, "path": "photos/…jpg", "sha256": "…", "width": 1024, "height": 768, "bytes": 184320, "created_at": 1735689601000 }
```

```json
{ "type": "alert",      "id": 11, "ts": 1735689600000, "kind": "low", "payload": { "bg": 58 }, "origin_token": 2, "created_at": 1735689601000 }
```

**Alert fan-out.** An alert posted via `POST /v1/alerts` is delivered to every
connected session **except** those belonging to the token that posted it, so a
client never receives an echo of its own alert.
