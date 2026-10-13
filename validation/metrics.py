"""Reference implementations of the spec's metrics, on 1 Hz power and HR lists.

These mirror core/ (Kotlin) so the two can be compared on real rides.
"""

import math

# Coggan power zones, upper bound as a fraction of FTP
ZONES = [
    ("Recovery", 0.55),
    ("Endurance", 0.75),
    ("Tempo", 0.90),
    ("Threshold", 1.05),
    ("VO2max", 1.20),
    ("Anaerobic", math.inf),
]


def wprime_balance(power, cp, w_prime):
    """Skiba differential W′ balance after each second, in joules."""
    balance = w_prime
    out = []
    for p in power:
        if p > cp:
            balance -= p - cp
        else:
            balance = w_prime - (w_prime - balance) * math.exp(-(cp - p) / w_prime)
        out.append(balance)
    return out


def rolling_best(power, window):
    """Best average power over `window` seconds, or None if the ride is shorter."""
    if len(power) < window:
        return None
    total = sum(power[:window])
    best = total
    for i in range(window, len(power)):
        total += power[i] - power[i - window]
        best = max(best, total)
    return best / window


def ride_contribution(power, ftp):
    """Per-second training stress, (P/FTP)^2 / 36, summed by power zone."""
    stress = {name: 0.0 for name, _ in ZONES}
    for p in power:
        fraction = p / ftp
        zone = next(name for name, upper in ZONES if fraction <= upper)
        stress[zone] += fraction * fraction / 36
    return stress


def fit_cp(bests):
    """Two-parameter CP model: least-squares line through work = CP * t + W′.

    `bests` maps duration in seconds to best average watts. Returns (cp, w_prime).
    """
    points = [(t, p * t) for t, p in bests.items() if p]
    n = len(points)
    mean_t = sum(t for t, _ in points) / n
    mean_w = sum(w for _, w in points) / n
    cp = sum((t - mean_t) * (w - mean_w) for t, w in points) / sum((t - mean_t) ** 2 for t, _ in points)
    return cp, mean_w - cp * mean_t


def steady_blocks(power, hr, start=0, seconds=180, max_cv=0.10, min_power=0.0):
    """(mean power, mean HR) of consecutive blocks where power varies by under `max_cv`."""
    blocks = []
    for i in range(start, len(power) - seconds + 1, seconds):
        p = power[i:i + seconds]
        h = [x for x in hr[i:i + seconds] if x]
        if len(h) < seconds * 0.9:
            continue
        mean_p = sum(p) / seconds
        if mean_p < min_power or mean_p <= 0:
            continue
        cv = math.sqrt(sum((x - mean_p) ** 2 for x in p) / seconds) / mean_p
        if cv < max_cv:
            blocks.append((mean_p, sum(h) / len(h)))
    return blocks


def performance_condition(today_ratio, baseline_ratio):
    """Spec's Form Today: % change in power per heartbeat vs baseline, clamped to +-20."""
    return max(-20, min(20, round(100 * (today_ratio / baseline_ratio - 1))))


def linear_fit(points):
    """Least-squares y = a + b * x and correlation r for (x, y) points."""
    n = len(points)
    mean_x = sum(x for x, _ in points) / n
    mean_y = sum(y for _, y in points) / n
    sxx = sum((x - mean_x) ** 2 for x, _ in points)
    syy = sum((y - mean_y) ** 2 for _, y in points)
    sxy = sum((x - mean_x) * (y - mean_y) for x, y in points)
    b = sxy / sxx
    return mean_y - b * mean_x, b, sxy / math.sqrt(sxx * syy)


def vo2max_from_5min(best_5min_w, weight_kg):
    """Intervals.icu's estimate (Sitko et al., IJSPP 2022), ml/kg/min."""
    return 16.6 + 8.87 * best_5min_w / weight_kg
