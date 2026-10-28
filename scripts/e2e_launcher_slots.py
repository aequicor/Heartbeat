"""Checks the launcher slots (A/B) of a packaged macOS build on a throwaway data directory.

Build the app with throwaway keys first, never with the release keys. Keep the key directory outside the repository and
unset a release key in the shell, so that the build cannot sign with it:
  unset HEARTBEAT_LAUNCHER_SIGNING_KEY
  java scripts/e2e_launcher_slots/Keys.java <keys dir>
  HEARTBEAT_LAUNCHER_SIGNING_KEY="$(cat <keys dir>/primary.priv)" ./gradlew \\
      -Pheartbeat.trustedKeysFile=<keys dir>/trusted-keys.txt :platform-main:desktop:createReleaseDistributable
Then: python3 scripts/e2e_launcher_slots.py <Heartbeat.app> <keys dir>
The script refuses an app whose selector does not trust exactly the keys of <keys dir>.

The seed train of the app is signed with the primary key and the launcher selector trusts the primary and backup keys.
The scenario starts the app a few times (windows open) on the data name HeartbeatTrainCheck, so it never touches a real
profile, and checks from the state files and from the JVM class-load log which launcher actually ran:
  1. a fresh install runs the embedded launcher, which stages the launcher of the seed train into slot A (trial)
  2. the next start runs slot A and confirms it
  3. a newer signed launcher in a queued train goes to slot B and runs after confirmation
  4. an older signed launcher, and one signed by a foreign key, are not staged (the queued train is activated first,
     so that the refusal is about the launcher and not about a train that never arrived)
  5. a signed launcher that cannot start is abandoned and the confirmed slot runs in the same process
Signing uses `java` (scripts/e2e_launcher_slots/Sign.java), which writes the same record as build-logic.
"""
import hashlib, json, os, shutil, subprocess, sys, tempfile, time, zipfile
from pathlib import Path

if len(sys.argv) != 3:
    sys.exit("usage: e2e_launcher_slots.py <Heartbeat.app> <keys dir>")
APP_DIR = Path(sys.argv[1])
APP = APP_DIR / "Contents/MacOS/Heartbeat"
KEYS = Path(sys.argv[2])
HELPER = Path(__file__).resolve().parent / "e2e_launcher_slots"
SCRATCH = Path(tempfile.mkdtemp(prefix="hb-slots-"))
home = Path.home() / "Library/Application Support"
data, root = home / "HeartbeatTrainCheck", home / "HeartbeatTrainCheck-launcher"
slots = root / "slots"
failures = []
RUN_LIMIT = 90   # seconds a start may take before the expected state is missed
POLL = 0.5


def check(condition, message):
    print(("  ok   " if condition else "  FAIL ") + message)
    if not condition:
        failures.append(message)


def key_lines(text):
    return [line.strip() for line in text.splitlines() if line.strip() and not line.strip().startswith("#")]


def trusts_exactly_the_keys_of_the_directory():
    """The selector of the app must carry the throwaway keys: an app built with other (release) keys is refused."""
    jar = APP_DIR / "Contents/app/launcher-selector.jar"
    with zipfile.ZipFile(jar) as z:
        built = key_lines(z.read("io/aequicor/heartbeat/selector/trusted-keys.txt").decode())
    return built == key_lines((KEYS / "trusted-keys.txt").read_text())


def props(path):
    # Java .properties escapes (\:, \=, \\) are not undone: the values read here are plain names and hashes
    if not path.exists():
        return {}
    return dict(l.split("=", 1) for l in path.read_text().splitlines() if "=" in l and not l.startswith("#"))


def slot_state():
    return props(slots / "state.properties")


def current_train():
    return props(root / "state.properties").get("current", "")


