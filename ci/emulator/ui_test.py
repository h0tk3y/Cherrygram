#!/usr/bin/env python3
"""
Drives the Cherrygram test build on an emulator through the merged folder feed, from the user's
point of view, and saves a screenshot (plus a UI hierarchy dump) after every step.

It logs in to a test-server account prepared by tg_test_account.py (country code 999 switches a
debug build to the test servers), opens the "Feeds" folder, the pinned "All channels" entry, the
feed, a source channel, and checks live updates and the settings screen.

Results go to OUT/: NN_name.png, NN_name.xml, summary.md, logcat.txt. The script never aborts on a
failed step: it records it and goes on, so a single run shows as much as possible.
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
steps = []
counter = [0]


def adb(*args, check=False, capture=True, timeout=120):
    result = subprocess.run(["adb", *args], capture_output=capture, text=True, errors="replace", timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr}")
    return result.stdout if capture else ""


def shell(cmd, **kw):
    return adb("shell", cmd, **kw)


def snap(name):
    counter[0] += 1
    base = os.path.join(OUT, f"{counter[0]:02d}_{name}")
    with open(base + ".png", "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)
    xml = dump()
    if xml:
        with open(base + ".xml", "w") as f:
            f.write(xml)
    return base


def dump():
    shell("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    return adb("exec-out", "cat", "/sdcard/ui.xml")


def nodes():
    xml = dump()
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return []
    return list(root.iter("node"))


def bounds(node):
    m = re.findall(r"\d+", node.get("bounds", ""))
    return [int(v) for v in m] if len(m) == 4 else None


def center(node):
    b = bounds(node)
    return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2) if b else None


def find(texts, attr=("text", "content-desc"), contains=True, clazz=None):
    texts = [t.lower() for t in texts]
    for node in nodes():
        if clazz and node.get("class") != clazz:
            continue
        for a in attr:
            value = (node.get(a) or "").lower()
            if not value:
                continue
            if any((t in value) if contains else (t == value) for t in texts):
                return node
    return None


def tap(x, y):
    shell(f"input tap {x} {y}")


def tap_node(node):
    c = center(node)
    if c:
        tap(*c)
        return True
    return False


def wait_for(texts, timeout=30, **kw):
    end = time.time() + timeout
    while time.time() < end:
        node = find(texts, **kw)
        if node is not None:
            return node
        dismiss_system_dialogs()
        time.sleep(1.5)
    return None


def dismiss_system_dialogs():
    for label in ["Don’t allow", "Don't allow", "Not now", "NOT NOW", "Deny", "Cancel"]:
        node = find([label], contains=False)
        if node is not None and (node.get("package") or "").startswith(("com.android", "com.google.android.permissioncontroller")):
            tap_node(node)
            time.sleep(1)


def screen_size():
    m = re.search(r"(\d+)x(\d+)", shell("wm size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


def step(name, fn):
    started = time.time()
    try:
        ok, note = fn()
    except Exception as e:  # keep going: one run should show every screen it can reach
        ok, note = False, f"{type(e).__name__}: {e}"
    shot = snap(name)
    steps.append((name, ok, note, os.path.basename(shot) + ".png", time.time() - started))
    print(("PASS " if ok else "FAIL ") + name + (f" — {note}" if note else ""), flush=True)
    return ok


def write_prefs():
    prefs = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="AP_FolderChannelsFeed" value="true" />
    <int name="AP_FolderChannelsFeedEntry" value="2" />
</map>
"""
    local = os.path.join(OUT, "mainconfig.xml")
    with open(local, "w") as f:
        f.write(prefs)
    adb("push", local, "/data/local/tmp/mainconfig.xml", check=True)
    shell("chmod 644 /data/local/tmp/mainconfig.xml")
    shell(f"run-as {PKG} mkdir -p shared_prefs")
    shell(f"run-as {PKG} cp /data/local/tmp/mainconfig.xml shared_prefs/mainconfig.xml")
    return True


