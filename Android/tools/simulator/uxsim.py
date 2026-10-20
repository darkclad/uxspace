#!/usr/bin/env python3
"""UxSpace simulator companion — fake the glasses from the PC (docs/SIMULATOR.md).

    uxsim.py setup [WxH/DPI] [--overlay]
                               add the fake glasses display (default 1920x1080/320) + adb
                               forward. Uses the emulator's own multi-display (a separate
                               window on the PC); --overlay uses Android's overlay display
                               instead (also works on a real device, but floats over the
                               phone screen)
    uxsim.py teardown          remove the fake display and the forward
    uxsim.py pair              turn on Wireless debugging and pair the app's privileged
                               helper (drives Settings over uiautomator; app must be running)
    uxsim.py send CMD ...      one command to the app's simulator bridge, e.g.
                               `send pose 30 -10`, `send mode free`, `send click`
    uxsim.py [gui]             head-pose pad: drag / arrow keys to look around

Talks to the debug build's SimulatorBridge (app/src/debug) on device port 47800 via
`adb forward`. Stdlib only. Honours ANDROID_SERIAL like adb does.
"""
import os
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

PORT = 47800
DEFAULT_OVERLAY = "1920x1080/320"


def find_adb():
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    exe = "adb.exe" if os.name == "nt" else "adb"
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    props = Path(__file__).resolve().parents[2] / "local.properties"
    if props.exists():
        m = re.search(r"^sdk\.dir=(.*)$", props.read_text(), re.M)
        if m:
            roots.append(m.group(1).replace("\\:", ":").replace("\\\\", "\\"))
    for root in filter(None, roots):
        cand = Path(root) / "platform-tools" / exe
        if cand.exists():
            return str(cand)
    return shutil.which("adb") or "adb"


ADB = find_adb()


def adb(*args, check=True):
    return subprocess.run([ADB, *args], check=check, capture_output=True, text=True).stdout


def setup(size=DEFAULT_OVERLAY, overlay=False):
    if overlay:
        adb("shell", "settings", "put", "global", "overlay_display_devices", size)
        kind = "overlay display"
    else:
        w, h, dpi = re.fullmatch(r"(\d+)x(\d+)/(\d+)", size).groups()
        out = adb("emu", "multidisplay", "add", "1", w, h, dpi, "0", check=False)
        if "OK" not in out:
            sys.exit(f"`adb emu multidisplay` failed ({out.strip() or 'not an emulator?'}) "
                     "- retry with --overlay")
        kind = "emulator display 1"
    adb("forward", f"tcp:{PORT}", f"tcp:{PORT}")
    print(f"{kind} {size} on, tcp:{PORT} forwarded")


def teardown():
    adb("emu", "multidisplay", "del", "1", check=False)
    adb("shell", "settings", "delete", "global", "overlay_display_devices", check=False)
    adb("forward", "--remove", f"tcp:{PORT}", check=False)
    print("fake display and forward removed")


