"""Local stand-in for intervalsync.icu's device API (docs/device-api.md).

    python3 tools/mock_server.py                   # approve by hand, see below
    python3 tools/mock_server.py --auto-approve 3  # approve after the 3rd poll

Build the app against it with -Pintervalsync.apiBaseUrl=http://<this machine's IP>:8787.
To approve a pairing by hand, as the website would:

    curl -X POST localhost:8787/api/devices -d '{"code": "483920"}'

To make a code for a Garmin data field, as the website would:

    curl -X POST localhost:8787/api/devices/codes

Everything is in memory and there is no sign-in: never expose this beyond your own network.
"""

import argparse
import datetime
import json
import math
import secrets
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ATHLETE = {
    "athlete_name": "Test Rider",
    "cp_w": 250,
    "w_prime_j": 20000,
    "ftp_w": 260,
    "weight_kg": 75.0,
    "max_hr": 185,
    "resting_hr": 50,
    "plan": "Pro",
    "power_zones": [
        {"name": "Active Recovery", "max_w": 143},
        {"name": "Endurance", "max_w": 195},
        {"name": "Tempo", "max_w": 234},
        {"name": "Threshold", "max_w": 273},
        {"name": "VO2 Max", "max_w": 312},
        {"name": "Anaerobic", "max_w": 390},
        {"name": "Neuromuscular", "max_w": None},
    ],
    "hr_zones": [
        {"name": "Recovery", "max_bpm": 135},
        {"name": "Aerobic", "max_bpm": 150},
        {"name": "Tempo", "max_bpm": 158},
        {"name": "SubThreshold", "max_bpm": 167},
        {"name": "SuperThreshold", "max_bpm": 172},
        {"name": "Aerobic Capacity", "max_bpm": 177},
        {"name": "Anaerobic", "max_bpm": 185},
    ],
}
EXPIRES_IN = 600


def synthetic_power(seconds, hard):
    """An easy ride with a few efforts at `hard` watts."""
    power = [150] * seconds
    for start in range(900, seconds - 600, 1200):
        power[start:start + 300] = [hard] * 300
    return power


RIDES = {
    "m1": {"name": "Morning Ride", "started_at_ms": int((time.time() - 86400) * 1000), "watts": synthetic_power(5400, 290),
           "records": [{"duration_s": 5, "watts": 812}, {"duration_s": 300, "watts": 301}]},
    "m2": {"name": "Threshold 2x20", "started_at_ms": int((time.time() - 3 * 86400) * 1000), "watts": synthetic_power(3600, 245)},
    "m3": {"name": "Easy spin", "started_at_ms": int((time.time() - 5 * 86400) * 1000), "watts": [130] * 2700},
}

pairings = {}  # pairing_id -> {code, created, approved, polls}
tokens = set()
issued = {}  # code without dashes -> time made
ISSUED_EXPIRES_IN = 1800
auto_approve = 0


