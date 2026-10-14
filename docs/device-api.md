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

## Ride summary (not built on either side yet)

### `POST /api/device/rides`

`Authorization: Bearer <device token>`. Body: the ride's start time, duration and metrics. The service finds the matching Intervals.icu activity and attaches the metrics.

- `200 {"status": "attached", "activity_id": "..."}`
- `202 {"status": "pending"}` — the activity hasn't reached Intervals.icu yet; the device retries later.