def sha_of(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def launcher_source(base, pid):
    """Source of LauncherMain in the class-load log of the process [pid] (a child JVM has a log of its own)."""
    log = Path(f"{base}-{pid}.log")
    if not log.exists():
        return ""
    for line in log.read_text(errors="ignore").splitlines():
        if "io.aequicor.heartbeat.launcher.LauncherMain source:" in line:
            return line.split("source:", 1)[1].strip()
    return ""


def run(number, ready, settle=0):
    """Starts the app until [ready] holds (or [RUN_LIMIT] passes), lets it [settle] seconds, then stops it.

    Returns (alive when the state was reached, source of LauncherMain of the app process)."""
    base = SCRATCH / f"classload-{number}"
    opts = f"-Dheartbeat.data.name=HeartbeatTrainCheck -Xlog:class+load:file={base}-%p.log"
    with open(SCRATCH / f"out-{number}.log", "w") as out:
        p = subprocess.Popen([str(APP)], env={**os.environ, "JAVA_TOOL_OPTIONS": opts},
                             stdout=out, stderr=subprocess.STDOUT)
        deadline = time.time() + RUN_LIMIT
        reached = False
        while time.time() < deadline and p.poll() is None:
            if ready():
                reached = True
                break
            time.sleep(POLL)
        if reached and settle:
            time.sleep(settle)
        alive = p.poll() is None
        if alive:
            p.terminate()
            try:
                p.wait(10)
            except subprocess.TimeoutExpired:
                p.kill()
    if not reached:
        print(f"  (the expected state of start {number} was not reached)")
    return alive and reached, launcher_source(base, p.pid)


def sign(key, entry):
    out = subprocess.run(["java", str(HELPER / "Sign.java"), str(KEYS / key), entry["id"], entry["version"],
                          entry["sha256"], entry["kind"], str(entry["launcherApi"])],
                         check=True, capture_output=True, text=True)
    return out.stdout.strip()


def queue(name, seed, jar_bytes, level, key):
    """Queues a train that differs from the seed only by its launcher entry."""
    sha = hashlib.sha256(jar_bytes).hexdigest()
    (root / "store" / (sha + ".jar")).write_bytes(jar_bytes)
    new = json.loads(json.dumps(seed))
    new["train"] = name
    entry = {"id": "launcher", "version": name, "jar": f"bundle-launcher-{name}.jar", "sha256": sha,
             "kind": "launcher", "launcherApi": level, "dependencies": []}
    entry["signature"] = sign(key, entry)
    new["bundles"] = [b for b in new["bundles"] if b["id"] != "launcher"] + [entry]
    pending = root / "pending" / name
    pending.mkdir(parents=True)
    (pending / "manifest.json").write_text(json.dumps(new))
    return sha


def with_marker(jar, marker):
    out = SCRATCH / f"launcher-{marker}.jar"
    shutil.copy(jar, out)
    with zipfile.ZipFile(out, "a") as z:
        z.writestr("hb-launcher-marker.txt", marker)
    return out.read_bytes()


def empty_jar():
    out = SCRATCH / "empty.jar"
    with zipfile.ZipFile(out, "w") as z:
        z.writestr("README.txt", "a signed jar without a launcher")
    return out.read_bytes()


def scenario():
    print("1. fresh install: the embedded launcher stages slot A")
    alive, source = run(1, lambda: slot_state().get("active") == "A" and (slots / "A" / "slot.properties").exists())
    st = slot_state()
    check(alive, "the app runs")
    check(source.endswith("launcher-embedded.seed"), f"the embedded launcher ran ({source})")
    check(st.get("active") == "A" and st.get("confirmed") == "", f"slot A is staged as a trial ({st})")
    check((slots / "A" / "launcher.jar").exists(), "slots/A/launcher.jar exists")
    seed_train = current_train()
    seed = json.loads((root / "trains" / seed_train / "manifest.json").read_text())
    check(any(b["id"] == "launcher" and b.get("signature") for b in seed["bundles"]), "the seed launcher entry is signed")
    base_jar = slots / "A" / "launcher.jar"

    print("2. slot A runs and is confirmed")
    alive, source = run(2, lambda: slot_state().get("confirmed") == "A")
    st = slot_state()
    check(alive, "the app runs")
    check("/slots/A/" in source, f"LauncherMain loaded from slot A ({source})")
    check(st.get("confirmed") == "A" and st.get("launching") == "false" and st.get("floor") == "1",
          f"slot A is confirmed, floor 1 ({st})")

    print("3. a newer signed launcher goes to slot B")
    l2 = with_marker(base_jar, "level2")
    queue("t2check", seed, l2, 2, "primary.priv")
    alive, source = run(3, lambda: slot_state().get("active") == "B" and (slots / "B" / "slot.properties").exists())
    st = slot_state()
    check(alive, "the app runs")
    check("/slots/A/" in source, f"the confirmed slot A ran ({source})")
    check(current_train() == "t2check", "the queued train was activated")
    check(st.get("active") == "B" and st.get("confirmed") == "A", f"slot B is staged as a trial ({st})")
    check((slots / "B" / "launcher.jar").exists()
          and sha_of(slots / "B" / "launcher.jar") == hashlib.sha256(l2).hexdigest(), "slot B holds the new launcher")
    alive, source = run(4, lambda: slot_state().get("confirmed") == "B")
    st = slot_state()
    check(alive, "the app runs")
    check("/slots/B/" in source, f"LauncherMain loaded from slot B ({source})")
    check(st.get("confirmed") == "B" and st.get("floor") == "2", f"slot B is confirmed, floor 2 ({st})")

    print("4. an older and a foreign-signed launcher are not staged")
    a_before = sha_of(slots / "A" / "launcher.jar")
    queue("t3old", seed, with_marker(base_jar, "level1again"), 1, "primary.priv")
    alive, source = run(5, lambda: current_train() == "t3old", settle=10)
    st = slot_state()
    check(alive and "/slots/B/" in source, f"slot B ran ({source})")
    check(current_train() == "t3old", "the train with the older launcher was activated and offered")
    check(st.get("active") == "B" and sha_of(slots / "A" / "launcher.jar") == a_before,
          "the older launcher was not staged")
    queue("t4foreign", seed, with_marker(base_jar, "foreign"), 5, "foreign.priv")
    alive, source = run(6, lambda: current_train() == "t4foreign", settle=10)
    st = slot_state()
    check(alive and "/slots/B/" in source, f"slot B ran ({source})")
    check(current_train() == "t4foreign", "the train with the foreign-signed launcher was activated and offered")
    check(st.get("active") == "B" and sha_of(slots / "A" / "launcher.jar") == a_before,
          "the launcher signed by a foreign key was not staged")

    print("5. a signed launcher that cannot start is abandoned")
    broken = empty_jar()
    queue("t5broken", seed, broken, 6, "primary.priv")
    alive, source = run(7, lambda: slot_state().get("active") == "A" and current_train() == "t5broken")
    st = slot_state()
    check(alive and "/slots/B/" in source, f"slot B ran ({source})")
    check(st.get("active") == "A" and st.get("confirmed") == "B",
          f"the broken launcher is staged in A as a trial ({st})")
    broken_sha = hashlib.sha256(broken).hexdigest()
    alive, source = run(8, lambda: broken_sha in slot_state().get("bad", ""), settle=5)
    st = slot_state()
    check(alive, "the app runs although the trial slot cannot start")
    check("/slots/B/" in source, f"the confirmed slot B ran in the same process ({source})")
    check(st.get("active") == "B" and broken_sha in st.get("bad", ""),
          f"slot A is rejected and its jar remembered ({st})")


try:
    if not trusts_exactly_the_keys_of_the_directory():
        sys.exit("The selector of the app does not trust exactly the keys of " + str(KEYS)
                 + ": build the app with -Pheartbeat.trustedKeysFile=<keys dir>/trusted-keys.txt (never release keys)")
    for d in (data, root):
        shutil.rmtree(d, ignore_errors=True)
    scenario()
finally:
    for d in (data, root, SCRATCH):
        shutil.rmtree(d, ignore_errors=True)
print("\nFAILED: " + "; ".join(failures) if failures else "\nALL CHECKS PASSED")
sys.exit(1 if failures else 0)
