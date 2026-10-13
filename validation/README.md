# Formula validation

Runs every metric from the spec over the last 90 days of rides, straight from Intervals.icu, so the numbers can be judged before they are coded into the Karoo app.

## Setup

Create `.env` in the project root (it is git-ignored) with your Intervals.icu API key, from Intervals.icu → Settings → Developer Settings:

```
INTERVALS_API_KEY=<your key>
```

## Run

```bash
python3 validation/validate.py
```

The first run downloads each ride's 1 Hz power and HR into `validation/data/` (also git-ignored); later runs reuse it. `--offline` reports from the cache only.

## What it reports

Per ride: lowest W′ balance, best 5 and 20 min power, summed per-second stress next to Intervals.icu's load, the main benefit by zone, and Form Today two ways (the spec's power-per-beat ratio, and against the baseline HR–power line).

Overall: 90-day bests, the CP/W′ fit from them next to Intervals.icu's own model, VO2max by the Intervals.icu formula, the HR–power line, and any rides where W′ balance went negative.