def summary(watts, cp, w_prime):
    """What the service works out for GET /api/device/rides/{id}/summary."""
    out = {"duration_s": len(watts)}
    if not watts:
        return out
    out["average_power_w"] = round(sum(watts) / len(watts), 1)
    for name, window in (("best_5min_w", 300), ("best_20min_w", 1200)):
        if len(watts) >= window:
            total = sum(watts[:window])
            best = total
            for i in range(window, len(watts)):
                total += watts[i] - watts[i - window]
                best = max(best, total)
            out[name] = round(best / window, 1)
    balance = lowest = w_prime
    for p in watts:
        balance = balance - (p - cp) if p > cp else w_prime - (w_prime - balance) * math.exp(-(cp - p) / w_prime)
        lowest = min(lowest, balance)
    out["w_prime_min_j"] = round(lowest)
    out["w_prime_j"] = w_prime
    return out


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, body=None):
        data = json.dumps(body).encode() if body is not None else b""
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        try:
            return json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            return {}

    def token(self):
        value = self.headers.get("Authorization", "").removeprefix("Bearer ")
        return value if value in tokens else None

    def do_POST(self):
        body = self.body()
        if self.path == "/api/device/pairings":
            pairing_id = secrets.token_urlsafe(32)
            code = f"{secrets.randbelow(10**6):06d}"
            pairings[pairing_id] = {"code": code, "created": time.time(), "approved": False, "polls": 0}
            print(f"  pairing code {code}")
            self.reply(201, {"pairing_id": pairing_id, "code": code, "verification_url": "intervalsync.icu/#devices", "expires_in": EXPIRES_IN, "interval": 5})
        elif self.path == "/api/device/pairings/token":
            pairing = pairings.get(body.get("pairing_id"))
            if pairing is None:
                return self.reply(404, {"error": "unknown pairing"})
            if time.time() - pairing["created"] > EXPIRES_IN:
                del pairings[body["pairing_id"]]
                return self.reply(410, {"error": "expired"})
            pairing["polls"] += 1
            if auto_approve and pairing["polls"] >= auto_approve:
                pairing["approved"] = True
            if not pairing["approved"]:
                return self.reply(200, {"status": "pending"})
            del pairings[body["pairing_id"]]
            token = secrets.token_urlsafe(32)
            tokens.add(token)
            self.reply(200, {"status": "approved", "token": token, "athlete_name": ATHLETE["athlete_name"]})
        elif self.path == "/api/device/rides":
            if not self.token():
                return self.reply(401, {"error": "unauthorized"})
            print(f"  ride report: {json.dumps(body)}")
            self.reply(201, {"status": "saved"})
        elif self.path == "/api/devices/codes":
            # The portal making a code for a device that can't show one (a Garmin data field), approved as it is made.
            code = "-".join("".join(secrets.choice("0123456789ABCDEFGHJKMNPQRSTVWXYZ") for _ in range(4)) for _ in range(3))
            issued[code.replace("-", "")] = time.time()
            print(f"  issued code {code}")
            self.reply(201, {"code": code, "expires_in": ISSUED_EXPIRES_IN})
        elif self.path == "/api/device/pairings/redeem":
            code = str(body.get("code", "")).upper().replace("-", "").replace(" ", "").replace("O", "0").replace("I", "1").replace("L", "1")
            made = issued.pop(code, None)
            if made is None:
                return self.reply(404, {"error": "unknown pairing"})
            if time.time() - made > ISSUED_EXPIRES_IN:
                return self.reply(410, {"error": "expired"})
            token = secrets.token_urlsafe(32)
            tokens.add(token)
            self.reply(200, {"status": "approved", "token": token, "athlete_name": ATHLETE["athlete_name"]})
        elif self.path == "/api/devices":
            match = next((p for p in pairings.values() if p["code"] == str(body.get("code"))), None)
            if match is None:
                return self.reply(404, {"error": "unknown code"})
            match["approved"] = True
            self.reply(200, {"status": "approved"})
        else:
            self.reply(404, {"error": "not found"})

    def do_GET(self):
        if not self.path.startswith("/api/device/"):
            return self.reply(404, {"error": "not found"})
        if not self.token():
            return self.reply(401, {"error": "unauthorized"})
        parts = self.path.split("/")
        if self.path == "/api/device/athlete":
            self.reply(200, ATHLETE)
        elif self.path == "/api/device/context":
            self.reply(200, {
                "goal": "Ride a sub-5-hour century on 14 March.",
                "events": "14 March: spring century (A). 2 May: club hill climb (B).",
                "constraints": "8 hours a week. Indoor trainer on weekdays.",
                "preferences": None,
                "notes": "",
            })
        elif self.path == "/api/device/workouts":
            today = datetime.date.today()
            day = lambda n: str(today + datetime.timedelta(days=n))
            self.reply(200, {"workouts": [
                {"id": "e1", "date": day(0), "name": "VO2 4x4", "type": "Ride", "duration_s": 3600, "load": 62,
                 "description": "- 15m 60%\n- 4x 4m 120%, 4m 50%\n- 10m 55%"},
                {"id": "e2", "date": day(1), "name": "Easy spin", "type": "Ride", "duration_s": 2700, "load": 25, "description": "Keep HR under 130."},
                {"id": "e3", "date": day(3), "name": "Strength", "type": "WeightTraining", "duration_s": 2400},
                {"id": "e4", "date": day(5), "name": "Long ride", "type": "Ride", "duration_s": 10800, "load": 150,
                 "description": "Steady endurance. Eat 60 g of carbs an hour and finish with 3x 8m at tempo if the legs are good."},
            ]})
        elif self.path == "/api/device/spaces":
            today = datetime.date.today()
            self.reply(200, {"spaces": [
                {
                    "name": "Dana Coaching",
                    "kind": "coach",
                    "brief": {
                        "goal": "Sub-5-hour century on 14 March.",
                        "focus": "Raise threshold through October, then long tempo.",
                        "priorities": ["Two key sessions a week", "Sleep 8 hours before key days"],
                        "constraints": "No riding on Fridays.",
                    },
                    "updates": [
                        {"author": "Dana", "date": str(today - datetime.timedelta(days=1)), "text": "Nice control yesterday. Move Thursday's VO2 to Wednesday if the weather turns."},
                    ],
                },
                {
                    "name": "Sunday Club",
                    "kind": "team",
                    "brief": {"goal": "Everyone finishes the spring century together.", "priorities": ["Sunday long ride"]},
                    "updates": [],
                },
            ]})
        elif self.path == "/api/device/coach":
            today = datetime.date.today()
            self.reply(200, {
                "comments": [
                    {"date": str(today - datetime.timedelta(days=1)), "scope": "ride", "ride_id": "m1", "title": "Z2 Endurance",
                     "text": "Steady and controlled. Heart rate drifted 4% in the last 20 minutes, so keep fuelling from the start."},
                    {"date": str(today - datetime.timedelta(days=2)), "scope": "week", "author": "Dana",
                     "text": "Seven hours and both key sessions done. This week adds one VO2 day; keep everything else easy."},
                ],
                "brief": {"date": str(today), "text": "VO2 4x4 builds your ceiling. Hold 120% for each rep; if the third one fades by more than 10 W, stop after it."},
                "targets": {"date": str(today), "power_max_w": 312, "hr_max_bpm": 172, "note": "Recoveries really easy."},
            })
        elif self.path == "/api/device/trends":
            today = datetime.date.today()
            monday = today - datetime.timedelta(days=today.weekday())
            hours = [6.5, 8.0, 7.25, 0.0, 4.5, 7.5, 9.0, 8.25, 5.5, 7.0, 8.5, 2.0]
            weeks = [
                {
                    "week_start": str(monday - datetime.timedelta(weeks=11 - i)),
                    "hours": h,
                    "cp_w": None if i < 2 else round(236 + i + 2 * math.sin(i)),
                    "w_prime_j": None if i < 2 else round(19800 + 150 * i - 500 * math.sin(i / 2)),
                }
                for i, h in enumerate(hours)
            ]
            self.reply(200, {"weeks": weeks})
        elif self.path == "/api/device/rides":
            rides = [{"id": i, "name": r["name"], "started_at_ms": r["started_at_ms"], "duration_s": len(r["watts"]), "records": r.get("records", [])} for i, r in RIDES.items()]
            self.reply(200, {"rides": rides})
        elif len(parts) == 6 and parts[3] == "rides" and parts[5] == "power" and parts[4] in RIDES:
            ride = RIDES[parts[4]]
            self.reply(200, {"id": parts[4], "started_at_ms": ride["started_at_ms"], "watts": ride["watts"]})
        elif len(parts) == 6 and parts[3] == "rides" and parts[5] == "summary" and parts[4] in RIDES:
            ride = RIDES[parts[4]]
            self.reply(200, {"id": parts[4], "started_at_ms": ride["started_at_ms"], **summary(ride["watts"], ATHLETE["cp_w"], ATHLETE["w_prime_j"])})
        else:
            self.reply(404, {"error": "not found"})

    def do_DELETE(self):
        if self.path == "/api/device":
            tokens.discard(self.token())
            self.reply(204)
        else:
            self.reply(404, {"error": "not found"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--auto-approve", type=int, default=0, metavar="N", help="approve each pairing after its Nth poll")
    args = parser.parse_args()
    auto_approve = args.auto_approve
    print(f"Mock Intervalsync device API on port {args.port}")
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
