"""Capture native pause, statistics and world options using one isolated story world.

Windows usage:
  python audit-native-world-ui.py --report build/core/ui-audit-world --chunks all

Imports the native menu auditor's process, capture and chunk protocol. The only
world mutation is creating and normally saving this audit's own new world under
its fresh report directory. It does not move the player or apply world options.
Disabled empty Item/Mob statistics tabs are recorded without forcing them open.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import time
import traceback

_source = Path(__file__).with_name("audit-native-ui.py")
_spec = importlib.util.spec_from_file_location("darkwinter_menu_audit", _source)
menu = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(menu)
Audit = menu.Audit
export_chunks = menu.export_chunks
menu.PAGE_NAMES.update({1: "game", 2: "pause", 7: "loading", 13: "fault",
                        14: "statistics-general", 15: "world-options",
                        47: "statistics-items", 48: "statistics-mobs",
                        49: "game-rules", 57: "world-restrictions"})

ALLOWED_PAGES = frozenset({0, 1, 2, 5, 6, 7, 13, 14, 15, 40, 41, 47, 48, 49, 57})
CLICK_ROUTES = {
    (0, 10): 5, (5, 201): 40, (40, 700): 41, (41, 235): 6, (6, 301): 1,
    (2, 5): 14, (2, 6): 15, (2, 4): 0,
    (14, 724): 14, (14, 725): 47, (14, 726): 48, (14, 727): 2,
    (47, 724): 14, (47, 725): 47, (47, 726): 48, (47, 727): 2,
    (48, 724): 14, (48, 725): 47, (48, 726): 48, (48, 727): 2,
    (15, 758): 49, (15, 759): 57, (15, 251): 2,
    (49, 741): 15, (57, 757): 15,
}


class WorldAudit(Audit):
    def __init__(self, args):
        super().__init__(args)
        self.allowed = ALLOWED_PAGES
        self.creation_authorized = False
        self.own_world = None
        self.session_saved = False
        self.receipt["scope"] = (
            "One new story world in isolated audit saves; inspect pause/statistics/world "
            "options; no movement, settings Apply, network or existing-world changes")
        self.receipt["limits"]["worldLoadSeconds"] = args.load_seconds
        self.receipt["limits"]["gracefulCleanupSeconds"] = 45
        self.receipt["unavailablePages"] = []

    def poll(self):
        self.bounded()
        if self.process.poll() is not None:
            error = self.report / "error.json"
            detail = menu.read_json(error) if error.is_file() else {}
            raise RuntimeError("DarkWinter exited during world audit: %s; %s"
                               % (self.process.returncode, detail))
        try:
            state = menu.read_json(self.report / "state.json")
        except (FileNotFoundError, PermissionError, json.JSONDecodeError):
            return None
        if state.get("pid") != self.process.pid:
            raise ValueError("Native lab state PID differs from the owned process")
        if not isinstance(state.get("buttons"), list) or len(state["buttons"]) > 8192:
            raise ValueError("Invalid native UI buttons")
        page = state.get("page")
        if page not in self.allowed:
            raise ValueError("World audit left its explicit page scope: " + str(page))
        actual = state.get("clientWidth"), state.get("clientHeight")
        logical = state.get("uiWidth"), state.get("uiHeight")
        if actual != menu.SIZE or logical != menu.GUI_SIZE:
            raise ValueError("World audit dimensions differ: %s / %s" % (actual, logical))
        world = state.get("worldId", "")
        if not isinstance(world, str) or len(world) > 128:
            raise ValueError("Invalid native world identity")
        if world and not self.creation_authorized:
            raise ValueError("A world appeared before the isolated Create action")
        if page == 1:
            if not self.creation_authorized or not world:
                raise ValueError("Gameplay did not identify the isolated created world")
            if self.own_world is None:
                self.own_world = world
                self.receipt["worldId"] = world
        # Native retains the session identity after Save and Quit; only the owned
        # identity is allowed on those menus as well as during gameplay.
        if world and self.own_world is not None and world != self.own_world:
            raise ValueError("The audit unexpectedly switched to another world")
        self.state = state
        return state

    def command(self, operation, expected=None, timeout=20):
        words = operation.split()
        if not words:
            raise ValueError("Empty audit command")
        kind = words[0]
        if kind == "click":
            if len(words) != 3:
                raise ValueError("Invalid click command")
            route = int(words[1]), int(words[2])
            if route not in CLICK_ROUTES or self.state["page"] != route[0]:
                raise ValueError("Click is outside the explicit world audit route")
        elif kind == "key":
            if words != ["key", "27"]:
                raise ValueError("The world audit only permits the Escape key")
        elif kind == "pointer":
            if len(words) != 5 or words[-1] != "move":
                raise ValueError("The world audit does not drag or press pointer controls")
        elif kind not in {"scroll", "capture", "quit"}:
            raise ValueError("Command is outside world UI inspection: " + kind)
        return super().command(operation, expected, timeout)

    def click(self, identifier, target, timeout=20):
        self.locate_navigation(identifier)
        self.button(identifier)
        source = self.state["page"]
        if CLICK_ROUTES.get((source, identifier)) != target:
            raise ValueError("World UI route contract changed")
        self.command("click %d %d" % (source, identifier),
                     lambda state: state["page"] in {target, 13}, timeout)
        if self.state["page"] == 13:
            raise RuntimeError("Native world startup failed: " + self.state.get("status", ""))
        self.settle()

    def record_page(self):
        page = self.state["page"]
        if page in self.visited:
            return
        for _ in range(self.args.max_scrolls):
            amount = menu.scroll_amount(self.state)
            if amount is None or amount <= .000001:
                break
            self.scroll(100)
        self.capture("top")
        self.visited.add(page)
        record = {"route": "explicit_world_ui", "capturedViews": 1,
                  "scroll": "not_needed", "buttonIds": [], "disabledButtonIds": []}
        self.receipt["pages"][str(page)] = record
        seen, disabled = set(), set()
        def observe():
            for button in self.state["buttons"]:
                seen.add(button["id"])
                if not button.get("enabled"):
                    disabled.add(button["id"])
        observe()
        amount = menu.scroll_amount(self.state)
        if amount is not None:
            for _ in range(self.args.max_scrolls):
                previous = menu.scroll_amount(self.state)
                if previous is None or previous >= .999999:
                    break
                self.scroll(-100)
                after = menu.scroll_amount(self.state)
                if after is not None and after <= previous + .000001:
                    raise RuntimeError("World UI scrollbar did not advance")
                observe()
            last = menu.scroll_amount(self.state)
            if last is not None and last < .999999:
                raise RuntimeError("World UI scroll exceeded the bounded limit")
            self.capture("bottom")
            record["capturedViews"] += 1
            record["scroll"] = "top_and_bottom"
        record["buttonIds"] = sorted(seen)
        record["disabledButtonIds"] = sorted(disabled)
        self.checkpoint()

    def save_and_return(self):
        if self.session_saved or not self.own_world or not self.process or self.process.poll() is not None:
            return
        for _ in range(12):
            page = self.state["page"]
            if page == 0:
                break
            if page == 2:
                self.click(4, 0, timeout=30)
                break
            if page in {7, 13}:
                raise RuntimeError("Cannot normally close an incomplete world load")
            self.command("key 27")
            self.settle()
        if self.state["page"] != 0:
            raise RuntimeError("Could not return the isolated session to title")
        saves = self.report / "saves"
        pairs = [path for path in saves.glob("*.state") if path.with_suffix(".dwv").is_file()]
        if not pairs:
            raise RuntimeError("Normal Save and Quit did not produce an isolated save pair")
        self.receipt["savedFiles"] = [
            {"path": path.relative_to(self.report).as_posix(), "bytes": path.stat().st_size}
            for path in sorted(saves.iterdir()) if path.is_file()]
        self.session_saved = True
        self.receipt["checks"].append({"check": "isolated_world_saved_and_closed", "result": "pass"})
        self.checkpoint()

    def stop(self):
        # Cleanup has its own finite allowance even after the inspection deadline.
        # 25 seconds for Save and Quit plus Audit.stop's bounded20-second exit.
        self.deadline = time.monotonic() + 25
        try:
            self.save_and_return()
        except Exception as error:
            self.receipt["errors"].append("Normal world cleanup: " + str(error))
        finally:
            super().stop()

    def run(self):
        try:
            self.start()
            self.click(10, 5)
            if self.state.get("worldCount") != 0:
                raise ValueError("The isolated audit save directory was not empty")
            self.click(201, 40)
            self.click(700, 41)
            self.click(235, 6)
            self.creation_authorized = True
            self.click(301, 1, self.args.load_seconds)
            self.receipt["checks"].append({"check": "isolated_story_world_started", "result": "pass"})
            self.command("key 27", lambda state: state["page"] == 2)
            self.settle()
            self.record_page()
            self.click(5, 14)
            self.record_page()
            for identifier, target in ((725, 47), (726, 48)):
                button = next((b for b in self.state["buttons"] if b["id"] == identifier), None)
                if button is None:
                    raise ValueError("Missing native statistics tab " + str(identifier))
                if not button.get("enabled"):
                    self.receipt["unavailablePages"].append({
                        "page": target, "button": identifier, "reason": "disabled_empty_statistics"})
                    self.disabled_links.add((self.state["page"], identifier, target))
                    continue
                self.click(identifier, target)
                self.record_page()
                self.click(724, 14)
            self.click(727, 2)
            self.click(6, 15)
            self.record_page()
            self.click(758, 49)
            self.record_page()
            self.click(741, 15)
            self.click(759, 57)
            self.record_page()
            self.click(757, 15)
            self.click(251, 2)
            self.save_and_return()
            required = {2, 14, 15, 49, 57}
            missing = sorted(required - self.visited)
            self.receipt["unreachedRequiredPages"] = missing
            if missing:
                raise RuntimeError("Required world pages were not captured: " + str(missing))
            self.receipt["checks"].append({"check": "world_pages_captured_without_apply", "result": "pass"})
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
    parser.add_argument("--exe", type=Path, default=menu.BUILD / "game-client-v9/DarkWinter.exe")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--chunks", choices=("all", "top", "none"), default="all")
    parser.add_argument("--max-seconds", type=int, default=600)
    parser.add_argument("--max-captures", type=int, default=32)
    parser.add_argument("--max-scrolls", type=int, default=8)
    parser.add_argument("--load-seconds", type=int, default=120)
    parser.add_argument("--export-label", help="Export an existing original PNG without launching the game")
    args = parser.parse_args()
    args.phase = "world"
    if not (60 <= args.max_seconds <= 1800 and 1 <= args.max_captures <= 64
            and 1 <= args.max_scrolls <= 32 and 30 <= args.load_seconds <= 300):
        parser.error("Invalid audit bounds: seconds60..1800, captures1..64, scrolls1..32, load30..300")
    if args.export_label:
        report = menu.beneath_build(args.report)
        receipt = menu.read_json(report / "audit.json", 16 * 1024 * 1024)
        captures = [row for row in receipt.get("captures", []) if row["label"] == args.export_label]
        if len(captures) != 1:
            raise ValueError("Expected one existing audited PNG")
        captures[0]["chunks"] = export_chunks(report, captures[0])
        menu.write_json(report / "audit.json", receipt)
        print(json.dumps({"exported": args.export_label, "chunks": captures[0]["chunks"]}))
        return 0
    if os.name != "nt":
        parser.error("The native world audit is Windows-only")
    audit = WorldAudit(args)
    result = audit.run()
    print(json.dumps({"completed": audit.receipt["completed"],
                      "report": audit.report.relative_to(menu.ROOT).as_posix(),
                      "captures": len(audit.receipt["captures"]),
                      "pages": sorted(audit.visited),
                      "unavailablePages": audit.receipt["unavailablePages"],
                      "errors": audit.receipt["errors"]}, ensure_ascii=False))
    return result


if __name__ == "__main__":
    raise SystemExit(main())
