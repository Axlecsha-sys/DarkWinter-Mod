"""Exercise real loading, cancellation, pause and reopen in isolated audit saves.

Windows: python audit-native-loading.py --report build/core/loading-audit
Uses native --lab clicks and original 2560x1440 PNGs at GUI scale 3. Loading is
never delayed artificially. Unknown/count captures are opportunistic; missing
a brief phase is recorded as not_observed. Lossless evidence export runs after
normal game shutdown so compression cannot consume the cancellation window.
"""
import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import time
import traceback

_spec = importlib.util.spec_from_file_location(
    "darkwinter_loading_menu_helpers", Path(__file__).with_name("audit-native-ui.py"))
menu = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(menu)
NAMES = {0: "title", 1: "game", 2: "pause", 5: "worlds", 6: "create",
         7: "loading", 13: "fault", 40: "world-mode", 41: "story-setup"}
ROUTES = {(0, 10): {5}, (5, 201): {40}, (40, 700): {41}, (41, 235): {6},
          (6, 301): {7, 1}, (7, 900): {0}, (2, 4): {0},
          (5, 1000): {5}, (5, 202): {7, 1}}
PAUSED_FIELDS = ("seconds", "x", "y", "z", "yaw", "pitch")


class LoadingAudit(menu.Audit):
    def __init__(self, args):
        super().__init__(args)
        self.own_world = None
        self.creation_authorized = False
        self.receipt["scope"] = "Attempt loading cancellation; complete, save and reopen one isolated world"
        self.receipt["completionMeaning"] = (
            "The world lifecycle completed; cancellation and Escape coverage are reported separately")
        self.receipt["coverage"] = {
            name: {"result": "not_observed", "reason": "not_attempted"}
            for name in ("escape_does_not_cancel_loading",
                         "loading_button_returns_to_title_without_save")}
        self.receipt["limits"]["worldLoadSeconds"] = args.load_seconds
        self.receipt["limits"]["gracefulCleanupSeconds"] = 90
        self.receipt["loadingCaptures"] = {
            mode: {"result": "not_observed"} for mode in ("unknown", "known")}
        self.receipt["captureStateTiming"] = (
            "State brackets capture request/acknowledgement; transitional images need visual review")

    def poll(self):
        self.bounded()
        if self.process.poll() is not None:
            error = self.report / "error.json"
            raise RuntimeError("Owned native process exited: %s; %s" % (
                self.process.returncode, menu.read_json(error) if error.is_file() else {}))
        try:
            state = menu.read_json(self.report / "state.json")
        except (FileNotFoundError, PermissionError, json.JSONDecodeError):
            return None
        if state.get("pid") != self.process.pid or state.get("page") not in NAMES:
            raise ValueError("Unexpected process identity or page in native loading audit")
        if not isinstance(state.get("buttons"), list) or len(state["buttons"]) > 8192:
            raise ValueError("Invalid native button tree")
        if ((state.get("clientWidth"), state.get("clientHeight")) != menu.SIZE
                or (state.get("uiWidth"), state.get("uiHeight")) != menu.GUI_SIZE):
            raise ValueError("Native window/GUI must remain 2560x1440 / 854x480")
        world = state.get("worldId", "")
        if not isinstance(world, str) or len(world) > 128:
            raise ValueError("Invalid native world identity")
        if world and not self.creation_authorized:
            raise ValueError("World activated before the authorized isolated Create click")
        if state["page"] == 1:
            if not world:
                raise ValueError("Gameplay has no world identity")
            if self.own_world is None:
                self.own_world = world
                self.receipt["worldId"] = world
        if world and self.own_world is not None and world != self.own_world:
            raise ValueError("Reopen switched to a different world")
        if state["page"] == 7:
            if type(state.get("loadingKnown")) is not bool:
                raise ValueError("Loading telemetry is missing")
            for key in ("loadingCompleted", "loadingTotal", "loadingElapsedMillis", "loadingFrame"):
                if type(state.get(key)) is not int or state[key] < 0:
                    raise ValueError("Invalid loading telemetry: " + key)
            progress = state.get("progress")
            if (type(progress) not in (int, float) or not math.isfinite(progress)
                    or not 0 <= progress <= 1):
                raise ValueError("Invalid displayed loading progress")
            if state["loadingKnown"]:
                if not (0 < state["loadingTotal"]
                        and 0 <= state["loadingCompleted"] <= state["loadingTotal"]):
                    raise ValueError("Invalid loading completion counts")
                actual = state["loadingCompleted"] / state["loadingTotal"]
                # The native value is float32, serialized as a JSON decimal.
                if progress > actual + 1e-6:
                    raise ValueError("Displayed loading progress exceeds completed work")
            if not isinstance(state.get("loadingStage"), str):
                raise ValueError("Missing loading stage")
        self.state = state
        self.visited.add(state["page"])
        return state

    def check(self, name, **details):
        self.receipt["checks"].append({"check": name, "result": "pass", **details})
        self.checkpoint()

    def click(self, identifier, timeout=20):
        self.poll()
        page = self.state["page"]
        targets = ROUTES.get((page, identifier))
        if targets is None:
            raise ValueError("Click leaves the explicit loading audit route")
        button = self.button(identifier)
        if identifier == 900 and button.get("label", "").strip() != "В главное меню":
            raise ValueError("Loading return button does not match the source UI label")
        result = self.command("click %d %d" % (page, identifier),
                              lambda s: s["page"] in targets | {13}, timeout)
        if result["page"] == 13:
            raise RuntimeError("World load failed: " + result.get("status", ""))
        if result["page"] not in {1, 7}:
            self.settle()
        return self.state

    def capture(self, suffix):
        if len(self.receipt["captures"]) >= self.args.max_captures:
            raise ValueError("Loading audit reached its screenshot limit")
        if self.state["page"] not in {1, 7}:
            self.neutral_pointer()
        before = dict(self.state)
        filename = "lab-%d-%d.png" % (self.process.pid, self.sequence + 1)
        after = self.command("capture", lambda s: s.get("capture") == filename)
        raw = menu.png_data(self.report / filename)
        self.png_bytes += len(raw)
        if self.png_bytes > menu.MAX_EVIDENCE:
            raise ValueError("Loading audit exceeded its PNG byte limit")
        label = "p%d-%s-%s" % (before["page"], NAMES[before["page"]], suffix)
        if any(row["label"] == label for row in self.receipt["captures"]):
            raise ValueError("Duplicate screenshot label")
        state_file = label + ".state.json"
        menu.write_json(self.report / state_file, {"request": before, "acknowledged": after})
        item = {"label": label, "page": before["page"], "acknowledgedPage": after["page"],
                "png": filename, "state": state_file, "sha256": hashlib.sha256(raw).hexdigest(),
                "bytes": len(raw), "width": menu.SIZE[0], "height": menu.SIZE[1],
                "frame": after["frame"], "loadingFrame": after.get("loadingFrame"),
                "transitionedDuringCapture": before["page"] != after["page"], "chunks": []}
        self.receipt["captures"].append(item)
        if (before["page"] == after["page"] == 7
                and before["loadingKnown"] == after["loadingKnown"]):
            mode = "known" if after["loadingKnown"] else "unknown"
            self.receipt["loadingCaptures"][mode] = {"result": "observed", "label": label,
                "completed": after["loadingCompleted"], "total": after["loadingTotal"]}
        self.checkpoint()
        return after

    def create_route(self, tag):
        self.click(10)
        if self.state.get("worldCount") != 0:
            raise ValueError("Cancelled audit created a save or the isolated saves were not empty")
        self.capture(tag + "-worlds")
        self.click(201)
        self.click(700)
        self.capture(tag + "-story")
        self.click(235)
        self.capture(tag + "-create")
        self.creation_authorized = True
        self.click(301, self.args.load_seconds)

    def watch_load(self, tag):
        until = min(self.deadline, time.monotonic() + self.args.load_seconds)
        attempted = set()
        while self.state["page"] == 7:
            mode = "known" if self.state["loadingKnown"] else "unknown"
            # Capture real chunk progress first; an opening PNG can consume that short phase.
            can_capture = (mode == "known"
                           or self.receipt["loadingCaptures"]["known"]["result"] == "observed")
            if (can_capture and mode not in attempted
                    and self.receipt["loadingCaptures"][mode]["result"] == "not_observed"):
                attempted.add(mode)
                self.capture(tag + "-" + mode)
                continue
            previous = self.state["loadingFrame"]
            self.wait(lambda s: s["page"] != 7 or s["loadingFrame"] > previous,
                      max(0, until - time.monotonic()), "loading progress or gameplay")
        if self.state["page"] != 1:
            raise RuntimeError("Load ended outside gameplay: page %s" % self.state["page"])
        self.check("world_started_" + tag, worldId=self.state["worldId"])

    def cancel_first_load(self):
        coverage = self.receipt["coverage"]
        escape_check = "escape_does_not_cancel_loading"
        cancel_check = "loading_button_returns_to_title_without_save"

        def own_gameplay():
            return (self.state["page"] == 1 and self.creation_authorized
                    and self.own_world is not None
                    and self.state.get("worldId") == self.own_world)

        def finish(cancelled, action=None):
            outcome = ({"result": "pass"} if cancelled else
                       {"result": "not_observed", "reason": "completed_before_action",
                        "observedPage": self.state["page"], "worldId": self.own_world})
            if action is not None:
                outcome.update(actionSequence=action["sequence"], actionResult=action.get("result"))
            coverage[cancel_check] = outcome
            self.check(escape_check, **coverage[escape_check])
            self.check(cancel_check, **outcome)
            return cancelled

        self.poll()
        if self.state["page"] != 7:
            if own_gameplay():
                coverage[escape_check]["reason"] = "completed_before_action"
                return finish(False)
            raise RuntimeError("Cancellation attempt started outside loading or its own gameplay")

        before_escape = dict(self.state)
        coverage[escape_check]["reason"] = "command_not_observed"
        after_command = dict(self.command("key 27"))
        if after_command["page"] == 7:
            coverage[escape_check] = {
                "result": "pass", "actionSequence": self.sequence,
                "loadingFrameBefore": before_escape["loadingFrame"],
                "loadingFrameAfter": after_command["loadingFrame"], "pageAfter": 7}
            frame = after_command["loadingFrame"]
            self.wait(lambda s: s["page"] != 7 or s["loadingFrame"] > frame,
                      self.args.load_seconds, "a loading frame after Escape")
        elif own_gameplay():
            coverage[escape_check] = {
                "result": "not_observed", "reason": "completed_before_escape_observation",
                "actionSequence": self.sequence, "pageAfter": 1}
        if self.state["page"] not in {7, 1}:
            raise RuntimeError("Escape cancelled loading or returned to a menu")

        # No capture/checkpoint in this short window; complete loads supply screenshots.
        self.wait(lambda s: s["page"] != 7 or any(
            b.get("id") == 900 and b.get("enabled") for b in s["buttons"]),
            self.args.load_seconds, "enabled loading return button")
        if self.state["page"] != 7:
            if own_gameplay():
                return finish(False)
            raise RuntimeError("Loading ended outside its own gameplay before cancellation")

        sequence_before_click = self.sequence
        coverage[cancel_check]["reason"] = "action_not_observed"
        try:
            self.click(900, self.args.load_seconds)
        except (RuntimeError, ValueError) as error:
            action = self.receipt["actions"][-1]
            rejected_click = (
                str(error) == "Native UI command rejected: click 7 900"
                and action.get("operation") == "click 7 900"
                and action.get("sequence") == self.sequence
                and self.state.get("sequence") == self.sequence
                and action.get("result") == self.state.get("result") == "rejected")
            completed_before_dispatch = (
                str(error) == "Click leaves the explicit loading audit route"
                and self.sequence == sequence_before_click)
            if own_gameplay() and (rejected_click or completed_before_dispatch):
                # Keep any rejected command unchanged in actions; this is missing coverage, not a pass.
                return finish(False, action if rejected_click else None)
            raise

        if any((self.report / "saves").glob("*.state")) or any((self.report / "saves").glob("*.dwv")):
            raise RuntimeError("Cancelled load unexpectedly wrote an isolated world save")
        finish(True, self.receipt["actions"][-1])
        self.capture("cancelled-title")
        return True

    def pause_and_check(self, tag):
        self.command("key 27", lambda s: s["page"] == 2)
        self.settle()
        self.capture(tag + "-pause")
        before = dict(self.state)
        earliest = time.monotonic() + .75
        after = self.wait(lambda s: s["page"] == 2 and s["frame"] >= before["frame"] + 3
                          and time.monotonic() >= earliest, description="paused rendered frames")
        for key in PAUSED_FIELDS:
            value = before.get(key)
            if (not isinstance(value, (int, float)) or not math.isfinite(value)
                    or after.get(key) != value):
                raise RuntimeError("Pause advanced or changed " + key)
        self.check("pause_freezes_world_" + tag,
                   before={key: before[key] for key in PAUSED_FIELDS},
                   after={key: after[key] for key in PAUSED_FIELDS},
                   renderedFrames=after["frame"] - before["frame"])
        return after

    def save_to_title(self, tag):
        self.click(4, timeout=60)
        pairs = [p for p in (self.report / "saves").glob("*.state")
                 if p.with_suffix(".dwv").is_file() and p.with_suffix(".info").is_file()]
        if not pairs or self.state.get("worldCount") != 1:
            raise RuntimeError("Save and return did not produce exactly one listed audit world")
        self.receipt["savedFiles"] = [
            {"path": p.relative_to(self.report).as_posix(), "bytes": p.stat().st_size}
            for p in sorted((self.report / "saves").iterdir()) if p.is_file()]
        self.capture(tag + "-saved-title")
        self.check("save_and_return_" + tag, worldId=self.own_world)

    def stop(self):
        # A separate bounded allowance preserves the isolated world on failure.
        self.deadline = time.monotonic() + 70
        try:
            if self.process is not None and self.process.poll() is None:
                self.poll()
                if self.state and self.state["page"] == 7:
                    self.wait(lambda s: s["page"] != 7 or any(
                        b.get("id") == 900 and b.get("enabled") for b in s["buttons"]),
                        10, "cleanup loading return")
                    if self.state["page"] == 7:
                        self.click(900, timeout=10)
                if self.state and self.state["page"] == 1:
                    self.command("key 27", lambda s: s["page"] == 2, timeout=5)
                if self.state and self.state["page"] == 2:
                    self.click(4, timeout=60)
        except Exception as error:
            self.receipt["errors"].append("Normal world cleanup: " + str(error))
        finally:
            super().stop()

    def run(self):
        try:
            self.start()
            self.capture("initial")
            self.create_route("cancel")
            if self.cancel_first_load():
                self.create_route("create")
            self.watch_load("create")
            self.capture("created")
            first_pause = self.pause_and_check("created")
            self.save_to_title("first")
            self.click(10)
            if self.state.get("worldCount") != 1:
                raise RuntimeError("Reopen requires exactly one listed isolated world")
            self.click(1000)
            self.click(202, self.args.load_seconds)
            self.watch_load("reopen")
            if self.state["worldId"] != first_pause["worldId"]:
                raise RuntimeError("World list reopened a different world")
            self.capture("reopened")
            second_pause = self.pause_and_check("reopened")
            self.check("world_list_reopens_same_world", worldId=second_pause["worldId"])
            self.save_to_title("reopened")
        except Exception as error:
            self.receipt["errors"].append(str(error))
            self.receipt["traceback"] = traceback.format_exc()
        finally:
            try:
                self.stop()
            except Exception as error:
                self.receipt["errors"].append("Shutdown: " + str(error))
            if self.created_report:
                try:
                    if self.args.chunks == "all":
                        for capture in self.receipt["captures"]:
                            capture["chunks"] = menu.export_chunks(self.report, capture)
                except Exception as error:
                    self.receipt["errors"].append("Lossless evidence export: " + str(error))
                self.receipt["completed"] = not self.receipt["errors"]
                self.receipt["finishedUtc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
                self.checkpoint()
        return 0 if self.receipt["completed"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exe", type=Path, default=menu.BUILD / "game-client-v9/DarkWinter.exe")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--chunks", choices=("all", "none"), default="all")
    parser.add_argument("--max-seconds", type=int, default=600)
    parser.add_argument("--max-captures", type=int, default=32)
    parser.add_argument("--load-seconds", type=int, default=120)
    args = parser.parse_args()
    args.phase, args.max_scrolls = "loading", 1
    if not (60 <= args.max_seconds <= 1800 and 1 <= args.max_captures <= 64
            and 10 <= args.load_seconds <= 300):
        parser.error("Bounds: seconds60..1800, captures1..64, load10..300")
    if os.name != "nt":
        parser.error("The native loading audit is Windows-only")
    audit = LoadingAudit(args)
    result = audit.run()
    print(json.dumps({"completed": audit.receipt["completed"],
                      "report": audit.report.relative_to(menu.ROOT).as_posix(),
                      "captures": len(audit.receipt["captures"]),
                      "loadingCaptures": audit.receipt["loadingCaptures"],
                      "coverage": audit.receipt["coverage"],
                      "checks": audit.receipt["checks"], "errors": audit.receipt["errors"]},
                     ensure_ascii=False))
    return result


if __name__ == "__main__":
    raise SystemExit(main())
