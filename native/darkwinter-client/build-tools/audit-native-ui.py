"""Bounded native DarkWinter UI audit; never opens or creates a world.

Windows usage:
  python audit-native-ui.py --phase smoke --report build/core/ui-audit-smoke
  python audit-native-ui.py --phase settings --report build/core/ui-audit-settings
  python audit-native-ui.py --phase menus --report build/core/ui-audit-menus
  python audit-native-ui.py --report build/core/ui-audit-settings --export-label p17-language-top

The executable, preferences, saves and evidence stay under this project's build/core.
A fresh report directory is required. Physical PNGs are checked as 2560x1440;
the logical GUI is checked as 854x480 (scale 3). The window uses fullscreen on
this 2560x1440 reference monitor. No image is resized or annotated. Evidence PNGs
only repack IDAT losslessly; original PNGs and before/after RGBA hashes are kept.
The report records navigation/scroll coverage, not a claim of visual equality.
"""
import argparse
import base64
from collections import deque
import hashlib
import json
import math
import os
import re
from pathlib import Path
import struct
import subprocess
import tempfile
import time
import traceback
import zlib

ROOT = Path(__file__).resolve().parents[3]
BUILD = ROOT / "build/core"
SIZE = (2560, 1440)
GUI_SIZE = (854, 480)
CHUNK_BYTES = 24 * 1024
MAX_PNG = 32 * 1024 * 1024
MAX_EVIDENCE = 2 * 1024 * 1024 * 1024
MAX_STATE = 2 * 1024 * 1024
PAGE_NAMES = {
    0: "title", 4: "settings", 5: "worlds", 6: "create", 8: "interface",
    9: "controls", 10: "video", 11: "audio", 12: "about",
    16: "accessibility", 17: "language", 18: "mouse", 19: "keybinds",
    20: "online", 21: "skin", 22: "chat", 23: "resources", 24: "telemetry",
    25: "credits", 26: "attributions", 27: "licenses", 28: "font-options",
    29: "connections", 30: "connection-create", 31: "connection-join",
    32: "resource-confirm", 33: "credits-scroll", 34: "guide", 35: "privacy-link",
    36: "friends-confirm", 40: "world-mode",
    41: "story-setup", 42: "test-setup", 43: "create-world-tab",
    44: "create-more-tab", 49: "game-rules", 50: "experiments",
    51: "data-packs", 52: "flat-customize", 53: "biome-customize",
    56: "flat-presets", 57: "restrictions",
}
SETTINGS_PAGES = frozenset({0, 4, 8, 9, 10, 11, 16, 17, 18, 19, 20, 21, 22, 23,
                           24, 25, 26, 27, 28, 32, 33, 34, 35, 36, 57})
