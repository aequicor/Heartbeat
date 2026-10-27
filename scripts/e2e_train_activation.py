# -*- coding: utf-8 -*-
"""Checks the platform launcher of a packaged macOS build on a throwaway data directory.

usage: e2e_train_activation.py <Heartbeat.app> good|broken

good:   the seed train starts, a queued train (the shell jar with a marker file) is activated at the next launch and the
        classes of the shell load from its jar in the store, not from the old one.
broken: a queued train without the application entry point crashes twice, then the launcher rolls back to the seed
        train and marks the broken one bad.
Build the app first: ./gradlew :platform-main:desktop:createReleaseDistributable
Uses the data name HeartbeatTrainCheck, so it never touches a real profile; starts the app a few times (windows open).
"""
import hashlib, json, os, shutil, subprocess, sys, tempfile, time, zipfile
from pathlib import Path

APP = Path(sys.argv[1]) / "Contents/MacOS/Heartbeat"
SCRATCH = Path(tempfile.mkdtemp(prefix="hb-train-"))
home = Path.home() / "Library/Application Support"
data, root = home / "HeartbeatTrainCheck", home / "HeartbeatTrainCheck-launcher"
mode = sys.argv[2]  # "good" or "broken"

def run(seconds, xlog=None):
    opts = "-Dheartbeat.data.name=HeartbeatTrainCheck"
    if xlog:
        opts += f" -Xlog:class+load:file={xlog}"
    p = subprocess.Popen([str(APP)], env={**os.environ, "JAVA_TOOL_OPTIONS": opts},
                         stdout=open(str(SCRATCH / "out.log"), "w"), stderr=subprocess.STDOUT)
    time.sleep(seconds)
    alive = p.poll() is None
    if alive:
        p.terminate()
        try:
            p.wait(10)
        except subprocess.TimeoutExpired:
            p.kill()
    return alive

def state():
    return dict(l.split("=", 1) for l in (root / "state.properties").read_text().splitlines() if "=" in l and not l.startswith("#"))

for d in (data, root):
    shutil.rmtree(d, ignore_errors=True)
print("first launch alive:", run(25))
print("after first:", {k: state()[k] for k in ("current", "previous", "crashes")})

seed = json.loads((root / "trains/seed-1.0.0/manifest.json").read_text())
shell = next(b for b in seed["bundles"] if b["id"] == "shell")
src = root / "store" / (shell["sha256"] + ".jar")
tmp = SCRATCH / "shell2.jar"
shutil.copy(src, tmp)
with zipfile.ZipFile(tmp, "a") as z:
    z.writestr("hb-train-marker.txt", "train2")
if mode == "broken":
    # a foundation without the application: starting it fails, the launcher must roll back
    f = next(b for b in seed["bundles"] if b["id"] == "foundation")
    tmp = SCRATCH / "foundation2.jar"
    with zipfile.ZipFile(root / "store" / (f["sha256"] + ".jar")) as zin, zipfile.ZipFile(tmp, "w") as zout:
        for e in zin.infolist():
            if "platform/desktop/MainKt" not in e.filename:
                zout.writestr(e, zin.read(e))
    target = f
else:
    target = shell
sha = hashlib.sha256(tmp.read_bytes()).hexdigest()
shutil.copy(tmp, root / "store" / (sha + ".jar"))
new = json.loads(json.dumps(seed))
new["train"] = "t2check"
for b in new["bundles"]:
    if b["id"] == target["id"]:
        b["sha256"] = sha
pending = root / "pending/t2check"
pending.mkdir(parents=True)
(pending / "manifest.json").write_text(json.dumps(new))

log = SCRATCH / "classload.log"
log.unlink(missing_ok=True)
print("second launch alive:", run(30, str(log)))
st = state()
print("after second:", {k: st[k] for k in ("current", "previous", "crashes", "bad", "rollbackRequested")})
text = log.read_text(errors="ignore") if log.exists() else ""
print("classes loaded from new jar:", text.count(sha), " from old jar:", text.count(target["sha256"]))
if mode == "broken":
    for n in (3, 4):
        print(f"launch {n} alive:", run(20))
        st = state()
        print(f"after launch {n}:", {k: st[k] for k in ("current", "previous", "crashes", "bad", "rollbackRequested")})
for d in (data, root):
    shutil.rmtree(d, ignore_errors=True)
