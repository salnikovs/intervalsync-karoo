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
  "resting_hr": 42,
  "power_zones": [
    {"name": "Active Recovery", "max_w": 136},
    {"name": "Neuromuscular", "max_w": null}
  ],
  "hr_zones": [
    {"name": "Recovery", "max_bpm": 138},
    {"name": "Anaerobic", "max_bpm": 189}
  ]
}
```

`power_zones` and `hr_zones` are the rider's own zones from their Intervals.icu ride settings, lowest first, each with its name and upper bound; power bounds are in watts (the percentages of FTP already applied) and the open-ended top power zone has `max_w: null`. The Karoo shows them and has no settings of its own for CP, W′, FTP or zones: they are set up in Intervals.icu.

`401` on any device call means the token was revoked; the device forgets it and shows itself as not connected. The device calls this after pairing and at most once a day after that.

## Past rides

The Karoo can't read rides already recorded on it, so it fetches them back from Intervals.icu through the service and runs them through the same code as a live ride. Both calls happen only when the rider opens the past-rides screen.

### `GET /api/device/rides`

`Authorization: Bearer <device token>`. Rides with power from the last 30 days, newest first, at most 20.

```json
{
  "rides": [
    {
      "id": "i123",
      "name": "Morning Ride",
      "started_at_ms": 1791500000000,
      "duration_s": 4244,
      "records": [{"duration_s": 5, "watts": 812}, {"duration_s": 1200, "watts": 268}]
    }
  ]
}
```

`records` is optional: the durations among 5, 15 and 30 seconds and 5 and 20 minutes for which this ride's best power beat the rider's best in the 90 days before it, each with this ride's watts. The Karoo shows each as a badge. Omitted or empty when the ride set none, or when there aren't 90 days of history to compare with.

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

## Planned workouts

### `GET /api/device/workouts`

`Authorization: Bearer <device token>`. Workouts planned on the rider's Intervals.icu calendar from today through the next 7 days in the athlete's own timezone, oldest first, at most 14.

```json
{
  "workouts": [
    {
      "id": "e123",
      "date": "2026-10-14",
      "name": "VO2 4x4",
      "type": "Ride",
      "duration_s": 3600,
      "load": 62,
      "description": "- 15m 60%\n- 4x 4m 120%, 4m 50%\n- 10m 55%"
    }
  ]
}
```

`duration_s`, `load` and `description` are optional. `description` is the workout's text as the rider or coach wrote it, plain text, cut at 1,000 characters.

The device calls this when the rider opens the Plan tab, at most once an hour.

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

## Shared spaces

### `GET /api/device/spaces`

`Authorization: Bearer <device token>`. The coach and team spaces this rider belongs to and may read right now, each with its shared brief and its latest updates. A space that is closed or archived to this rider is left out entirely.

```json
{
  "spaces": [
    {
      "name": "Spring century group",
      "kind": "team",
      "brief": {
        "goal": "...",
        "focus": "...",
        "priorities": ["...", "..."],
        "constraints": "..."
      },
      "updates": [
        {"author": "Dana", "date": "2026-10-12", "text": "..."}
      ]
    }
  ]
}
```

- `kind` is `coach` (a coach and one athlete) or `team` (2 to 10 riders).
- Every part of `brief` is optional.
- `updates` are the newest three messages posted to the space, newest first, each cut at 500 characters. Authors are display names only.
- These are other people's words: the Karoo always shows them under the space's name and each update under its author.

The device calls this when the rider opens the app, at most once an hour.

## Coach

Written by the rider's Intervalsync assistant, or by their coach, for the Karoo. Nothing here is read from or written to Intervals.icu: it is text and numbers saved on the Intervalsync account, like the training context.

### `GET /api/device/coach`

`Authorization: Bearer <device token>`.

```json
{
  "comments": [
    {"date": "2026-10-12", "scope": "ride", "ride_id": "i123", "title": "Z2 Endurance", "text": "..."},
    {"date": "2026-10-11", "scope": "week", "author": "Dana", "text": "..."}
  ],
  "brief": {"date": "2026-10-13", "text": "...", "author": "Dana"},
  "targets": {"date": "2026-10-13", "power_max_w": 190, "hr_max_bpm": 137, "note": "..."}
}
```

- `comments` — notes after reviewing training, newest first, at most 10. `scope` is `ride` or `week`; `title` is optional. `ride_id` is optional: the `id` from `GET /api/device/rides` of the ride a `ride` comment is about. Without it the Karoo matches a ride comment to a past ride by date, and only when it is the one ride comment of that day.
- `author` (on a comment or the brief) is optional: the display name of the coach who wrote it, when a person did. Omitted when the assistant wrote it.
- `brief` — what the day's workout is for and what to watch, from the assistant or the rider's coach. The Karoo shows it only on its `date`, at the top of its Training tab (four lines until tapped) and on its Plan tab.
- `targets` — caps for the day's ride; each of `power_max_w`, `hr_max_bpm` and `note` is optional. The Karoo applies them only on their `date`: it shows them, offers them as data fields, and warns the rider in-ride when the 10-second average goes over a cap.
- `brief` and `targets` are `null` or omitted when the assistant hasn't set them.

The device calls this when the rider opens the app and when the extension starts, at most once an hour.

## Ride report

What only the Karoo knows about a finished ride, sent for the assistant to use. It is saved on the Intervalsync account; nothing is written to Intervals.icu.

### `POST /api/device/rides`

`Authorization: Bearer <device token>`. Sent when the rider taps Send on the ride summary.

```json
{
  "ended_at_ms": 1791500000000,
  "duration_s": 4244,
  "average_power_w": 186.2,
  "best_5min_w": 265.4,
  "best_20min_w": 234.1,
  "w_prime_min_j": -3100,
  "w_prime_j": 21600,
  "zones": [{"name": "Endurance", "stress": 31.0}],
  "feel": 2
}
```

Every metric is optional. `w_prime_min_j` is the lowest W′ balance reached and `w_prime_j` the rider's W′ at the time. `zones` is training stress per power zone, lowest first. `feel` is the rider's own rating, 1 (strong) to 5 (weak), or absent if they skipped it.

`201 {"status": "saved"}`. Sending the same `ended_at_ms` again replaces the earlier report.