# Reviewed backend handlers only open pages or edit the isolated world draft.
# 609 opens the native confirmation page; its external Open button634 is never used.
BACKEND_LINKS = {
    (0, 10): 5, (5, 201): 40, (16, 609): 34,
    (40, 233): 6, (41, 235): 6, (41, 236): 43, (42, 240): 6,
    (52, 754): 56, (56, 755): 52, (56, 756): 52,
    (52, 751): 43, (52, 752): 43,
    (49, 740): 44, (49, 741): 44, (53, 742): 43, (53, 743): 43,
    (50, 747): 44, (50, 748): 44, (51, 747): 44,
}
CORE_SETTINGS = frozenset({4, 8, 9, 10, 11, 16, 17, 18, 19, 20, 21, 22, 23,
                          25, 26, 27, 28})
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def atomically(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(prefix="audit-", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(data)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def write_json(path, value):
    atomically(path, (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True)
                     + "\n").encode("utf-8"))


def read_json(path, limit=MAX_STATE):
    if path.stat().st_size > limit:
        raise ValueError("Oversized audit JSON: " + str(path))
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError("Audit JSON must be an object: " + str(path))
    return value


def beneath_build(path):
    path = Path(path)
    if not path.is_absolute():
        path = ROOT / path
    path = path.resolve()
    if not path.is_relative_to(BUILD.resolve()) or path == BUILD.resolve():
        raise ValueError("UI audit paths must be inside this project's build/core")
    return path


def png_data(path):
    if not path.is_file() or not 24 <= path.stat().st_size <= MAX_PNG:
        raise ValueError("Missing or oversized screenshot: " + str(path))
    raw = path.read_bytes()
    if raw[:8] != PNG_SIGNATURE or raw[12:16] != b"IHDR":
        raise ValueError("Screenshot is not a PNG: " + str(path))
    dimensions = struct.unpack(">II", raw[16:24])
    if dimensions != SIZE:
        raise ValueError("Expected physical PNG %sx%s, got %sx%s" % (*SIZE, *dimensions))
    return raw


def png_pixels(raw):
    """Validate the native PNG and hash decoded RGBA pixels without an image library."""
    if raw[:8] != PNG_SIGNATURE:
        raise ValueError("Invalid native PNG signature")
    at, chunks, compressed = 8, [], bytearray()
    header = None
    while at < len(raw):
        if at + 12 > len(raw):
            raise ValueError("Truncated native PNG chunk")
        length = struct.unpack_from(">I", raw, at)[0]
        end = at + 12 + length
        if end > len(raw):
            raise ValueError("Native PNG chunk leaves the file")
        kind, payload = raw[at+4:at+8], raw[at+8:at+8+length]
        crc = struct.unpack_from(">I", raw, at+8+length)[0]
        if zlib.crc32(kind + payload) & 0xffffffff != crc:
            raise ValueError("Native PNG CRC mismatch")
        if kind == b"IHDR":
            if header is not None or len(payload) != 13:
                raise ValueError("Invalid native PNG header")
            header = struct.unpack(">IIBBBBB", payload)
        elif kind == b"IDAT":
            compressed.extend(payload)
        chunks.append((kind, payload))
        at = end
        if kind == b"IEND":
            if length or at != len(raw):
                raise ValueError("Invalid native PNG ending")
            break
    if header is None or not chunks or chunks[-1][0] != b"IEND":
        raise ValueError("Incomplete native PNG")
    width, height, depth, color, compression, filtering, interlace = header
    if (width, height) != SIZE or depth != 8 or color not in (2, 6) or compression or filtering or interlace:
        raise ValueError("Unexpected native screenshot PNG format")
    channels = 4 if color == 6 else 3
    stride = width * channels
    expected = (stride + 1) * height
    inflater = zlib.decompressobj()
    filtered = inflater.decompress(bytes(compressed), expected + 1)
    if len(filtered) != expected or not inflater.eof or inflater.unused_data or inflater.unconsumed_tail:
        raise ValueError("Native PNG decompression length mismatch")
    digest = hashlib.sha256()
    previous = bytearray(stride)
    for y in range(height):
        offset = y * (stride + 1)
        method = filtered[offset]
        row = bytearray(filtered[offset+1:offset+1+stride])
        if method > 4:
            raise ValueError("Unknown PNG row filter")
        if method:
            for x in range(stride):
                left = row[x-channels] if x >= channels else 0
                up = previous[x]
                corner = previous[x-channels] if x >= channels else 0
                if method == 1:
                    predictor = left
                elif method == 2:
                    predictor = up
                elif method == 3:
                    predictor = (left + up) // 2
                else:
                    p = left + up - corner
                    distances = abs(p-left), abs(p-up), abs(p-corner)
                    predictor = (left, up, corner)[distances.index(min(distances))]
                row[x] = (row[x] + predictor) & 255
        if channels == 4:
            digest.update(row)
        else:
            rgba = bytearray(width * 4)
            for x in range(width):
                rgba[x*4:x*4+3] = row[x*3:x*3+3]
                rgba[x*4+3] = 255
            digest.update(rgba)
        previous = row
    return chunks, filtered, digest.hexdigest()


def evidence_png(report, capture, original):
    chunks, filtered, before = png_pixels(original)
    data = bytearray(PNG_SIGNATURE)
    inserted = False
    for kind, payload in chunks:
        if kind == b"IDAT":
            if inserted:
                continue
            payload = zlib.compress(filtered, 9)
            inserted = True
        data.extend(struct.pack(">I", len(payload)))
        data.extend(kind)
        data.extend(payload)
        data.extend(struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff))
    encoded = bytes(data)
    _, after_filtered, after = png_pixels(encoded)
    if before != after or filtered != after_filtered:
        raise ValueError("Lossless evidence repack changed screenshot pixels")
    target = report / "evidence" / (capture["label"] + ".png")
    atomically(target, encoded)
    capture["evidence"] = {
        "png": target.relative_to(report).as_posix(),
        "sha256": hashlib.sha256(encoded).hexdigest(), "bytes": len(encoded),
        "rgbaSha256Before": before, "rgbaSha256After": after,
        "operation": "Lossless IDAT recompression only; original PNG retained",
    }
    return encoded


