#!/usr/bin/env python3
"""PC thermal / load watch for StormTrooper (ProxMobile; checklist Phase 14 "Thermal guard").

Runs 24/7 as the proxai-thermal-watch user service (scripts/install-thermal-watch.sh). Samples every
second: GPU temp, power, use, VRAM, fan, clocks, throttle reasons (one streaming nvidia-smi), CPU
package + hottest core temp (coretemp), CPU use, RAM and swap.
  - every sample  -> <date>.csv (one file per day, 14 days kept) and latest.json (current reading)
  - problems      -> alerts.log (text) + alerts.jsonl (numbered, for the phone), rate-limited per kind
  - critical GPU  -> unloads the Ollama models (reversible), at most every 5 min
  - every 10 min  -> a STATUS line with the peaks since the last one
The apps server (proxai_apps_server.py) serves latest.json / alerts.jsonl to ProxMobile, which
shows the alerts as notifications and passes the stats to ProxAI-built apps with "pc-stats".
Stop: systemctl --user stop proxai-thermal-watch
"""
import csv, glob, json, os, subprocess, sys, time
from collections import deque
from datetime import datetime
from pathlib import Path

OUT = Path(os.environ.get("PROXAI_THERMAL_DIR", Path.home() / ".local/state/proxai/thermal-watch"))
OUT.mkdir(parents=True, exist_ok=True)
ALERTS = OUT / "alerts.log"
ALERTS_JSON = OUT / "alerts.jsonl"
LATEST = OUT / "latest.json"
KEEP_DAYS = 14
HEADER = ["time", "gpu_c", "gpu_w", "gpu_use", "vram_mib", "fan_pct", "sm_mhz", "throttle",
          "cpu_pkg_c", "cpu_core_max_c", "cpu_use", "ram_used_mb", "ram_avail_mb", "swap_mb"]

# Limits. RTX 5070 Ti: slows itself near ~90 C, power limit 300 W. i9-13900K: TjMax 100 C.
# Stricter than stock: this card (MSI) already lost GPU core 0 once and was repaired (Nathan, 2026-10-22).
GPU_WARN, GPU_CRIT = 75, 82
GPU_RISE = 10            # C within 10 s
GPU_POWER_WARN = 250     # W (limit 300 W; the local model peaked at ~83 W in testing)
# On a critical GPU reading, unload every Ollama model to take the load off (reversible: the next
# request loads it again). At most once every 5 minutes.
AUTO_UNLOAD = True
UNLOAD_EVERY = 300
_last_unload = 0.0


OLLAMA = os.environ.get("OLLAMA_HOST", "http://127.0.0.1:11434")
if not OLLAMA.startswith("http"):
    OLLAMA = "http://" + OLLAMA


def _ollama(path: str, body: dict | None = None) -> dict:
    import urllib.request
    req = urllib.request.Request(OLLAMA + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read() or b"{}")


def protect_gpu(reason: str):
    """Unload every loaded Ollama model (keep_alive 0) through Ollama's local API."""
    global _last_unload
    if not AUTO_UNLOAD or time.time() - _last_unload < UNLOAD_EVERY:
        return
    _last_unload = time.time()
    try:
        models = [m.get("name") or m.get("model") for m in _ollama("/api/ps").get("models", [])]
        for m in models:
            _ollama("/api/generate", {"model": m, "keep_alive": 0})
        done = ", ".join(models) or "nothing loaded"
    except Exception as exc:
        done = f"failed: {exc}"
    record_alert("CRIT", "protect", f"PROTECT unloaded local model ({done}) because {reason}")
CPU_WARN, CPU_CRIT = 85, 95   # package OR any single core (Nathan lost a core before)
CPU_RISE = 25            # C within 5 s
RAM_WARN_MB, RAM_CRIT_MB = 2048, 1024   # available memory
SWAP_JUMP_MB = 1024
# nvidia-smi clocks_event_reasons bits that mean the card is protecting itself.
THROTTLE = {0x8: "hardware slowdown", 0x20: "software thermal slowdown",
            0x40: "hardware thermal slowdown", 0x80: "power brake"}
COOLDOWN = {"ALERT": 180, "CRIT": 60}
STATUS_EVERY = 600

last_sent: dict[str, float] = {}


def _next_alert_id() -> int:
    try:
        with open(ALERTS_JSON, "rb") as f:
            f.seek(max(0, f.seek(0, 2) - 4096))
            last = f.read().decode("utf-8", "replace").strip().splitlines()[-1]
        return int(json.loads(last)["id"]) + 1
    except (OSError, ValueError, IndexError, KeyError):
        return 1


_alert_id = None


def record_alert(level: str, kind: str, msg: str) -> None:
    """One numbered alert for the phone (alerts.jsonl) + the text log (alerts.log)."""
    global _alert_id
    if _alert_id is None:
        _alert_id = _next_alert_id()
    now = datetime.now()
    with open(ALERTS_JSON, "a") as f:
        f.write(json.dumps({"id": _alert_id, "time": now.isoformat(timespec="seconds"),
                            "level": level, "kind": kind, "msg": msg}) + "\n")
    _alert_id += 1
    line = f"{now:%H:%M:%S} {level} {msg}"
    with open(ALERTS, "a") as f:
        f.write(line + "\n")
    print(line, flush=True)


