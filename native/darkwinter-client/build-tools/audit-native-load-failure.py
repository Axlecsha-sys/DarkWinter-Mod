"""Check a real fatal load error and recovery using only owned audit snapshots.

Windows:
  python audit-native-load-failure.py --report build/core/load-failure-audit

Two sequential native --lab processes use fresh source/recovery subdirectories.
The first creates and saves one story world through real buttons. Only that
snapshot is copied into the second process's empty saves directory. Its copied
.state is temporarily replaced by DWSTATE3 plus a newline, so it remains listed
but must fail mandatory header validation. Original bytes and hashes are kept.
The second process must stay alive at FAULT without gameplay or Continue, return
with Back, reopen the restored same world, save, and quit normally.

No user save path, artificial game delay, failure injection command, or build
is used. Unexpected faults/rejected commands are errors, never expected passes.
"""
import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import shlex
import shutil
import time
import traceback

_spec = importlib.util.spec_from_file_location(
    "darkwinter_load_failure_menu_helpers", Path(__file__).with_name("audit-native-ui.py"))
menu = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(menu)

NAMES = {0: "title", 1: "game", 2: "pause", 5: "worlds", 6: "create",
         7: "loading", 13: "fault", 40: "world-mode", 41: "story-setup"}
ROUTES = {(0, 10): {5}, (5, 201): {40}, (40, 700): {41}, (41, 235): {6},
          (6, 301): {7, 1}, (5, 1000): {5}, (5, 202): {7, 1},
          (2, 4): {0}, (13, 0): {0}}
SIDECARS = (".dwv", ".state", ".info", ".options", ".dwg")
BAD_STATE = b"DWSTATE3\n"
MAX_FILE_BYTES = 256 * 1024 * 1024
MAX_SNAPSHOT_BYTES = 512 * 1024 * 1024
MAX_RAW_PNG_BYTES = 256 * 1024 * 1024
STILL_FIELDS = ("seconds", "x", "y", "z", "yaw", "pitch")