def export_chunks(report, capture):
    if not re.fullmatch(r"p[0-9]+-[a-z0-9-]{1,96}", capture["label"]):
        raise ValueError("Invalid audit capture label")
    path = (report / capture["png"]).resolve()
    if path.parent != report or path.name != capture["png"]:
        raise ValueError("Invalid capture path in audit manifest")
    raw = png_data(path)
    digest = hashlib.sha256(raw).hexdigest()
    if digest != capture["sha256"] or len(raw) != capture["bytes"]:
        raise ValueError("Capture changed since its audit record")
    raw = evidence_png(report, capture, raw)
    digest = hashlib.sha256(raw).hexdigest()
    directory = report / "evidence" / capture["label"]
    directory.mkdir(parents=True, exist_ok=True)
    count = math.ceil(len(raw) / CHUNK_BYTES)
    result = []
    for index in range(count):
        offset = index * CHUNK_BYTES
        chunk = raw[offset:offset + CHUNK_BYTES]
        target = directory / ("%04d.json" % index)
        write_json(target, {
            "schema": 1, "encoding": "base64", "mimeType": "image/png",
            "capture": capture["label"], "pngSha256": digest,
            "originalPngSha256": capture["sha256"],
            "index": index, "count": count, "offset": offset, "bytes": len(chunk),
            "chunkSha256": hashlib.sha256(chunk).hexdigest(),
            "data": base64.b64encode(chunk).decode("ascii"),
        })
        result.append(target.relative_to(report).as_posix())
    return result


def scrollbar(state):
    return next((button for button in state["buttons"]
                 if button.get("id") == 690 and button.get("enabled")), None)


def scroll_amount(state):
    bar = scrollbar(state)
    if bar is None:
        return None
    value = bar.get("amount")
    if not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 <= value <= 1:
        raise ValueError("Invalid scrollbar amount")
    return float(value)