def alert(level: str, kind: str, msg: str):
    now = time.time()
    if now - last_sent.get(kind, 0) < COOLDOWN[level]:
        return
    last_sent[kind] = now
    record_alert(level, kind, msg)


def coretemp_dir():
    for d in glob.glob("/sys/class/hwmon/hwmon*"):
        try:
            if open(f"{d}/name").read().strip() == "coretemp":
                return d
        except OSError:
            pass
    return None


CT = coretemp_dir()


def cpu_temps():
    pkg, hottest = None, None
    if not CT:
        return pkg, hottest
    for lab in glob.glob(f"{CT}/temp*_label"):
        try:
            name = open(lab).read().strip()
            val = int(open(lab.replace("_label", "_input")).read()) / 1000
        except (OSError, ValueError):
            continue
        if name.startswith("Package"):
            pkg = val
        elif hottest is None or val > hottest:
            hottest = val
    return pkg, hottest


_prev_cpu = None


def cpu_use():
    global _prev_cpu
    with open("/proc/stat") as f:
        v = list(map(int, f.readline().split()[1:]))
    idle, total = v[3] + v[4], sum(v)
    if _prev_cpu is None:
        _prev_cpu = (idle, total)
        return 0.0
    di, dt = idle - _prev_cpu[0], total - _prev_cpu[1]
    _prev_cpu = (idle, total)
    return 100.0 * (1 - di / dt) if dt else 0.0


def mem():
    m = {}
    with open("/proc/meminfo") as f:
        for line in f:
            k, v = line.split(":", 1)
            m[k] = int(v.split()[0]) // 1024
    return m["MemTotal"] - m["MemAvailable"], m["MemAvailable"], m["SwapTotal"] - m["SwapFree"]


def prune_old():
    cutoff = time.time() - KEEP_DAYS * 86400
    for f in OUT.glob("20*.csv"):
        try:
            if f.stat().st_mtime < cutoff:
                f.unlink()
        except OSError:
            pass


def write_latest(now, sample: dict, vram_total):
    """Current reading + the limits, for the phone (atomic replace, ~1 KB/s)."""
    data = {"time": now.isoformat(timespec="seconds"), **sample, "vram_total_mib": vram_total,
            "limits": {"gpu_warn_c": GPU_WARN, "gpu_crit_c": GPU_CRIT, "gpu_power_warn_w": GPU_POWER_WARN,
                       "cpu_warn_c": CPU_WARN, "cpu_crit_c": CPU_CRIT, "ram_warn_mb": RAM_WARN_MB}}
    tmp = LATEST.with_suffix(".tmp")
    try:
        tmp.write_text(json.dumps(data))
        os.replace(tmp, LATEST)
    except OSError:
        pass


def num(s):
    try:
        return float(s)
    except ValueError:
        return None