def ui_nodes():
    """(text, (cx, cy)) for every node with text on the current screen."""
    adb("shell", "uiautomator", "dump", "/sdcard/uxsim-ui.xml", check=False)
    xml = adb("shell", "cat", "/sdcard/uxsim-ui.xml", check=False)
    nodes = []
    for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        x1, y1, x2, y2 = map(int, m.group(2, 3, 4, 5))
        nodes.append((m.group(1), ((x1 + x2) // 2, (y1 + y2) // 2)))
    return nodes


def tap_text(text, scrolls=8, waits=5):
    """Tap the first node whose text is exactly `text`. Polls `waits` times for it to
    appear, then scrolls down up to `scrolls` times looking for it."""
    for attempt in range(waits + scrolls):
        for t, (x, y) in ui_nodes():
            if t == text:
                adb("shell", "input", "tap", str(x), str(y))
                time.sleep(1.5)
                return True
        if attempt >= waits:
            adb("shell", "input", "swipe", "500", "900", "500", "300", "300")
        time.sleep(0.8)
    return False


def tap_id(resource_id):
    adb("shell", "uiautomator", "dump", "/sdcard/uxsim-ui.xml", check=False)
    xml = adb("shell", "cat", "/sdcard/uxsim-ui.xml", check=False)
    m = re.search(rf'resource-id="{re.escape(resource_id)}"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(1.5)
    return m is not None


def pair():
    """Enable Wireless debugging and hand its pairing code to the app over the bridge."""
    # The overlay display's window floats over the primary screen, and SystemUI's "allow
    # wireless debugging" prompt drops taps while anything obscures it — hide the overlay
    # while pairing.
    overlay = adb("shell", "settings", "get", "global", "overlay_display_devices").strip()
    adb("shell", "settings", "delete", "global", "overlay_display_devices", check=False)
    try:
        _pair()
    finally:
        if overlay and overlay != "null":
            adb("shell", "settings", "put", "global", "overlay_display_devices", overlay)


def _pair():
    adb("shell", "settings", "put", "global", "development_settings_enabled", "1")
    # Start Settings fresh so it opens at the top of Developer options, not a stale subpage.
    adb("shell", "am", "force-stop", "com.android.settings")
    adb("shell", "am", "start", "-a", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
    time.sleep(2)
    if not tap_text("Wireless debugging"):
        sys.exit("could not find 'Wireless debugging' in Developer options")
    texts = [t for t, _ in ui_nodes()]
    if "Pair device with pairing code" not in texts:
        # Off: flip the switch and accept the "allow on this network" prompt. The switch
        # itself is the tap target, not its label.
        tap_id("android:id/switch_widget")
        tap_text("Always allow on this network", scrolls=0, waits=3)
        tap_text("Allow", scrolls=0)
    if not tap_text("Pair device with pairing code", scrolls=0):
        sys.exit("Wireless debugging did not turn on")
    code = next((t for t, _ in ui_nodes() if re.fullmatch(r"\d{6}", t)), None)
    if code is None:
        sys.exit("no pairing code on screen")
    print(f"pairing with code {code} ...")
    reply = Bridge().send(f"pair {code}")
    print(reply)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    if not reply.startswith("ok"):
        sys.exit(1)
    # The helper starts as soon as pairing lands; bring the app back to the front.
    adb("shell", "am", "start", "-n", "com.uxspace/.MainActivity")


class Bridge:
    """A persistent connection to the app; reconnects (re-forwarding) on failure."""

    def __init__(self):
        self.sock = None
        self.file = None

    def _connect(self):
        adb("forward", f"tcp:{PORT}", f"tcp:{PORT}", check=False)
        self.sock = socket.create_connection(("127.0.0.1", PORT), timeout=2)
        self.sock.settimeout(40)  # `pair` replies only once pairing finishes
        self.file = self.sock.makefile("rw", encoding="utf-8", newline="\n")

    def send(self, line):
        for attempt in (1, 2):
            try:
                if self.sock is None:
                    self._connect()
                self.file.write(line + "\n")
                self.file.flush()
                reply = self.file.readline().strip()
                if not reply:
                    raise ConnectionError("bridge closed the connection")
                return reply
            except OSError as e:
                self.close()
                if attempt == 2:
                    return f"err no bridge ({e}) - debug build installed and running?"

    def close(self):
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass
        self.sock = None
        self.file = None


def gui():
    import tkinter as tk

    bridge = Bridge()
    state = {"yaw": 0.0, "pitch": 0.0, "roll": 0.0, "free": True, "drag": None}

    root = tk.Tk()
    root.title("UxSpace head pose")
    canvas = tk.Canvas(root, width=520, height=300, bg="#1e1f24", highlightthickness=0)
    canvas.pack(fill="both", expand=True)
    status = tk.StringVar()
    tk.Label(root, textvariable=status, anchor="w", font=("Consolas", 10)).pack(fill="x")
    tk.Label(
        root,
        anchor="w",
        justify="left",
        font=("Segoe UI", 9),
        text="Drag (left button) or arrows = look   Shift = fine   Q/E = roll   "
        "0 = look ahead\nF = FREE/PINNED   R = recenter   L = next layout   "
        "right-drag = move cursor   Space = click",
    ).pack(fill="x")

    def draw():
        canvas.delete("all")
        w, h = canvas.winfo_width(), canvas.winfo_height()
        # 360° yaw across the width, ±90° pitch across the height; the box is a ~45° FOV.
        cx = w / 2 + state["yaw"] / 360 * w
        cy = h / 2 - state["pitch"] / 180 * h
        bw, bh = w * 45 / 360, h * 25 / 180
        canvas.create_line(w / 2, 0, w / 2, h, fill="#3a3d46")
        canvas.create_line(0, h / 2, w, h / 2, fill="#3a3d46")
        canvas.create_rectangle(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2,
                                outline="#5aa9ff", width=2)
        canvas.create_text(8, 8, anchor="nw", fill="#9aa0aa", font=("Consolas", 10),
                           text=f"yaw {state['yaw']:+6.1f}  pitch {state['pitch']:+5.1f}  "
                                f"roll {state['roll']:+5.1f}  {'FREE' if state['free'] else 'PINNED'}")

    def send(line):
        reply = bridge.send(line)
        status.set(f"{line}  →  {reply}")
        return reply

    def push_pose():
        state["yaw"] = (state["yaw"] + 180) % 360 - 180
        state["pitch"] = max(-89.0, min(89.0, state["pitch"]))
        send(f"pose {state['yaw']:.2f} {state['pitch']:.2f} {state['roll']:.2f}")
        draw()

    def nudge(dyaw=0.0, dpitch=0.0, droll=0.0):
        state["yaw"] += dyaw
        state["pitch"] += dpitch
        state["roll"] += droll
        push_pose()

    def on_key(ev):
        step = 1.0 if ev.state & 0x1 else 5.0
        k = ev.keysym.lower()
        if k == "left": nudge(dyaw=-step)
        elif k == "right": nudge(dyaw=step)
        elif k == "up": nudge(dpitch=step)
        elif k == "down": nudge(dpitch=-step)
        elif k == "q": nudge(droll=-step)
        elif k == "e": nudge(droll=step)
        elif k == "0":
            state.update(yaw=0.0, pitch=0.0, roll=0.0)
            push_pose()
        elif k == "f":
            state["free"] = not state["free"]
            send(f"mode {'free' if state['free'] else 'pinned'}")
            draw()
        elif k == "r": send("recenter")
        elif k == "l": send("layout")
        elif k == "space": send("click")

    def on_press(ev):
        state["drag"] = (ev.x, ev.y)

    def on_drag(ev):
        x0, y0 = state["drag"]
        state["drag"] = (ev.x, ev.y)
        nudge(dyaw=(ev.x - x0) * 0.3, dpitch=-(ev.y - y0) * 0.3)

    def on_cursor_drag(ev):
        x0, y0 = state["drag"]
        state["drag"] = (ev.x, ev.y)
        send(f"move {(ev.x - x0) / 520:.4f} {(ev.y - y0) / 300:.4f}")

    root.bind("<Key>", on_key)
    canvas.bind("<ButtonPress-1>", on_press)
    canvas.bind("<B1-Motion>", on_drag)
    canvas.bind("<ButtonPress-3>", on_press)
    canvas.bind("<B3-Motion>", on_cursor_drag)
    canvas.bind("<Configure>", lambda _: draw())

    # Start looking straight ahead with the scene world-fixed, so the pose has an effect.
    root.after(100, lambda: (push_pose(), send("mode free")))
    root.mainloop()
    bridge.close()


def main(argv):
    cmd = argv[1] if len(argv) > 1 else "gui"
    if cmd == "setup":
        args = [a for a in argv[2:] if a != "--overlay"]
        setup(args[0] if args else DEFAULT_OVERLAY, overlay="--overlay" in argv)
    elif cmd == "teardown":
        teardown()
    elif cmd == "pair":
        pair()
    elif cmd == "send" and len(argv) > 2:
        reply = Bridge().send(" ".join(argv[2:]))
        print(reply)
        return 0 if reply.startswith("ok") else 1
    elif cmd == "gui":
        gui()
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
