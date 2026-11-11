#!/usr/bin/env python3
"""
Smoke test of the Cherrygram test build on an emulator: installs the APK, launches it, waits for the
intro screen and saves a screenshot, a UI hierarchy dump and the logcat.

Signing in (and so reaching the merged folder feed) is not possible on CI: Telegram's test servers
reject the login codes. The earlier login-driven test lives in git history (ci/emulator/
tg_test_account.py and the long version of this script) if that ever changes.

Fails (exit code 1) when the app does not reach its intro screen or crashes. Results go to OUT/:
NN_name.png, NN_name.xml, summary.md, logcat.txt.
"""
import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "uz.unnarsx.cherrygram.beta"
OUT = "ci-out"


def adb(*args, check=False, timeout=120):
    result = subprocess.run(["adb", *args], capture_output=True, text=True, errors="replace", timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr}")
    return result.stdout


def dump():
    # Remove the previous dump first: a failed dump must not return stale content.
    adb("shell", "rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    return adb("exec-out", "cat", "/sdcard/ui.xml")


def snap(index, name):
    base = os.path.join(OUT, f"{index:02d}_{name}")
    with open(base + ".png", "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)
    with open(base + ".xml", "w") as f:
        f.write(dump())
    return os.path.basename(base) + ".png"


# Buttons of system dialogs that can cover the app on a fresh emulator (permission prompts, "isn't
# responding"): tapped so they do not hide the screen being waited for.
SYSTEM_DIALOG_BUTTONS = {"wait", "don't allow", "don’t allow", "deny", "not now", "cancel"}


def dismiss_system_dialogs(root):
    for node in root.iter("node"):
        package = node.get("package") or ""
        text = (node.get("text") or "").lower()
        if text in SYSTEM_DIALOG_BUTTONS and package.startswith(("android", "com.android", "com.google.android")):
            m = re.findall(r"\d+", node.get("bounds", ""))
            if len(m) == 4:
                x1, y1, x2, y2 = map(int, m)
                adb("shell", f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
                time.sleep(1)
                return


def wait_for_text(text, timeout):
    """Waits until a view whose text contains `text` (case-insensitive) is on screen."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            root = ET.fromstring(dump())
        except ET.ParseError:
            root = None
        if root is not None:
            if any(text.lower() in (node.get("text") or "").lower() for node in root.iter("node")):
                return True
            dismiss_system_dialogs(root)
        time.sleep(2)
    return False


def main():
    global OUT, PKG
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--out", default=OUT)
    parser.add_argument("--package", default=PKG)
    args = parser.parse_args()
    OUT, PKG = args.out, args.package
    os.makedirs(OUT, exist_ok=True)

    adb("install", "-r", "-g", args.apk, check=True, timeout=600)
    # The first launch on the software-rendered emulator can be slow enough for an "isn't responding"
    # dialog. While it is shown, uiautomator dumps fail, so the screen behind it could not be checked:
    # hide such dialogs (Settings.Global.HIDE_ERROR_DIALOGS). Crashes are still caught from logcat, and
    # ANRs are reported in the summary.
    adb("shell", "settings put global hide_error_dialogs 1")
    adb("shell", "logcat -c")
    adb("shell", f"am start -W -n {PKG}/org.telegram.ui.LaunchActivity")
    launched = wait_for_text("Start Messaging", timeout=150)
    shot = snap(1, "launch")

    log = adb("logcat", "-d", timeout=120)
    with open(os.path.join(OUT, "logcat.txt"), "w") as f:
        f.write(log)
    # Only crashes of the app itself: other processes do crash on emulators.
    crashes = [line for line in log.splitlines() if f"Process: {PKG}," in line]
    anrs = [line for line in log.splitlines() if f"ANR in {PKG}" in line]

    with open(os.path.join(OUT, "summary.md"), "w") as f:
        f.write("# Emulator smoke test\n\n| step | result | screenshot |\n|---|---|---|\n")
        f.write(f"| launch to intro screen | {'PASS' if launched else 'FAIL'} | {shot} |\n")
        f.write(f"\nCrashes of {PKG} in logcat: {len(crashes)}\n")
        f.write(f"\"Not responding\" reports (ANR) of {PKG}: {len(anrs)} (not a failure: slow emulator)\n")
        for line in crashes[:20]:
            f.write(f"    {line}\n")
    print(open(os.path.join(OUT, "summary.md")).read())
    return 0 if launched and not crashes else 1


if __name__ == "__main__":
    sys.exit(main())
