# Device API

The contract between the Karoo app and intervalsync.icu. The app implements the client side; `tools/mock_server.py` implements the server side for local testing. The real service does not have these endpoints yet.

All bodies are JSON. The Karoo sends requests through Karoo's own HTTP channel (Wi-Fi, or Bluetooth via the companion app), so they must stay small.

## Pairing

The Karoo never holds an Intervals.icu credential. It shows a short code, the rider enters it at intervalsync.icu while signed in, and the Karoo receives a device token bound to that rider's account and connection.

### `POST /api/device/pairings`

No auth. Body: `{"device": "Karoo"}`.

`201`:

```json
{
  "pairing_id": "<long random secret, known only to the device>",
  "code": "483920",
  "verification_url": "intervalsync.icu/#devices",
  "expires_in": 600,
  "interval": 5
}
```

### `POST /api/device/pairings/token`

No auth. Body: `{"pairing_id": "..."}`. The device polls this every `interval` seconds.

- `200 {"status": "pending"}` — the rider hasn't entered the code yet.
- `200 {"status": "approved", "token": "...", "athlete_name": "..."}` — returned once; the pairing is then spent.
- `404` or `410` — unknown or expired; the device stops polling.

### `POST /api/devices` (website, signed in)

Session cookie and CSRF token. Body: `{"code": "483920"}`. Approves the pairing for the signed-in rider. Attempts are limited per account.

### `DELETE /api/device`

`Authorization: Bearer <device token>`. Revokes the token. `204`.

## Athlete parameters

### `GET /api/device/athlete`

`Authorization: Bearer <device token>`.

`200`, every value optional:

```json
{
  "athlete_name": "...",
  "cp_w": 235,
  "w_prime_j": 21600,
  "ftp_w": 248,
  "weight_kg": 81.8,
  "max_hr": 189,
  "resting_hr": 42
}
```

`401` on any device call means the token was revoked; the device forgets it and shows itself as not connected. The device calls this after pairing and at most once a day after that.

## Past rides

The Karoo can't read rides already recorded on it, so it fetches them back from Intervals.icu through the service and runs them through the same code as a live ride. Both calls happen only when the rider opens the past-rides screen.

### `GET /api/device/rides`

`Authorization: Bearer <device token>`. Rides with power from the last 30 days, newest first, at most 20.

```json
{
  "rides": [
    {"id": "i123", "name": "Morning Ride", "started_at_ms": 1791500000000, "duration_s": 4244}
  ]
}
```

### `GET /api/device/rides/{id}/power`

`Authorization: Bearer <device token>`. The ride's power at 1 Hz, one integer per recorded second, with missing samples as 0.

```json
{"id": "i123", "started_at_ms": 1791500000000, "watts": [112, 118, 121]}
```

- `404` — not one of this rider's rides, or it has no power.
- `413` — longer than 6 hours; the response would be too large for Karoo's HTTP channel.

## Training context

### `GET /api/device/context`

`Authorization: Bearer <device token>`. The training context the rider saved to their Intervalsync account, in their own words. It reads nothing from Intervals.icu. A section the rider hasn't saved is omitted or `null`.

```json
{
  "goal": "...",
  "events": "...",
  "constraints": "...",
  "preferences": "...",
  "notes": "..."
}
```

The device calls this when the rider opens the app, at most once every 6 hours.

## Trends

### `GET /api/device/trends`

`Authorization: Bearer <device token>`. One entry per week for the last 12 weeks, oldest first, ending with the current (partial) week. Weeks start on Monday in the athlete's own timezone. A week with no training is included with `hours: 0`.

```json
{
  "weeks": [
    {"week_start": "2026-07-27", "hours": 7.5, "cp_w": 231, "w_prime_j": 20900}
  ]
}
```

- `hours` — training time that week, counting every activity type, not only rides.
- `cp_w`, `w_prime_j` — the rider's modelled critical power and W′ as of the end of that week (as of today for the current week); `null` where there isn't enough data to model them.

The device shows the last 6 or 12 of these. It calls this when the rider opens the app, at most once every 6 hours.

## Ride summary (not built on either side yet)

### `POST /api/device/rides`

`Authorization: Bearer <device token>`. Body: the ride's start time, duration and metrics. The service finds the matching Intervals.icu activity and attaches the metrics.

- `200 {"status": "attached", "activity_id": "..."}`
- `202 {"status": "pending"}` — the activity hasn't reached Intervals.icu yet; the device retries later.