def digest(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_FILE_BYTES:
        raise ValueError("Missing, linked, or oversized audit fixture: " + str(path))
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def owned_file(directory, name):
    path = directory / name
    if path.parent.resolve() != directory.resolve() or path.is_symlink():
        raise ValueError("Fixture path leaves its owned saves directory")
    return path


def snapshot_identity(state):
    info = state.with_suffix(".info")
    if state.stat().st_size > menu.MAX_STATE or info.stat().st_size > menu.MAX_STATE:
        raise ValueError("Oversized snapshot metadata")
    metadata = shlex.split(info.read_text(encoding="utf-8"))
    values = shlex.split(state.read_text(encoding="utf-8"))
    # Native state ends in seed, quoted name, quoted logical ID.
    if (len(metadata) != 3 or not metadata[1].isdigit() or len(values) < 12
            or values[0] != "DWSTATE3"
            or values[-3:] != [metadata[1], metadata[0], metadata[2]]):
        raise ValueError("Native state/info identity mismatch")
    identity = metadata[2]
    if not identity or len(identity) > 128:
        raise ValueError("Invalid saved logical world identity")
    return identity


class FailureSession(menu.Audit):
    def __init__(self, owner, role, world=None):
        args = argparse.Namespace(**vars(owner.args))
        args.report = owner.report / role
        args.phase, args.max_scrolls = "load-failure-" + role, 1
        super().__init__(args)
        self.owner, self.role = owner, role
        self.deadline = owner.deadline
        self.world = world
        self.permit_world = False
        self.expecting_fault = False
        self.closed = False
        self.lifecycle_finished = False
        self.frame_peak = 0
        self.receipt["scope"] = "Owned story snapshot " + role + "; fatal loading error audit"
        self.receipt["limits"].update(mainFrames=owner.args.max_frames,
                                      rawPngBytes=MAX_RAW_PNG_BYTES)
        self.receipt["captureStateTiming"] = "State brackets request and acknowledgement on a stable page"

    def poll(self):
        self.bounded()
        if self.process.poll() is not None:
            error = self.report / "error.json"
            raise RuntimeError("Owned native process exited during audit: %s; %s" % (
                self.process.returncode, menu.read_json(error) if error.is_file() else {}))
        try:
            state = menu.read_json(self.report / "state.json")
        except (FileNotFoundError, PermissionError, json.JSONDecodeError):
            return None
        if state.get("pid") != self.process.pid or state.get("page") not in NAMES:
            raise ValueError("Unexpected owned PID or page in load failure audit")
        if not isinstance(state.get("buttons"), list) or len(state["buttons"]) > 8192:
            raise ValueError("Invalid native button tree")
        if ((state.get("clientWidth"), state.get("clientHeight")) != menu.SIZE
                or (state.get("uiWidth"), state.get("uiHeight")) != menu.GUI_SIZE):
            raise ValueError("Native window/GUI must remain 2560x1440 / 854x480")
        frame = state.get("frame")
        if type(frame) is not int or frame < self.frame_peak:
            raise ValueError("Invalid or decreasing native frame counter")
        self.frame_peak = frame
        if sum(session.frame_peak for session in self.owner.sessions) > self.args.max_frames:
            raise TimeoutError("Load failure audit exceeded its total main frame limit")
        if type(state.get("worldActive")) is not bool or not isinstance(state.get("errorText"), str):
            raise ValueError("Required worldActive/errorText telemetry is missing or invalid")
        if state["worldActive"] and not self.permit_world:
            raise RuntimeError("Gameplay activated before the authorized valid load")
        world = state.get("worldId")
        if not isinstance(world, str) or len(world) > 128:
            raise ValueError("Invalid native world identity")
        if world and not self.permit_world:
            raise RuntimeError("A world activated before the authorized valid load")
        if world and self.world is not None and world != self.world:
            raise RuntimeError("A different world became active")
        if state["page"] == 1:
            if not world or not state["worldActive"]:
                raise RuntimeError("Gameplay has no active logical world identity")
            if self.world is None:
                self.world = world
                self.receipt["worldId"] = world
        if state["page"] == 13 and not self.expecting_fault:
            raise RuntimeError("Unexpected world load failure: " + state.get("errorText", ""))
        self.state = state
        self.visited.add(state["page"])
        return state

    def check(self, name, **details):
        row = {"check": name, "result": "pass", **details}
        self.receipt["checks"].append(row)
        self.owner.receipt["checks"].append({"session": self.role, **row})
        self.checkpoint()
        self.owner.checkpoint()

    def click(self, identifier, timeout=20):
        self.wait(lambda state: True, timeout=5, description="fresh route state")
        page = self.state["page"]
        targets = ROUTES.get((page, identifier))
        if targets is None:
            raise ValueError("Click leaves the explicit load failure audit route")
        self.button(identifier)
        self.command("click %d %d" % (page, identifier),
                     lambda state: state["page"] in targets, timeout)
        if self.state["page"] not in {1, 7}:
            self.settle()
        return self.state

    def capture(self, suffix):
        total = sum(len(session.receipt["captures"]) for session in self.owner.sessions)
        if total >= self.args.max_captures:
            raise ValueError("Load failure audit reached its total screenshot limit")
        if self.state["page"] in {1, 7}:
            raise ValueError("Failure evidence must be captured on a stable menu or paused page")
        self.neutral_pointer()
        before = dict(self.state)
        filename = "lab-%d-%d.png" % (self.process.pid, self.sequence + 1)
        after = self.command("capture", lambda state: state.get("capture") == filename)
        if after["page"] != before["page"]:
            raise RuntimeError("Failure evidence changed page during capture")
        raw = menu.png_data(self.report / filename)
        self.owner.png_bytes += len(raw)
        if self.owner.png_bytes > MAX_RAW_PNG_BYTES:
            raise ValueError("Load failure audit exceeded its total PNG byte limit")
        label = "p%d-%s-%s" % (before["page"], NAMES[before["page"]], suffix)
        if any(item["label"] == label for item in self.receipt["captures"]):
            raise ValueError("Duplicate failure evidence label")
        state_file = label + ".state.json"
        menu.write_json(self.report / state_file, {"request": before, "acknowledged": after})
        self.receipt["captures"].append({
            "label": label, "page": before["page"], "acknowledgedPage": after["page"],
            "png": filename, "state": state_file, "sha256": hashlib.sha256(raw).hexdigest(),
            "bytes": len(raw), "width": menu.SIZE[0], "height": menu.SIZE[1],
            "frame": after["frame"], "chunks": []})
        self.checkpoint()
        return after

    def wait_game(self):
        self.wait(lambda state: state["page"] == 1, self.args.load_seconds, "owned world gameplay")
        if not self.world or self.state["worldId"] != self.world:
            raise RuntimeError("Expected owned world was not installed")
        if type(self.state.get("residentSections")) is not int or self.state["residentSections"] <= 0:
            raise RuntimeError("Gameplay has no resident scene")
        self.command("key 27", lambda state: state["page"] == 2)
        self.settle()
        self.check("valid_world_loaded_and_paused", worldId=self.world,
                   residentSections=self.state["residentSections"])

    def create_story(self):
        self.click(10)
        if self.state.get("worldCount") != 0 or any((self.report / "saves").iterdir()):
            raise RuntimeError("Source audit saves were not empty")
        for identifier in (201, 700, 235):
            self.click(identifier)
        self.permit_world = True
        self.click(301, self.args.load_seconds)
        self.wait_game()

    def select_only_world(self):
        self.click(10)
        if self.state.get("worldCount") != 1:
            raise RuntimeError("Expected exactly one listed owned world")
        self.click(1000)
        self.button(202)

    def save_to_title(self):
        if self.state["page"] != 2 or self.state.get("worldId") != self.world:
            raise RuntimeError("Saving requires the paused owned world")
        self.click(4, timeout=60)
        if self.state.get("worldCount") != 1 or self.state.get("firstPerson") is not False:
            raise RuntimeError("Save did not return to one inactive listed world")
        if self.state.get("worldActive") is not False:
            raise RuntimeError("Save to title left gameplay active")
        states = sorted((self.report / "saves").glob("*.state"))
        if not states or len(states) > 16:
            raise RuntimeError("Missing or excessive owned saved snapshots")
        for state in states:
            if snapshot_identity(state) != self.world:
                raise RuntimeError("Save produced a different logical world")
            for suffix in SIDECARS:
                digest(owned_file(state.parent, state.with_suffix(suffix).name))
        self.receipt["savedFiles"] = [
            {"path": path.relative_to(self.report).as_posix(), "bytes": path.stat().st_size,
             "sha256": digest(path)}
            for path in sorted((self.report / "saves").iterdir()) if path.is_file()]
        self.check("save_to_title", worldId=self.world, snapshots=len(states))
        return max(states, key=lambda path: path.stat().st_mtime_ns)

    def assert_inactive_fault(self, state):
        if (state["page"] != 13 or state.get("worldId") != ""
                or state.get("residentSections") != 0 or state.get("firstPerson") is not False):
            raise RuntimeError("Failed initial load installed a scene or entered gameplay")
        if state.get("worldActive") is not False:
            raise RuntimeError("Failed initial load left gameplay active")
        if (len(state["buttons"]) != 1 or state["buttons"][0].get("id") != 0
                or state["buttons"][0].get("enabled") is not True
                or state["buttons"][0].get("label", "").strip() != "Назад"):
            raise RuntimeError("Fatal load error must expose only Back, without Continue")
        for key in STILL_FIELDS:
            if type(state.get(key)) not in (int, float) or not math.isfinite(state[key]):
                raise ValueError("Invalid inactive fault telemetry: " + key)

    def open_with_expected_failure(self):
        """Accept only the specific observed FAULT after an accepted real Open click."""
        self.owner.assert_injected_copy()
        self.wait(lambda state: state["page"] == 5, description="selected owned fixture")
        self.button(202)
        self.expecting_fault = True
        # This is the only expected-failure gate. No caught exception is a passing result.
        state = self.command("click 5 202", lambda value: value["page"] == 13,
                             self.args.load_seconds)
        self.assert_inactive_fault(state)
        if state.get("errorText") != "Save header invalid":
            raise RuntimeError("Malformed state reached a different fatal error: " + repr(state.get("errorText")))
        before = dict(state)
        after = self.wait(lambda value: value["page"] == 13
                          and value["frame"] >= before["frame"] + 3,
                          10, "live inactive fault frames")
        self.assert_inactive_fault(after)
        if after.get("errorText") != "Save header invalid":
            raise RuntimeError("Expected fatal error changed before its evidence capture")
        if any(after[key] != before[key] for key in STILL_FIELDS):
            raise RuntimeError("Failed load advanced the uninstalled world")
        self.owner.assert_injected_copy()
        self.check("malformed_owned_state_reaches_live_inactive_fault",
                   expectedError="Save header invalid",
                   observedError=after["errorText"], worldActive=after["worldActive"],
                   worldId=after["worldId"], residentSections=after["residentSections"],
                   buttons=after["buttons"], actionSequence=self.sequence,
                   renderedFrames=after["frame"] - before["frame"])
        self.capture("invalid-state")
        self.click(0)
        self.expecting_fault = False
        if (self.state.get("worldId") or self.state.get("residentSections") != 0
                or self.state.get("worldActive") is not False):
            raise RuntimeError("Back from failed loading activated a world")
        self.check("fault_back_returns_to_title", page=self.state["page"], pid=self.process.pid)


class LoadFailureAudit:
    def __init__(self, args):
        self.args = args
        self.report = menu.beneath_build(args.report)
        self.started = time.monotonic()
        self.deadline = self.started + args.max_seconds
        self.sessions = []
        self.png_bytes = 0
        self.created_report = False
        self.fixture = None
        self.original_state = None
        self.copied_state = None
        self.receipt = {
            "schema": 1, "phase": "load-failure", "completed": False,
            "scope": "Fatal malformed header in a copied owned snapshot, then exact-byte restore and reopen",
            "completionMeaning": "Both owned processes exited normally; expected fatal failure and restored lifecycle passed",
            "visualParity": "not_assessed", "worldId": None,
            "startedUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "limits": {"seconds": args.max_seconds, "worldLoadSeconds": args.load_seconds,
                       "mainFrames": args.max_frames, "captures": args.max_captures,
                       "rawPngBytes": MAX_RAW_PNG_BYTES, "snapshotBytes": MAX_SNAPSHOT_BYTES,
                       "gracefulCleanupSecondsPerProcess": 20},
            "sessions": {}, "captures": [], "checks": [], "errors": []}

    def checkpoint(self):
        if not self.created_report:
            return
        self.receipt["elapsedSeconds"] = round(time.monotonic() - self.started, 3)
        self.receipt["sessions"] = {
            session.role: {"report": session.report.relative_to(self.report).as_posix(),
                           "audit": (session.report / "audit.json").relative_to(self.report).as_posix(),
                           "pid": session.process.pid if session.process else None,
                           "exitCode": session.process.poll() if session.process else None,
                           "shutdown": session.receipt.get("shutdown"),
                           "executableSha256": session.receipt["executableSha256"],
                           "mainFrames": session.frame_peak}
            for session in self.sessions}
        self.receipt["captures"] = [
            {"session": session.role, **item,
             "png": session.role + "/" + item["png"], "state": session.role + "/" + item["state"],
             "chunks": [session.role + "/" + path for path in item["chunks"]],
             **({"evidence": {**item["evidence"],
                             "png": session.role + "/" + item["evidence"]["png"]}}
                if "evidence" in item else {})}
            for session in self.sessions for item in session.receipt["captures"]]
        menu.write_json(self.report / "audit.json", self.receipt)

    def start_session(self, role, world=None):
        session = FailureSession(self, role, world)
        self.sessions.append(session)
        if len(self.sessions) > 1:
            if self.sessions[0].process.poll() is None or not self.sessions[0].closed:
                raise RuntimeError("Source process must exit before the recovery process starts")
            if session.receipt["executableSha256"] != self.sessions[0].receipt["executableSha256"]:
                raise RuntimeError("Executable changed between owned processes")
        session.start()
        self.checkpoint()
        return session

    def close_session(self, session):
        if session.closed:
            return
        session.stop()
        session.closed = session.process is None or session.process.poll() is not None
        if session.receipt.get("shutdown") != "graceful" or session.receipt.get("exitCode") != 0:
            raise RuntimeError("Owned " + session.role + " process did not exit normally")
        if session.receipt["errors"]:
            raise RuntimeError("Owned process shutdown errors: " + "; ".join(session.receipt["errors"]))
        session.receipt["completed"] = session.lifecycle_finished
        session.receipt["finishedUtc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        session.checkpoint()
        self.checkpoint()

    def install_fixture(self, source, saved_state, target):
        if (not source.closed or source.process.poll() != 0 or target.state["page"] != 0
                or target.state.get("worldId") or target.state.get("worldCount") != 0
                or target.state.get("worldActive") is not False):
            raise RuntimeError("Fixture copy requires a closed source and fresh target title")
        source_saves, target_saves = source.report / "saves", target.report / "saves"
        if saved_state.parent != source_saves or any(target_saves.iterdir()):
            raise RuntimeError("Fixture paths are not the owned source and empty target")
        if snapshot_identity(saved_state) != source.world:
            raise RuntimeError("Fixture belongs to a different logical world")
        files, total = [], 0
        for suffix in SIDECARS:
            original = owned_file(source_saves, saved_state.with_suffix(suffix).name)
            copied = owned_file(target_saves, original.name)
            original_hash = digest(original)
            size = original.stat().st_size
            total += size
            if total > MAX_SNAPSHOT_BYTES:
                raise ValueError("Owned snapshot exceeds its total byte limit")
            with original.open("rb") as incoming, copied.open("xb") as outgoing:
                shutil.copyfileobj(incoming, outgoing, 1024 * 1024)
            if digest(copied) != original_hash or digest(original) != original_hash:
                raise RuntimeError("Owned snapshot changed during copy")
            files.append({"name": original.name, "source": original.relative_to(self.report).as_posix(),
                          "copy": copied.relative_to(self.report).as_posix(),
                          "bytes": size, "sha256": original_hash})
        self.copied_state = target_saves / saved_state.name
        self.original_state = self.copied_state.read_bytes()
        original_path = self.report / "fixture-original.state"
        with original_path.open("xb") as stream:
            stream.write(self.original_state)
        injected_path = self.report / "fixture-invalid.state"
        with injected_path.open("xb") as stream:
            stream.write(BAD_STATE)
        original_hash = hashlib.sha256(self.original_state).hexdigest()
        if digest(original_path) != original_hash:
            raise RuntimeError("Original state evidence readback failed")
        self.fixture = {"worldId": source.world, "files": files,
                        "originalState": original_path.relative_to(self.report).as_posix(),
                        "originalStateSha256": original_hash,
                        "copiedState": self.copied_state.relative_to(self.report).as_posix(),
                        "injectedStateSha256": hashlib.sha256(BAD_STATE).hexdigest(),
                        "injectedStateBytes": len(BAD_STATE),
                        "injectedState": injected_path.relative_to(self.report).as_posix(),
                        "mutationApplied": False,
                        "restored": False}
        self.receipt["fixture"] = self.fixture
        self.checkpoint()
        if digest(self.copied_state) != original_hash:
            raise RuntimeError("Copied state changed before owned mutation")
        menu.atomically(self.copied_state, BAD_STATE)
        self.fixture["mutationApplied"] = True
        self.assert_injected_copy()
        self.checkpoint()

    def assert_injected_copy(self):
        if self.fixture is None or self.copied_state is None:
            raise RuntimeError("No owned malformed fixture exists")
        saves = self.copied_state.parent
        expected_names = {entry["name"] for entry in self.fixture["files"]}
        if {path.name for path in saves.iterdir()} != expected_names:
            raise RuntimeError("Malformed fixture directory gained, lost, or changed snapshot files")
        for entry in self.fixture["files"]:
            original = self.report / entry["source"]
            copied = self.report / entry["copy"]
            if digest(original) != entry["sha256"]:
                raise RuntimeError("Original owned snapshot changed")
            expected = (self.fixture["injectedStateSha256"]
                        if copied == self.copied_state else entry["sha256"])
            if digest(copied) != expected:
                raise RuntimeError("Malformed fixture changed unexpectedly: " + copied.name)
        if self.copied_state.read_bytes() != BAD_STATE:
            raise RuntimeError("Malformed header readback differs from the intended fixture")

    def restore_fixture(self):
        if self.fixture is None or self.copied_state is None:
            return
        current = digest(self.copied_state)
        original_hash = self.fixture["originalStateSha256"]
        if current not in {original_hash, self.fixture["injectedStateSha256"]}:
            raise RuntimeError("Copied state changed externally; original evidence retained without overwriting it")
        if current != original_hash:
            menu.atomically(self.copied_state, self.original_state)
        if (self.copied_state.read_bytes() != self.original_state
                or digest(self.copied_state) != original_hash):
            raise RuntimeError("Exact-byte state restoration failed")
        self.fixture["restored"] = True
        self.fixture["restoredStateSha256"] = original_hash
        self.checkpoint()

    def verify_originals(self):
        if self.fixture is not None:
            for entry in self.fixture["files"]:
                if digest(self.report / entry["source"]) != entry["sha256"]:
                    raise RuntimeError("Original audit snapshot was modified")

    def run(self):
        finished = False
        try:
            self.report.mkdir(parents=True, exist_ok=False)
            self.created_report = True
            self.checkpoint()
            source = self.start_session("source")
            source.create_story()
            self.receipt["worldId"] = source.world
            source.capture("created-pause")
            saved_state = source.save_to_title()
            source.capture("saved-title")
            source.lifecycle_finished = True
            self.close_session(source)

            target = self.start_session("recovery", source.world)
            self.install_fixture(source, saved_state, target)
            target.select_only_world()
            target.open_with_expected_failure()
            self.restore_fixture()
            target.check("copied_state_restored_exactly",
                         sha256=self.fixture["restoredStateSha256"], worldId=source.world)
            target.select_only_world()
            target.permit_world = True
            target.click(202, self.args.load_seconds)
            target.wait_game()
            if target.world != source.world or target.state["worldId"] != source.world:
                raise RuntimeError("Restored list entry opened a different world")
            target.capture("restored-pause")
            target.check("restored_world_list_reopens_same_world", worldId=target.world)
            before_saves = {path.name for path in (target.report / "saves").glob("*.state")}
            new_state = target.save_to_title()
            after_saves = {path.name for path in (target.report / "saves").glob("*.state")}
            if not after_saves - before_saves or new_state.name in before_saves:
                raise RuntimeError("Restored gameplay did not create a new owned save snapshot")
            target.capture("saved-title")
            target.lifecycle_finished = True
            self.close_session(target)
            self.verify_originals()
            finished = True
        except Exception as error:
            self.receipt["errors"].append(str(error))
            self.receipt["traceback"] = traceback.format_exc()
        finally:
            for session in self.sessions:
                if not session.closed:
                    try:
                        self.close_session(session)
                    except Exception as error:
                        self.receipt["errors"].append(session.role + " shutdown: " + str(error))
            # Recovery on failure runs only after the owned processes have exited.
            try:
                if any(session.process and session.process.poll() is None for session in self.sessions):
                    raise RuntimeError("Owned process still alive; fixture restoration deferred")
                self.restore_fixture()
                self.verify_originals()
            except Exception as error:
                self.receipt["errors"].append("Fixture cleanup: " + str(error))
            if self.created_report:
                try:
                    if self.args.chunks == "all":
                        for session in self.sessions:
                            for capture in session.receipt["captures"]:
                                capture["chunks"] = menu.export_chunks(session.report, capture)
                            if session.created_report:
                                session.checkpoint()
                except Exception as error:
                    self.receipt["errors"].append("Lossless evidence export: " + str(error))
                self.receipt["completed"] = finished and not self.receipt["errors"]
                self.receipt["finishedUtc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
                self.checkpoint()
        return 0 if self.receipt["completed"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exe", type=Path, default=menu.BUILD / "game-client-v9/DarkWinter.exe")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--chunks", choices=("all", "none"), default="all")
    parser.add_argument("--max-seconds", type=int, default=600)
    parser.add_argument("--max-captures", type=int, default=8)
    parser.add_argument("--max-frames", type=int, default=36000)
    parser.add_argument("--load-seconds", type=int, default=120)
    args = parser.parse_args()
    if not (60 <= args.max_seconds <= 1800 and 5 <= args.max_captures <= 16
            and 1200 <= args.max_frames <= 108000 and 10 <= args.load_seconds <= 300):
        parser.error("Bounds: seconds60..1800, captures5..16, frames1200..108000, load10..300")
    if os.name != "nt":
        parser.error("The native load failure audit is Windows-only")
    audit = LoadFailureAudit(args)
    result = audit.run()
    print(json.dumps({"completed": audit.receipt["completed"],
                      "report": audit.report.relative_to(menu.ROOT).as_posix(),
                      "worldId": audit.receipt["worldId"],
                      "captures": len(audit.receipt["captures"]),
                      "checks": audit.receipt["checks"], "errors": audit.receipt["errors"]},
                     ensure_ascii=False))
    return result


if __name__ == "__main__":
    raise SystemExit(main())
