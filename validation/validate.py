"""Run every metric over the last 90 days of rides from Intervals.icu.

Reads INTERVALS_API_KEY (and optionally INTERVALS_ATHLETE_ID) from the environment
or from .env in the project root. Streams are cached in validation/data/.

    python3 validation/validate.py            # download what's missing, then report
    python3 validation/validate.py --offline  # report from the cache only
"""

import base64
import datetime
import json
import os
import sys
import urllib.request
from pathlib import Path

import metrics

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "validation" / "data"
API = "https://intervals.icu/api/v1"
DAYS = 90
BEST_WINDOWS = [180, 300, 720, 1200]


def load_env():
    env_file = ROOT / ".env"
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            key, sep, value = line.strip().partition("=")
            if sep and not key.startswith("#"):
                os.environ.setdefault(key.strip(), value.strip().strip("\"'"))


def api_get(path):
    key = os.environ.get("INTERVALS_API_KEY")
    if not key:
        sys.exit("INTERVALS_API_KEY is not set. Put it in .env (see validation/README.md).")
    request = urllib.request.Request(API + path)
    token = base64.b64encode(f"API_KEY:{key}".encode()).decode()
    request.add_header("Authorization", "Basic " + token)
    # Intervals.icu answers 403 to urllib's default user agent
    request.add_header("User-Agent", "intervalsync-karoo-validation")
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def cached(name, path, offline):
    file = DATA / name
    if file.exists():
        return json.loads(file.read_text())
    if offline:
        return None
    data = api_get(path)
    DATA.mkdir(parents=True, exist_ok=True)
    file.write_text(json.dumps(data))
    return data


def stream(streams, kind):
    data = next((s["data"] for s in streams if s["type"] == kind), None)
    return data or []


def main():
    offline = "--offline" in sys.argv
    load_env()
    athlete_id = os.environ.get("INTERVALS_ATHLETE_ID", "0")
    today = datetime.date.today()
    oldest = today - datetime.timedelta(days=DAYS)

    athlete = cached("athlete.json", f"/athlete/{athlete_id}", offline)
    activities = cached("activities.json", f"/athlete/{athlete_id}/activities?oldest={oldest}&newest={today}", offline)
    if athlete is None or activities is None:
        sys.exit("Nothing cached yet; run once without --offline.")

    ride = next(s for s in athlete["sportSettings"] if "Ride" in s["types"])
    model = ride.get("mmp_model") or {}
    ftp = ride["ftp"]
    cp = model.get("criticalPower") or ftp
    w_prime = model.get("wPrime") or ride.get("w_prime") or 20_000
    weight = athlete.get("icu_weight") or athlete.get("weight")
    print(f"Athlete: FTP {ftp} W, CP {cp} W, W′ {w_prime / 1000:.1f} kJ, weight {weight} kg, max HR {ride.get('max_hr')}")

    rides = []
    for activity in sorted(activities, key=lambda a: a["start_date_local"]):
        if activity.get("type") not in ("Ride", "VirtualRide") or not activity.get("icu_average_watts"):
            continue
        streams = cached(f"{activity['id']}.json", f"/activity/{activity['id']}/streams.json?types=watts,heartrate", offline)
        if not streams:
            continue
        power = [p or 0 for p in stream(streams, "watts")]
        hr = stream(streams, "heartrate") or [None] * len(power)
        if not power:
            continue
        rides.append((activity, power, hr))
    print(f"{len(rides)} rides with power in the last {DAYS} days\n")

    header = f"{'date':10} {'name':28} {'min':>4} {'avgW':>5} {'icuW':>5} {'W′min%':>6} {'5min':>5} {'20min':>5} {'stress':>6} {'load':>5} {'main benefit':>12} {'PC':>4} {'PCline':>6}"
    print(header)
    print("-" * len(header))

    bests = {w: 0.0 for w in BEST_WINDOWS}
    blocks_by_ride = []
    negative = []
    for activity, power, hr in rides:
        date = datetime.date.fromisoformat(activity["start_date_local"][:10])
        wbal = metrics.wprime_balance(power, cp, w_prime)
        ride_bests = {w: metrics.rolling_best(power, w) for w in BEST_WINDOWS}
        for w, value in ride_bests.items():
            bests[w] = max(bests[w], value or 0.0)
        stress = metrics.ride_contribution(power, ftp)
        main_benefit = max(stress, key=stress.get)

        # Form Today: this ride's steady blocks after 10 min vs the 30 days before it
        baseline = [b for d, blocks in blocks_by_ride if 0 < (date - d).days <= 30 for b in blocks]
        mine = metrics.steady_blocks(power, hr, start=600, min_power=0.4 * ftp)
        pc = pc_line = ""
        if len(baseline) >= 10 and mine:
            ratio = lambda bs: sum(p / h for p, h in bs) / len(bs)
            pc = metrics.performance_condition(ratio(mine), ratio(baseline))
            a, b, _ = metrics.linear_fit(baseline)
            # same idea, but against the HR the baseline line predicts at this ride's power
            pc_line = round(100 * (sum((a + b * p) / h for p, h in mine) / len(mine) - 1))
        blocks_by_ride.append((date, metrics.steady_blocks(power, hr, min_power=0.4 * ftp)))

        if min(wbal) < 0:
            negative.append((activity, min(wbal)))
        fmt = lambda v: f"{v:.0f}" if v else "-"
        print(
            f"{date} {activity['name'][:28]:28} {len(power) // 60:>4} {sum(power) / len(power):>5.0f} "
            f"{activity['icu_average_watts']:>5} {100 * min(wbal) / w_prime:>6.0f} {fmt(ride_bests[300]):>5} "
            f"{fmt(ride_bests[1200]):>5} {sum(stress.values()):>6.0f} {activity.get('icu_training_load') or 0:>5} "
            f"{main_benefit:>12} {pc:>4} {pc_line:>6}"
        )

    print("\n90-day bests: " + ", ".join(f"{w // 60} min {p:.0f} W" for w, p in bests.items()))
    fit_cp, fit_w = metrics.fit_cp(bests)
    print(f"CP fit from those: CP {fit_cp:.0f} W, W′ {fit_w / 1000:.1f} kJ   (Intervals.icu model: CP {cp} W, W′ {w_prime / 1000:.1f} kJ)")
    if weight:
        print(f"VO2max from best 5 min ({bests[300]:.0f} W): {metrics.vo2max_from_5min(bests[300], weight):.1f} ml/kg/min")
    all_blocks = [b for _, blocks in blocks_by_ride for b in blocks]
    if len(all_blocks) >= 10:
        a, b, r = metrics.linear_fit(all_blocks)
        print(f"HR-power line over {len(all_blocks)} steady blocks: HR = {a:.0f} + {b:.3f} x W  (r = {r:.2f})")
        low, high = 0.5 * ftp, 0.9 * ftp
        print(f"  power per beat on that line: {low / (a + b * low):.2f} at {low:.0f} W vs {high / (a + b * high):.2f} at {high:.0f} W")
    if negative:
        print(f"W′ balance went below zero on {len(negative)} rides (CP/W′ too low for these efforts):")
        for activity, low in negative:
            print(f"  {activity['start_date_local'][:10]} {activity['name'][:40]}: {low / 1000:.1f} kJ")


if __name__ == "__main__":
    main()