def main():
    global OUT, PKG
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--out", default=OUT)
    parser.add_argument("--account", help="directory written by tg_test_account.py setup")
    parser.add_argument("--package", default=PKG)
    args = parser.parse_args()
    OUT, PKG = args.out, args.package
    os.makedirs(OUT, exist_ok=True)

    adb("install", "-r", "-g", args.apk, check=True, timeout=600)
    shell("logcat -c")
    width, height = screen_size()
    phone = None
    code = None
    if args.account and os.path.exists(os.path.join(args.account, "phone.txt")):
        phone = open(os.path.join(args.account, "phone.txt")).read().strip()
        code_file = os.path.join(args.account, "code.txt")
        code = open(code_file).read().strip() if os.path.exists(code_file) else str(phone[5]) * 5

    step("prefs_enable_feature", lambda: (write_prefs(), "feature enabled, entry points: both"))

    def launch():
        shell(f"am start -W -n {PKG}/org.telegram.ui.LaunchActivity")
        node = wait_for(["Start Messaging", "Start messaging"], timeout=60)
        return node is not None, "intro screen" if node is not None else "intro button not found"
    step("launch", launch)

    if not phone:
        steps.append(("login", False, "no test account (see the 'Prepare test-server account' step: secrets missing or test-server sign-in failed): smoke test only", "", 0))
        finish()
        return

    def open_phone_screen():
        node = find(["Start Messaging", "Start messaging"])
        if node is not None:
            tap_node(node)
        time.sleep(3)
        dismiss_system_dialogs()
        edits = [n for n in nodes() if n.get("class") == "android.widget.EditText"]
        return len(edits) >= 2, f"{len(edits)} input fields"
    step("phone_screen", open_phone_screen)

    def enter_phone():
        edits = [n for n in nodes() if n.get("class") == "android.widget.EditText"]
        code_field, number_field = edits[0], edits[1]
        tap_node(code_field)
        for _ in range(6):
            shell("input keyevent 67")
        shell("input text 999")
        time.sleep(0.5)
        edits = [n for n in nodes() if n.get("class") == "android.widget.EditText"]
        tap_node(edits[1])
        for _ in range(12):
            shell("input keyevent 67")
        shell(f"input text {phone[3:]}")
        time.sleep(1)
        shell("input keyevent 66")
        time.sleep(2)
        confirm = find(["Yes", "Continue", "Done"], contains=False)
        if confirm is not None:
            tap_node(confirm)
        return True, f"+999 {phone[3:]}"
    step("phone_entered", enter_phone)

    def enter_code():
        time.sleep(4)
        dismiss_system_dialogs()
        shell("input text " + code)
        node = wait_for(["Feeds", "Chats", "Cherrygram", "Search"], timeout=60)
        return node is not None, "logged in" if node is not None else "main screen not detected"
    step("logged_in", enter_code)

    time.sleep(8)
    dismiss_system_dialogs()

    def open_folder():
        tab = find(["Feeds"], contains=False)
        if tab is not None:
            tap_node(tab)
        else:
            shell(f"input swipe {int(width * 0.85)} {int(height * 0.55)} {int(width * 0.1)} {int(height * 0.55)} 250")
        time.sleep(3)
        entry = find(["All channels"], contains=False)
        return entry is not None, "pinned entry visible" if entry is not None else "pinned entry NOT found"
    step("folder_with_pinned_entry", open_folder)

    def action_bar_button():
        node = find(["Read channels together"], attr=("content-desc",))
        return node is not None, "action bar button present" if node is not None else "action bar button NOT found"
    step("action_bar_button", action_bar_button)

    def open_feed():
        entry = find(["All channels"], contains=False)
        if entry is None:
            node = find(["Read channels together"], attr=("content-desc",))
            if node is None:
                return False, "no entry point to tap"
            tap_node(node)
        else:
            tap_node(entry)
        time.sleep(6)
        title = find(["3 channels"])
        text = dump()
        feed_ok = title is not None
        has_group = "must NOT appear" in text
        return feed_ok and not has_group, ("feed opened" if feed_ok else "feed header not found") + ("; GROUP MESSAGE LEAKED" if has_group else "")
    step("feed_opened", open_feed)

    def scroll_feed():
        shell(f"input swipe {width // 2} {int(height * 0.35)} {width // 2} {int(height * 0.8)} 400")
        time.sleep(3)
        return True, "scrolled to older posts"
    step("feed_scrolled_up", scroll_feed)

    def back_to_bottom():
        for _ in range(3):
            shell(f"input swipe {width // 2} {int(height * 0.8)} {width // 2} {int(height * 0.3)} 300")
            time.sleep(1)
        time.sleep(2)
        return True, ""
    step("feed_bottom", back_to_bottom)

    def live_update():
        if not args.account:
            return False, "no account"
        r = subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "tg_test_account.py"), "post", "--out", args.account],
                           capture_output=True, text=True, timeout=120)
        time.sleep(8)
        text = dump()
        return "Live update" in text, (r.stdout + r.stderr).strip()[-200:]
    step("live_update", live_update)

    def open_channel_from_post():
        # Tap the avatar of the newest post (drawn by the cell at its bottom-left corner).
        cells = [n for n in nodes() if bounds(n) and (n.get("class") or "").endswith("ViewGroup") and "post #" in (n.get("content-desc") or n.get("text") or "")]
        target = None
        if cells:
            target = max(cells, key=lambda n: bounds(n)[3])
            b = bounds(target)
            density = int(re.search(r"(\d+)", shell("wm density")).group(1)) / 160
            tap(int(b[0] + 30 * density), int(b[3] - 22 * density))
        else:
            tap(int(width * 0.06), int(height * 0.78))
        time.sleep(4)
        joined = find(["Mute", "Unmute", "subscribers", "subscriber", "Broadcast"])
        return joined is not None, "channel opened" if joined is not None else "channel screen not detected"
    step("channel_from_post", open_channel_from_post)

    def back_to_feed():
        shell("input keyevent 4")
        time.sleep(3)
        node = find(["3 channels"])
        return node is not None, "back in feed" if node is not None else "feed header not found after back"
    step("back_to_feed", back_to_feed)

    def channels_menu():
        node = find(["Channels in this feed"], attr=("content-desc",))
        if node is None:
            return False, "channels menu button not found"
        tap_node(node)
        time.sleep(2)
        dlg = find(["Feed Alpha"])
        ok = dlg is not None and find(["Feed Group"]) is None
        shell("input keyevent 4")
        return ok, "channel list shows only channels" if ok else "channel list wrong"
    step("channels_list", channels_menu)

    def settings():
        shell("input keyevent 4")
        time.sleep(1)
        shell(f"am start -a android.intent.action.VIEW -d tg://cg_settings/folders {PKG}")
        time.sleep(4)
        node = find(["Merged channels feed"])
        return node is not None, "settings toggle visible" if node is not None else "toggle not found"
    step("settings", settings)

    finish()


def finish():
    log = adb("logcat", "-d", timeout=120)
    with open(os.path.join(OUT, "logcat.txt"), "w") as f:
        f.write(log)
    crashes = [line for line in log.splitlines() if "FATAL EXCEPTION" in line or "AndroidRuntime: Process" in line]
    with open(os.path.join(OUT, "summary.md"), "w") as f:
        f.write("# Emulator UI test\n\n| step | result | note | screenshot |\n|---|---|---|---|\n")
        for name, ok, note, shot, _ in steps:
            f.write(f"| {name} | {'PASS' if ok else 'FAIL'} | {note} | {shot} |\n")
        f.write(f"\nCrashes in logcat: {len(crashes)}\n")
        for line in crashes[:20]:
            f.write(f"    {line}\n")
    print(open(os.path.join(OUT, "summary.md")).read())


if __name__ == "__main__":
    main()