def main():
    fields = "temperature.gpu,power.draw,utilization.gpu,memory.used,memory.total,fan.speed,clocks.sm,clocks_event_reasons.active"
    smi = subprocess.Popen(["nvidia-smi", f"--query-gpu={fields}", "--format=csv,noheader,nounits", "-lms", "1000"],
                           stdout=subprocess.PIPE, text=True, bufsize=1)
    day, out, w = None, None, None
    gpu_hist, cpu_hist = deque(maxlen=11), deque(maxlen=6)
    swap0 = mem()[2]
    peak = {}
    last_status = time.time()
    line0 = f"{datetime.now():%H:%M:%S} START watching GPU/CPU/RAM every 1 s -> {OUT}"
    with open(ALERTS, "a") as f:
        f.write(line0 + "\n")
    print(line0, flush=True)

    for raw in smi.stdout:
        parts = [p.strip() for p in raw.split(",")]
        if len(parts) != 8:
            continue
        g_t, g_w, g_u, vram, vtot, fan, sm = (num(p) for p in parts[:7])
        reasons = int(parts[7], 16) if parts[7].startswith("0x") else 0
        pkg, core = cpu_temps()
        cu = cpu_use()
        used, avail, swap = mem()
        now = datetime.now()
        if now.date() != day:   # a new CSV every day; old ones pruned
            if out:
                out.close()
            day = now.date()
            path = OUT / f"{day:%Y-%m-%d}.csv"
            new = not path.exists()
            out = open(path, "a", newline="")
            w = csv.writer(out)
            if new:
                w.writerow(HEADER)
            prune_old()
        row = [f"{now:%H:%M:%S}", g_t, g_w, g_u, vram, fan, sm, hex(reasons),
               pkg, core, round(cu, 1), used, avail, swap]
        w.writerow(row)
        out.flush()
        write_latest(now, dict(zip(HEADER[1:], row[1:])), vtot)

        for k, v in (("gpu_c", g_t), ("gpu_w", g_w), ("gpu_use", g_u), ("cpu_c", pkg), ("cpu_use", cu)):
            if v is not None:
                peak[k] = max(peak.get(k, v), v)
        peak["ram_min_avail"] = min(peak.get("ram_min_avail", avail), avail)

        if g_t is not None:
            gpu_hist.append(g_t)
            if g_t >= GPU_CRIT:
                alert("CRIT", "gpu_t", f"GPU {g_t:.0f}C (crit {GPU_CRIT}C), {g_w:.0f} W, fan {fan:.0f}%")
                protect_gpu(f"GPU reached {g_t:.0f}C")
            elif g_t >= GPU_WARN:
                alert("ALERT", "gpu_t", f"GPU {g_t:.0f}C (warn {GPU_WARN}C), {g_w:.0f} W, fan {fan:.0f}%")
            # The Kanye voice makes ~3 s bursts at 130-180 W (45->57C is normal); only a jump that also
            # lands at 65C+ is worth a phone alert. The 75/82C limits still apply to every reading.
            if len(gpu_hist) > 1 and g_t - min(gpu_hist) >= GPU_RISE and g_t >= 65:
                alert("ALERT", "gpu_rise", f"GPU temp jumped {min(gpu_hist):.0f}->{g_t:.0f}C in 10 s ({g_w:.0f} W)")
            if g_t >= 70 and fan is not None and fan == 0:
                alert("CRIT", "gpu_fan", f"GPU {g_t:.0f}C but fan at 0% (fan problem?)")
                protect_gpu(f"GPU at {g_t:.0f}C with the fan at 0%")
        if g_w is not None and g_w >= GPU_POWER_WARN:
            alert("ALERT", "gpu_w", f"GPU power {g_w:.0f} W (limit 300 W), {g_t:.0f}C")
        for bit, name in THROTTLE.items():
            if reasons & bit:
                level = "CRIT" if bit in (0x40, 0x80) else "ALERT"
                alert(level, f"thr{bit}", f"GPU {name} active ({g_t:.0f}C, {g_w:.0f} W)")
                if level == "CRIT":
                    protect_gpu(f"GPU {name}")
        # Any single core (core 0 included), not just the package reading.
        if core is not None and core > (pkg or 0):
            if core >= CPU_CRIT:
                alert("CRIT", "cpu_core", f"a CPU core hit {core:.0f}C (crit {CPU_CRIT}C), package {pkg:.0f}C")
            elif core >= CPU_WARN:
                alert("ALERT", "cpu_core", f"a CPU core hit {core:.0f}C (warn {CPU_WARN}C), package {pkg:.0f}C")
        if pkg is not None:
            cpu_hist.append(pkg)
            if pkg >= CPU_CRIT:
                alert("CRIT", "cpu_t", f"CPU package {pkg:.0f}C (crit {CPU_CRIT}C), CPU use {cu:.0f}%")
            elif pkg >= CPU_WARN:
                alert("ALERT", "cpu_t", f"CPU package {pkg:.0f}C (warn {CPU_WARN}C), CPU use {cu:.0f}%")
            # Raptor Lake boosts one core for a second or two (e.g. 38->72C at 2% use); only a jump that
            # also lands high is worth an alert.
            if len(cpu_hist) > 1 and pkg - min(cpu_hist) >= CPU_RISE and pkg >= 80:
                alert("ALERT", "cpu_rise", f"CPU temp jumped {min(cpu_hist):.0f}->{pkg:.0f}C in 5 s (use {cu:.0f}%)")
        if avail <= RAM_CRIT_MB:
            alert("CRIT", "ram", f"RAM almost full: {avail} MB available")
        elif avail <= RAM_WARN_MB:
            alert("ALERT", "ram", f"RAM low: {avail} MB available")
        if swap - swap0 >= SWAP_JUMP_MB:
            alert("ALERT", "swap", f"swap grew by {swap - swap0} MB (now {swap} MB)")
            swap0 = swap

        if time.time() - last_status >= STATUS_EVERY:
            s = (f"{datetime.now():%H:%M:%S} STATUS last 10 min peaks: GPU {peak.get('gpu_c', 0):.0f}C "
                 f"{peak.get('gpu_w', 0):.0f} W {peak.get('gpu_use', 0):.0f}% use | CPU {peak.get('cpu_c', 0):.0f}C "
                 f"{peak.get('cpu_use', 0):.0f}% use | RAM min free {peak.get('ram_min_avail', 0)} MB | "
                 f"VRAM {vram:.0f}/{vtot:.0f} MiB")
            with open(ALERTS, "a") as f:
                f.write(s + "\n")
            print(s, flush=True)
            peak = {}
            last_status = time.time()

    msg = f"{datetime.now():%H:%M:%S} STOPPED nvidia-smi exited ({smi.wait()})"
    with open(ALERTS, "a") as f:
        f.write(msg + "\n")
    print(msg, flush=True)
    sys.exit(1)


if __name__ == "__main__":
    main()