class Audit:
    def __init__(self, args):
        self.args = args
        self.exe = beneath_build(args.exe)
        if self.exe.name.lower() != "darkwinter.exe" or not self.exe.is_file():
            raise ValueError("Expected the built DarkWinter.exe")
        self.report = beneath_build(args.report)
        self.allowed = SETTINGS_PAGES if args.phase == "settings" else frozenset(PAGE_NAMES)
        self.created_report = False
        self.process = None
        self.log = None
        self.sequence = 0
        self.state = None
        self.started = time.monotonic()
        self.deadline = self.started + args.max_seconds
        self.routes = {0: []}
        self.pending = deque([0])
        self.visited = set()
        self.png_bytes = 0
        self.disabled_links = set()
        self.receipt = {
            "schema": 1, "completed": False, "phase": args.phase,
            "physicalSize": list(SIZE), "guiSize": list(GUI_SIZE), "guiScale": 3,
            "executable": self.exe.relative_to(ROOT).as_posix(),
            "executableSha256": hashlib.sha256(self.exe.read_bytes()).hexdigest(),
            "startedUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "scope": "Native UI only; isolated preferences and saves; no world startup",
            "visualParity": "not_assessed",
            "captures": [], "actions": [], "pages": {}, "checks": [], "errors": [],
            "limits": {"seconds": args.max_seconds, "captures": args.max_captures,
                       "scrollsPerPage": args.max_scrolls, "rawPngBytes": MAX_EVIDENCE},
        }

    def checkpoint(self):
        self.receipt["elapsedSeconds"] = round(time.monotonic() - self.started, 3)
        self.receipt["visitedPages"] = sorted(self.visited)
        self.receipt["disabledNavigation"] = [
            {"source": page, "button": button, "target": target}
            for page, button, target in sorted(self.disabled_links)]
        write_json(self.report / "audit.json", self.receipt)

    def bounded(self):
        if time.monotonic() >= self.deadline:
            raise TimeoutError("Native UI audit reached its overall time limit")

    def poll(self):
        self.bounded()
        if self.process.poll() is not None:
            error = self.report / "error.json"
            detail = read_json(error) if error.is_file() else {}
            raise RuntimeError("DarkWinter exited during audit: %s; %s"
                               % (self.process.returncode, detail))
        try:
            state = read_json(self.report / "state.json")
        except (FileNotFoundError, PermissionError, json.JSONDecodeError):
            return None
        if state.get("pid") != self.process.pid:
            raise ValueError("Native lab state PID does not match the launched process")
        if not isinstance(state.get("buttons"), list) or len(state["buttons"]) > 8192:
            raise ValueError("Invalid or excessive native UI buttons")
        if state.get("page") not in self.allowed:
            raise ValueError("Audit left its menu-only page scope: " + str(state.get("page")))
        if state.get("worldId"):
            raise ValueError("A world became active during the menu audit")
        actual = state.get("clientWidth"), state.get("clientHeight")
        logical = state.get("uiWidth"), state.get("uiHeight")
        if actual != SIZE or logical != GUI_SIZE:
            raise ValueError("UI dimensions differ: physical=%s logical=%s" % (actual, logical))
        self.state = state
        return state

    def wait(self, predicate, timeout=20, description="native state"):
        until = min(self.deadline, time.monotonic() + timeout)
        while time.monotonic() < until:
            state = self.poll()
            if state is not None and predicate(state):
                return state
            time.sleep(.05)
        raise TimeoutError("Timed out waiting for " + description)

    def command(self, operation, expected=None, timeout=20):
        self.sequence += 1
        record = {"sequence": self.sequence, "operation": operation,
                  "sourcePage": self.state["page"] if self.state else None}
        self.receipt["actions"].append(record)
        atomically(self.report / "inbox.txt",
                   ("%s %s %s\n" % (self.process.pid, self.sequence, operation)).encode("utf-8"))
        state = self.wait(lambda value: value.get("sequence", -1) >= self.sequence,
                          timeout, operation)
        if state.get("sequence") != self.sequence or state.get("result") != "accepted":
            record["result"] = state.get("result")
            raise RuntimeError("Native UI command rejected: " + operation)
        if expected is not None:
            state = self.wait(expected, timeout, operation + " result")
        record["result"] = "accepted"
        record["targetPage"] = state["page"]
        record["frame"] = state["frame"]
        return state

    def settle(self):
        frame = self.state["frame"]
        earliest = time.monotonic() + .3
        return self.wait(lambda state: state["frame"] >= frame + 2
                         and time.monotonic() >= earliest, description="settled UI frames")

    def neutral_pointer(self):
        self.command("pointer %d %d %d move" % (self.state["page"], GUI_SIZE[0]-2, GUI_SIZE[1]-2))
        self.settle()

    def capture(self, suffix):
        if len(self.receipt["captures"]) >= self.args.max_captures:
            raise ValueError("Native UI audit reached its capture limit")
        self.neutral_pointer()
        page = self.state["page"]
        label = "p%d-%s-%s" % (page, PAGE_NAMES[page], suffix)
        if any(row["label"] == label for row in self.receipt["captures"]):
            raise ValueError("Duplicate native UI capture label: " + label)
        expected_sequence = self.sequence + 1
        filename = "lab-%d-%d.png" % (self.process.pid, expected_sequence)
        state = self.command("capture", lambda value: value.get("capture") == filename)
        raw = png_data(self.report / filename)
        self.png_bytes += len(raw)
        if self.png_bytes > MAX_EVIDENCE:
            raise ValueError("Native UI audit exceeded its total PNG byte limit")
        state_name = label + ".state.json"
        write_json(self.report / state_name, state)
        item = {
            "label": label, "page": page, "png": filename,
            "state": state_name, "sha256": hashlib.sha256(raw).hexdigest(),
            "bytes": len(raw), "width": SIZE[0], "height": SIZE[1],
            "frame": state["frame"], "scrollAmount": scroll_amount(state),
            "chunks": [],
        }
        self.receipt["captures"].append(item)
        if self.args.chunks == "all" or (self.args.chunks == "top" and suffix == "top"):
            item["chunks"] = export_chunks(self.report, item)
        self.checkpoint()
        return state

    def button(self, identifier):
        matches = [button for button in self.state["buttons"] if button.get("id") == identifier]
        if len(matches) != 1 or not matches[0].get("enabled"):
            raise ValueError("Expected one enabled button %s on page %s"
                             % (identifier, self.state["page"]))
        return matches[0]

    def scroll(self, delta):
        bar = scrollbar(self.state)
        if bar is None:
            return False
        x = max(1, min(GUI_SIZE[0]-2, int(bar["x"])-20))
        y = max(1, min(GUI_SIZE[1]-2, int(bar["y"])+max(1, int(bar["height"]))//2))
        self.command("scroll %d %d %d" % (delta, x, y))
        self.settle()
        return True

    def down_steps(self):
        bar = scrollbar(self.state)
        # Current native menu steps are <=26 GUI units; keep at least 52 units overlap.
        return max(1, min(100, (int(bar["height"]) - 52) // 26)) if bar else 1

    def locate_navigation(self, identifier):
        def visible():
            return any(button.get("id") == identifier and button.get("enabled")
                       for button in self.state["buttons"])
        if visible():
            return
        # A route may contain a link discovered below the first viewport.
        for _ in range(8):
            amount = scroll_amount(self.state)
            if amount is None or amount <= .000001:
                break
            self.scroll(100)
        for _ in range(self.args.max_scrolls + 1):
            if visible():
                return
            amount = scroll_amount(self.state)
            if amount is None or amount >= .999999:
                break
            self.scroll(-self.down_steps())
        raise ValueError("Navigation button is not reachable in its scroll view: " + str(identifier))

    def click_transition(self, identifier, target):
        self.locate_navigation(identifier)
        page = self.state["page"]
        button = self.button(identifier)
        observed = button.get("targetPage", -1)
        if observed != target and BACKEND_LINKS.get((page, identifier)) != target:
            raise ValueError("Navigation contract changed for button " + str(identifier))
        self.command("click %d %d" % (page, identifier), lambda value: value["page"] == target)
        self.settle()

    def reset_title(self):
        for _ in range(24):
            if self.state["page"] == 0:
                return
            self.command("key 27")
            self.settle()
        raise RuntimeError("Escape did not return to title within 24 steps")

    def follow(self, route):
        self.reset_title()
        for source, identifier, target in route:
            if self.state["page"] != source:
                raise RuntimeError("Navigation route source changed")
            self.click_transition(identifier, target)

    def discover(self, route):
        page = self.state["page"]
        for button in self.state["buttons"]:
            identifier = button.get("id")
            target = button.get("targetPage", -1)
            if target == -1:
                target = BACKEND_LINKS.get((page, identifier), -1)
            if target not in self.allowed or target == page:
                continue
            if not button.get("enabled"):
                self.disabled_links.add((page, identifier, target))
                continue
            if target not in self.routes:
                self.routes[target] = route + [(page, identifier, target)]
                self.pending.append(target)

    def visit(self, page, route):
        self.follow(route)
        self.capture("top")
        self.visited.add(page)
        page_record = {"route": route, "scroll": "not_needed", "capturedViews": 1,
                       "buttonIds": [], "disabledButtonIds": []}
        self.receipt["pages"][str(page)] = page_record
        seen, disabled = set(), set()
        def observe():
            self.discover(route)
            for button in self.state["buttons"]:
                seen.add(button.get("id"))
                if not button.get("enabled"):
                    disabled.add(button.get("id"))
        observe()
        initial = scroll_amount(self.state)
        if initial is not None:
            page_record["scroll"] = "incomplete"
            for index in range(1, self.args.max_scrolls + 1):
                amount = scroll_amount(self.state)
                if amount is None or amount >= .999999:
                    page_record["scroll"] = "complete"
                    break
                self.scroll(-self.down_steps())
                after = scroll_amount(self.state)
                if after is not None and after <= amount + .000001:
                    raise RuntimeError("Scrollbar did not advance on page " + str(page))
                self.capture("scroll-%02d" % index)
                page_record["capturedViews"] += 1
                observe()
                if after is None or after >= .999999:
                    page_record["scroll"] = "complete"
                    break
            if page_record["scroll"] != "complete":
                raise RuntimeError("Scrolling exceeded the bounded page limit: " + str(page))
        page_record["buttonIds"] = sorted(seen)
        page_record["disabledButtonIds"] = sorted(disabled)
        self.checkpoint()

    def start(self):
        self.report.mkdir(parents=True, exist_ok=False)
        self.created_report = True
        settings = self.report / "settings.cfg"
        atomically(settings, (
            "schema 2\nwidth 2560\nheight 1440\nfullscreen 1\nvsync 0\nfps 60\n"
            "motion 0\nfxStrength 1\nmenuMist 1\noption \"guiScale\" 3\n"
            "text \"language\" \"ru_ru\"\n").encode("utf-8"))
        (self.report / "saves").mkdir()
        self.log = open(self.report / "process.log", "wb")
        argv = [str(self.exe), "--lab", "--no-vsync",
                "--settings", str(settings), "--report", str(self.report),
                "--saves", str(self.report / "saves")]
        self.receipt["arguments"] = argv
        self.process = subprocess.Popen(argv, cwd=self.exe.parent, stdout=self.log, stderr=self.log)
        self.receipt["pid"] = self.process.pid
        self.checkpoint()
        self.wait(lambda state: state["page"] == 0, timeout=30, description="title readiness")
        self.receipt["checks"].append({"check": "title_geometry", "result": "pass"})

    def stop(self):
        if self.process is None:
            if self.log is not None:
                self.log.close()
            return
        if self.process.poll() is None:
            self.sequence += 1
            atomically(self.report / "inbox.txt",
                       ("%s %s quit\n" % (self.process.pid, self.sequence)).encode("ascii"))
            try:
                self.process.wait(timeout=10)
                self.receipt["shutdown"] = "graceful"
            except subprocess.TimeoutExpired:
                self.process.terminate()
                try:
                    self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=5)
                self.receipt["shutdown"] = "own_process_terminated_after_timeout"
                self.receipt["errors"].append("Native process ignored bounded graceful quit")
        else:
            self.receipt["shutdown"] = "process_already_exited"
        self.receipt["exitCode"] = self.process.returncode
        if self.process.returncode != 0:
            self.receipt["errors"].append("Native process exit code " + str(self.process.returncode))
        if self.log is not None:
            self.log.close()
        stage = self.report / "stage6.json"
        if stage.is_file():
            self.receipt["nativeExitReport"] = read_json(stage)
        self.checkpoint()

    def run(self):
        try:
            self.start()
            if self.args.phase == "smoke":
                self.capture("top")
                self.visited.add(0)
            else:
                while self.pending:
                    page = self.pending.popleft()
                    if page not in self.visited:
                        self.visit(page, self.routes[page])
                required = CORE_SETTINGS
                missing = sorted(required - self.visited)
                self.receipt["unreachedRequiredPages"] = missing
                if missing:
                    raise RuntimeError("Required settings pages were not reachable: " + str(missing))
                self.reset_title()
            self.receipt["checks"].append({"check": "original_capture_geometry", "result": "pass"})
        except Exception as error:
            self.receipt["errors"].append(str(error))
            self.receipt["traceback"] = traceback.format_exc()
        finally:
            try:
                self.stop()
            except Exception as error:
                self.receipt["errors"].append("Shutdown report: " + str(error))
            if self.created_report:
                self.receipt["completed"] = not self.receipt["errors"]
                self.receipt["finishedUtc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
                self.checkpoint()
        return 0 if self.receipt["completed"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exe", type=Path, default=BUILD / "game-client-v9/DarkWinter.exe")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--phase", choices=("smoke", "settings", "menus"), default="smoke")
    parser.add_argument("--chunks", choices=("all", "top", "none"), default="top",
                        help="Keep all original PNGs; expose selected originals in bounded JSON chunks")
    parser.add_argument("--max-seconds", type=int, default=600)
    parser.add_argument("--max-captures", type=int, default=256)
    parser.add_argument("--max-scrolls", type=int, default=32)
    parser.add_argument("--export-label", help="Export one existing audited PNG without launching the game")
    args = parser.parse_args()
    if not 30 <= args.max_seconds <= 1800 or not 1 <= args.max_captures <= 512 or not 1 <= args.max_scrolls <= 64:
        parser.error("Audit limits must be bounded: seconds30..1800, captures1..512, scrolls1..64")
    if args.export_label:
        report = beneath_build(args.report)
        receipt = read_json(report / "audit.json", 16 * 1024 * 1024)
        matches = [item for item in receipt.get("captures", []) if item.get("label") == args.export_label]
        if len(matches) != 1:
            raise ValueError("Expected exactly one audited capture label")
        matches[0]["chunks"] = export_chunks(report, matches[0])
        write_json(report / "audit.json", receipt)
        print(json.dumps({"exported": args.export_label, "chunks": matches[0]["chunks"]}))
        return 0
    if os.name != "nt":
        parser.error("The native UI audit launcher is Windows-only")
    audit = Audit(args)
    result = audit.run()
    print(json.dumps({"completed": audit.receipt["completed"],
                      "report": audit.report.relative_to(ROOT).as_posix(),
                      "captures": len(audit.receipt["captures"]),
                      "pages": sorted(audit.visited),
                      "errors": audit.receipt["errors"]}, ensure_ascii=False))
    return result


if __name__ == "__main__":
    raise SystemExit(main())
