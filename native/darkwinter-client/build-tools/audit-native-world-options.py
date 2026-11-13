"""Audit native world-option transactions and restart persistence in owned saves.

Windows: python audit-native-world-options.py --report build/core/world-options-audit
Creates one fresh world, tests its UI transactions, stops the source process,
then copies that complete owned snapshot into a second client's initially empty
saves directory after observing TITLE. No user saves or arbitrary lab actions.
PNG evidence remains original; optional lossless chunks are exported after exit.
"""
import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import shlex
import shutil
import time
import traceback

_spec = importlib.util.spec_from_file_location(
    "darkwinter_options_menu_helpers", Path(__file__).with_name("audit-native-ui.py"))
menu = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(menu)

NAMES = {0: "title", 1: "game", 2: "pause", 5: "worlds", 6: "create",
         7: "loading", 13: "fault", 15: "world-options", 40: "world-mode",
         41: "story-setup", 49: "rules", 58: "difficulty-confirm", 59: "discard-confirm"}
ROUTES = {(0, 10): {5}, (5, 201): {40}, (40, 700): {41}, (41, 235): {6},
          (6, 301): {7, 1}, (7, 900): {0}, (2, 1): {1}, (2, 4): {0},
          (2, 6): {15}, (5, 1000): {5}, (5, 202): {7, 1},
          (15, 250): {2, 58}, (15, 251): {2}, (15, 758): {49},
          (49, 740): {15}, (49, 741): {15, 59},
          (58, 260): {2}, (58, 261): {15}, (59, 262): {15}, (59, 263): {49}}
WORLD_BUTTONS = {"world.gameMode": 252, "world.personalGameMode": 253,
                 "world.commands": 254, "world.difficulty": 255, "world.difficultyLocked": 256}
MAX_ACTIONS = 240
MAX_SNAPSHOT_FILE = 512 * 1024 * 1024
MAX_SNAPSHOT_TOTAL = 1024 * 1024 * 1024
MAX_CAPTURE_TOTAL = 256 * 1024 * 1024
TRANSIENT = ("world.draft.", "world.ruleDraft.")
DYNAMIC = {"world.daySeconds", "world.flying"}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def numeric(value):
    return type(value) in (int, float) and math.isfinite(value)


def sha_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_catalogue(path):
    require(path.is_file() and path.stat().st_size <= 256 * 1024, "Missing or oversized rule catalogue")
    result = {}
    integer_field = 410
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        cells = shlex.split(line)
        require(len(cells) == 8 and len(result) < 256, "Malformed or oversized rule catalogue")
        name, _, kind, initial, lower, upper, _, _ = cells
        require(re.fullmatch(r"[A-Za-z0-9_.-]{1,96}", name) and name not in result
                and kind in ("boolean", "integer"), "Invalid rule identity")
        low, high = int(lower), int(upper)
        value = int(initial == "true") if kind == "boolean" else int(initial)
        require(low <= value <= high and -(2**31) <= low <= high < 2**31,
                "Invalid rule catalogue range")
        if kind == "boolean":
            require(initial in ("true", "false") and (low, high) == (0, 1), "Invalid boolean rule")
        field = None
        if kind == "integer":
            require(integer_field < 450, "Too many integer rule fields")
            field, integer_field = integer_field, integer_field + 1
        result["rule." + name] = {"default": value, "minimum": low, "maximum": high,
                                  "type": kind, "field": field}
    require("rule.advance_time" in result and "rule.max_snow_accumulation_height" in result,
            "Required runtime audit rules absent from the real catalogue")
    return result


def read_options(path):
    require(path.is_file() and path.stat().st_size <= 1024 * 1024, "Missing or oversized .options")
    values, seen = {}, set()
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        parts = shlex.split(line)
        if not parts or parts[0] not in ("option", "text"):
            continue
        require(len(parts) == 3, "Malformed saved world option")
        kind, key, raw = parts
        require(not key.startswith(TRANSIENT), "Transient staging leaked to the saved .options")
        require((kind, key) not in seen, "Duplicate saved world option")
        seen.add((kind, key))
        if kind == "option":
            value = float(raw)
            require(numeric(value), "Nonfinite saved world option")
            values[key] = value
    return values


def stable_settings(state):
    return {key: value for key, value in state["worldSettings"].items() if key not in DYNAMIC}


def allowed_operation(operation, state):
    """The scenario cannot issue movement, clock injection, arbitrary clicks or external operations."""
    if operation == "capture":
        return state["page"] in {2, 15, 49, 58, 59}
    match = re.fullmatch(r"click (\d+) (\d+)", operation)
    if match:
        page, identifier = map(int, match.groups())
        if page != state["page"]:
            return False
        if (page, identifier) in ROUTES:
            return True
        if page == 15:
            return identifier in WORLD_BUTTONS.values()
        if page == 49:
            if identifier == 405:
                return True
            return any(b.get("id") == identifier and (
                       410 <= identifier < 450 or
                       (14000 <= identifier < 14256 and b.get("optionKey", "").startswith("rule.")))
                       for b in state["buttons"])
        return False
    match = re.fullmatch(r"pointer (\d+) (\d+) (\d+) move", operation)
    if match:
        page, x, y = map(int, match.groups())
        return page == state["page"] and page not in {1, 7} and 0 <= x < menu.GUI_SIZE[0] and 0 <= y < menu.GUI_SIZE[1]
    if operation == "key 27":
        return state["page"] == 1
    editing = state["page"] == 49 and (state.get("focusedField") == 405
                                      or 410 <= state.get("focusedField", -1) < 450)
    if operation in ("key 65 2", "key 8"):
        return editing
    if operation.startswith("text ") and editing:
        try:
            value = json.loads(operation[5:])
        except (ValueError, TypeError):
            return False
        return isinstance(value, str) and len(value) <= 128 and all(32 <= ord(c) < 127 for c in value)
    return False


class WorldOptionsAudit(menu.Audit):
    def __init__(self, args, deadline, budget, expected_world=None):
        super().__init__(args)
        self.deadline = deadline
        self.budget = budget
        self.own_world = expected_world
        self.load_authorized = False
        self.cleaning = False
        self.closed = False
        self.catalogue = read_catalogue(self.exe.parent / "ui-game-rules.tsv")
        self.receipt["scope"] = "World option/rule transactions in one owned isolated world"
        self.receipt["limits"].update(actions=MAX_ACTIONS, worldLoadSeconds=args.load_seconds,
                                      gracefulCleanupSeconds=90, rawPngBytes=MAX_CAPTURE_TOTAL)
        self.receipt["catalogueSha256"] = sha_file(self.exe.parent / "ui-game-rules.tsv")

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
        require(state.get("pid") == self.process.pid and state.get("page") in NAMES,
                "Unexpected native process identity or page")
        require(isinstance(state.get("buttons"), list) and len(state["buttons"]) <= 8192,
                "Invalid native button tree")
        require((state.get("clientWidth"), state.get("clientHeight")) == menu.SIZE
                and (state.get("uiWidth"), state.get("uiHeight")) == menu.GUI_SIZE,
                "Native audit geometry differs from 2560x1440 / 854x480")
        world = state.get("worldId")
        require(isinstance(world, str) and len(world) <= 128, "Invalid world identity")
        require(type(state.get("worldActive")) is bool, "Missing worldActive telemetry")
        if world:
            require(self.load_authorized, "World activated before its authorized isolated load")
            if self.own_world is not None:
                require(world == self.own_world, "Audit switched to a different world")
        values = state.get("worldSettings")
        require(isinstance(values, dict) and len(values) <= 1024, "Missing canonical world settings")
        for key, value in values.items():
            require(isinstance(key, str) and key.startswith(("world.", "rule."))
                    and not key.startswith(TRANSIENT) and numeric(value), "Invalid canonical world setting")
        for field in ("worldOptionsResult", "worldRulesResult", "errorText"):
            require(isinstance(state.get(field), str), "Missing transaction telemetry: " + field)
        if state["worldActive"]:
            require(world and type(state.get("playerMode")) is int and 0 <= state["playerMode"] <= 3,
                    "Active world lacks its player mode")
            for field in ("flying", "mayBuild", "noClip"):
                require(type(state.get(field)) is bool, "Missing live ability: " + field)
            for field in ("daySeconds", "seconds"):
                require(numeric(state.get(field)) and state[field] >= 0, "Invalid clock: " + field)
            for key in WORLD_BUTTONS:
                require(key in values, "Missing canonical option: " + key)
        if state["page"] == 1:
            require(state["worldActive"], "Gameplay has no active world")
            if self.own_world is None:
                self.own_world = world
                self.receipt["worldId"] = world
        self.state = state
        self.visited.add(state["page"])
        if state["page"] == 13 and not self.cleaning:
            raise RuntimeError("Unexpected world load failure: " + state["errorText"])
        return state

    def command(self, operation, expected=None, timeout=20):
        require(self.state is not None and allowed_operation(operation, self.state),
                "Operation is outside the explicit world-options audit scope: " + operation)
        allowance = MAX_ACTIONS + (32 if self.cleaning else 0)
        require(len(self.receipt["actions"]) < allowance, "World-options audit action limit reached")
        return super().command(operation, expected, timeout)

    def check(self, name, **evidence):
        self.receipt["checks"].append({"check": name, "result": "pass", **evidence})
        self.checkpoint()

    def raw_button(self, identifier):
        found = [button for button in self.state["buttons"] if button.get("id") == identifier]
        require(len(found) == 1, "Expected exactly one visible button " + str(identifier))
        return found[0]

    def option_button(self, key):
        found = [button for button in self.state["buttons"] if button.get("optionKey") == key]
        require(len(found) == 1 and numeric(found[0].get("value")), "Missing visible draft value: " + key)
        return found[0]

    def click(self, identifier, targets=None, timeout=20):
        self.poll()
        page = self.state["page"]
        self.button(identifier)
        if targets is None:
            targets = ROUTES.get((page, identifier), {page})
        result = self.command("click %d %d" % (page, identifier),
                              lambda s: s["page"] in targets, timeout)
        if result["page"] not in {1, 7}:
            self.settle()
        return self.state

    def set_option(self, key, target):
        require(self.state["page"] == 15 and key in WORLD_BUTTONS, "World option outside page15")
        for _ in range(4):
            button = self.option_button(key)
            require(button["id"] == WORLD_BUTTONS[key], "World option identifier differs from reviewed source")
            if button["value"] == target:
                return
            self.click(button["id"], {15})
        require(self.option_button(key)["value"] == target, "World option cycle missed requested value")

    def edit(self, identifier, text):
        self.click(identifier, {49})
        require(self.state.get("focusedField") == identifier, "Rule text field did not receive focus")
        self.command("key 65 2")
        self.command("key 8")
        self.command("text " + json.dumps(text))
        self.settle()
        require(self.state.get("focusedField") == identifier and self.state.get("focusedValue") == text,
                "Rule field contents differ from typed text")

    def search_rule(self, name):
        require(self.state["page"] == 49 and "rule." + name in self.catalogue, "Unknown rule search")
        self.edit(405, name)
        rule = self.catalogue["rule." + name]
        if rule["type"] == "integer":
            return self.raw_button(rule["field"])
        return self.option_button("rule." + name)

    def toggle_rule(self, name, target):
        button = self.search_rule(name)
        require(self.catalogue["rule." + name]["type"] == "boolean", "Expected a boolean rule")
        if button["value"] != target:
            self.click(button["id"], {49})
        require(self.option_button("rule." + name)["value"] == target, "Boolean rule draft did not change")

    def capture(self, suffix):
        require(self.budget["captures"] < self.args.max_captures, "World-options screenshot limit reached")
        self.neutral_pointer()
        page = self.state["page"]
        label = "p%d-%s-%s" % (page, NAMES[page], suffix)
        require(not any(row["label"] == label for row in self.receipt["captures"]), "Duplicate capture label")
        filename = "lab-%d-%d.png" % (self.process.pid, self.sequence + 1)
        state = self.command("capture", lambda s: s.get("capture") == filename)
        require(state["page"] == page, "Page changed during stable UI capture")
        raw = menu.png_data(self.report / filename)
        self.budget["captures"] += 1
        self.budget["bytes"] += len(raw)
        require(self.budget["bytes"] <= MAX_CAPTURE_TOTAL, "World-options total screenshot byte limit")
        state_name = label + ".state.json"
        menu.write_json(self.report / state_name, state)
        self.receipt["captures"].append({"label": label, "page": page, "png": filename,
                                        "state": state_name, "sha256": hashlib.sha256(raw).hexdigest(),
                                        "bytes": len(raw), "width": menu.SIZE[0], "height": menu.SIZE[1],
                                        "frame": state["frame"], "chunks": []})
        self.checkpoint()

    def pause(self):
        require(self.state["page"] == 1, "Pause must start in owned gameplay")
        self.command("key 27", lambda s: s["page"] == 2)
        self.settle()

    def wait_game(self):
        self.wait(lambda s: s["page"] == 1, self.args.load_seconds, "owned world gameplay")
        require(self.state["worldId"] == self.own_world, "Wrong world after load")
        self.pause()

    def assert_abilities(self, mode, flying, may_build, no_clip):
        require(self.state["playerMode"] == mode and self.state["flying"] is flying
                and self.state["mayBuild"] is may_build and self.state["noClip"] is no_clip,
                "Live player abilities do not match the applied personal mode")

    def apply_options(self):
        self.click(250, {2})
        require(self.state["worldOptionsResult"] == "applied", "Backend did not report applied world options")

    def commit_time(self, enabled):
        self.click(758)
        self.toggle_rule("advance_time", int(enabled))
        self.click(740)
        require(self.state["worldRulesResult"] == "applied"
                and self.state["worldSettings"]["rule.advance_time"] == int(enabled),
                "Game-rule Done did not independently commit time rule")

    def clock_check(self, frozen):
        require(self.state["page"] == 2, "Clock check begins paused")
        before = {key: self.state[key] for key in ("seconds", "daySeconds", "frame")}
        self.click(1)
        after = self.wait(lambda s: s["page"] == 1 and s["seconds"] >= before["seconds"] + .75
                          and s["frame"] >= before["frame"] + 3, 15, "live world clock progression")
        observed = {key: after[key] for key in before}
        self.pause()
        if frozen:
            require(abs(observed["daySeconds"] - before["daySeconds"]) <= 1e-6,
                    "advance_time=0 allowed the calendar to advance")
        else:
            require(observed["daySeconds"] >= before["daySeconds"] + .5,
                    "advance_time=1 did not resume the calendar")
        self.check("calendar_frozen_while_playtime_advances" if frozen else "calendar_resumes",
                   before=before, after=observed)

    def source_scenario(self):
        require(self.state["page"] == 0 and not self.state["worldActive"], "Expected fresh inactive TITLE")
        self.click(10)
        require(self.state.get("worldCount") == 0, "Source audit saves must start empty")
        self.click(201); self.click(700); self.click(235)
        self.load_authorized = True
        self.click(301, timeout=self.args.load_seconds)
        self.wait_game()
        self.assert_abilities(0, False, True, False)
        baseline = stable_settings(self.state)
        require(baseline["world.gameMode"] == baseline["world.personalGameMode"] == 0
                and baseline["world.commands"] == 0, "Fresh story world is not unprivileged survival")
        self.click(6)
        self.capture("baseline")
        changed_difficulty = (int(baseline["world.difficulty"]) + 1) % 4
        self.set_option("world.difficulty", changed_difficulty)
        require(stable_settings(self.state) == baseline, "Draft changed the canonical world before Apply")
        self.click(251)
        require(stable_settings(self.state) == baseline, "Cancel committed draft world options")
        self.click(6)
        require(self.option_button("world.difficulty")["value"] == baseline["world.difficulty"],
                "Reopening world options retained a cancelled draft")
        self.check("cancel_discards_world_option_draft", canonical=baseline)

        self.set_option("world.commands", 1); self.apply_options()
        require(self.state["worldSettings"]["world.commands"] == 1, "Commands were not applied")
        self.click(6); self.set_option("world.gameMode", 1)
        require(self.option_button("world.personalGameMode")["value"] == 0,
                "Changing the default mode changed the personal draft")
        self.apply_options()
        require(self.state["worldSettings"]["world.gameMode"] == 1
                and self.state["worldSettings"]["world.personalGameMode"] == 0,
                "Default game mode silently changed the current player")
        self.assert_abilities(0, False, True, False)
        self.check("default_mode_does_not_change_personal_mode", canonical=stable_settings(self.state))

        self.click(6); self.set_option("world.personalGameMode", 3); self.apply_options()
        require(self.state["worldSettings"]["world.gameMode"] == 1
                and self.state["worldSettings"]["world.personalGameMode"] == 3, "Personal mode was not committed")
        self.assert_abilities(3, True, False, True)
        self.check("apply_changes_actual_player_abilities", playerMode=3, flying=True, mayBuild=False, noClip=True)

        # Fresh spawn is clear and 0.0001 above its support; gameplay has stayed paused.
        position = {key: self.state[key] for key in ("x", "y", "z")}
        self.click(6); self.set_option("world.personalGameMode", 1); self.apply_options()
        self.assert_abilities(1, False, True, False)
        require(self.state["worldSettings"]["world.gameMode"] == 1
                and self.state["worldSettings"]["world.personalGameMode"] == 1
                and all(self.state[key] == value for key, value in position.items()),
                "Grounded Spectator-to-Creative transition changed the default mode or position")
        self.check("spectator_to_creative_near_ground_stops_flight", position=position,
                   playerMode=1, flying=False, mayBuild=True, noClip=False)
        self.click(6); self.set_option("world.personalGameMode", 3); self.apply_options()
        self.assert_abilities(3, True, False, True)
        require(self.state["worldSettings"]["world.gameMode"] == 1
                and self.state["worldSettings"]["world.personalGameMode"] == 3
                and all(self.state[key] == value for key, value in position.items()),
                "Returning to Spectator changed the default mode or position")
        self.check("creative_to_spectator_restores_flight", position=position,
                   playerMode=3, flying=True, mayBuild=False, noClip=True)

        self.click(6); self.click(758)
        self.capture("baseline-rules")
        before_rules = stable_settings(self.state)
        rule = self.search_rule("max_snow_accumulation_height")
        identifier = rule["id"]
        self.click(identifier, {49})
        original_text = self.state["focusedValue"]
        invalid = str(self.catalogue["rule.max_snow_accumulation_height"]["maximum"] + 1)
        self.edit(identifier, invalid)
        require(not self.raw_button(740).get("enabled"), "Done remained enabled for an out-of-range integer rule")
        require(stable_settings(self.state) == before_rules, "Invalid integer rule changed applied settings")
        self.check("invalid_integer_rule_cannot_apply", field=identifier, entered=invalid, doneEnabled=False)
        self.edit(identifier, original_text)
        require(self.raw_button(740).get("enabled"), "Restoring a valid integer did not re-enable Done")
        self.toggle_rule("advance_time", 0)
        self.click(741, {59}); self.capture("unsaved-rules")
        self.click(263, {49})
        require(self.search_rule("advance_time")["value"] == 0, "Continue editing discarded rule draft")
        require(stable_settings(self.state) == before_rules, "Cancel confirmation applied rules")
        self.click(741, {59}); self.click(262, {15})
        require(stable_settings(self.state) == before_rules, "Discard confirmation committed rules")
        self.check("rule_cancel_confirmation_preserves_or_discards_draft", canonical=before_rules)

        self.set_option("world.difficulty", changed_difficulty)
        self.commit_time(False)
        require(self.option_button("world.difficulty")["value"] == changed_difficulty
                and self.state["worldSettings"]["world.difficulty"] == baseline["world.difficulty"],
                "Rules Done unexpectedly committed or erased outer world-option draft")
        self.click(251)
        require(self.state["worldSettings"]["rule.advance_time"] == 0
                and self.state["worldSettings"]["world.difficulty"] == baseline["world.difficulty"],
                "Outer Cancel rolled back committed rules or applied unrelated draft")
        self.check("rules_done_is_independent_of_outer_cancel", canonical=stable_settings(self.state))
        self.clock_check(True)
        self.click(6); self.commit_time(True); self.click(251)
        self.clock_check(False)

        self.click(6)
        before_lock = stable_settings(self.state)
        self.set_option("world.difficulty", changed_difficulty)
        self.set_option("world.difficultyLocked", 1)
        self.click(250, {58})
        require(stable_settings(self.state) == before_lock, "Unconfirmed difficulty lock committed")
        self.capture("difficulty-lock")
        self.click(261, {15})
        require(stable_settings(self.state) == before_lock, "Declining difficulty lock changed the world")
        self.click(250, {58}); self.click(260, {2})
        require(self.state["worldOptionsResult"] == "applied"
                and self.state["worldSettings"]["world.difficulty"] == changed_difficulty
                and self.state["worldSettings"]["world.difficultyLocked"] == 1,
                "Confirmed lock did not commit difficulty atomically")
        self.click(6)
        require(not self.raw_button(255).get("enabled") and not self.raw_button(256).get("enabled"),
                "Locked difficulty remained editable after reopening")
        self.check("difficulty_lock_requires_confirmation_and_is_persistent",
                   difficulty=changed_difficulty, locked=True)
        self.commit_time(False)
        self.capture("committed")
        self.click(251)
        self.assert_abilities(3, True, False, True)
        self.expected_saved = dict(self.state["worldSettings"])
        self.save_to_title()

    def save_to_title(self):
        require(self.state["page"] == 2, "Save-and-return requires owned PAUSE")
        self.click(4, timeout=60)
        require(not self.state["worldActive"] and self.state.get("worldCount") == 1,
                "Save-and-return did not leave one isolated saved world")
        self.check("save_and_return_to_title", worldId=self.own_world)

    def reopen_scenario(self, expected):
        require(self.state["page"] == 0 and not self.state["worldActive"], "Reopen must begin at inactive TITLE")
        self.click(10)
        require(self.state.get("worldCount") == 1, "Copied snapshot did not appear as one world")
        self.click(1000)
        self.load_authorized = True
        self.click(202, timeout=self.args.load_seconds)
        self.wait_game()
        actual = self.state["worldSettings"]
        for key, value in expected.items():
            if key == "world.daySeconds":
                require(abs(actual[key] - value) <= 1e-6, "Saved frozen calendar changed on restart")
            else:
                require(actual.get(key) == value, "Restart lost canonical option " + key)
        self.assert_abilities(3, True, False, True)
        self.check("new_process_reopens_same_world_and_canonical_settings",
                   worldId=self.own_world, expected=expected, observed=actual)
        self.click(6)
        for key in WORLD_BUTTONS:
            require(self.option_button(key)["value"] == expected[key], "Restart UI disagrees with saved " + key)
        require(not self.raw_button(255).get("enabled") and not self.raw_button(256).get("enabled"),
                "Restart lost difficulty lock")
        self.capture("reopened")
        self.click(758)
        require(self.search_rule("advance_time")["value"] == 0, "Restart UI lost committed time rule")
        self.click(741, {15}); self.click(251)
        self.clock_check(True)
        self.capture("reopened-pause")
        self.save_to_title()

    def stop(self):
        self.cleaning = True
        self.deadline = time.monotonic() + 70
        try:
            if self.process is not None and self.process.poll() is None:
                self.poll()
                if self.state and self.state["page"] == 7:
                    self.wait(lambda s: s["page"] != 7 or any(
                        b.get("id") == 900 and b.get("enabled") for b in s["buttons"]),
                        10, "bounded cleanup loading return")
                    if self.state["page"] == 7:
                        self.click(900, {0}, timeout=10)
                if self.state and self.state["page"] == 1:
                    self.pause()
                for _ in range(4):
                    page = self.state["page"] if self.state else -1
                    if page == 49:
                        self.click(741, {15, 59})
                    elif page == 59:
                        self.click(262, {15})
                    elif page == 58:
                        self.click(261, {15})
                    elif page == 15:
                        self.click(251, {2})
                    else:
                        break
                if self.state and self.state["page"] == 2:
                    self.click(4, {0}, timeout=60)
        except Exception as error:
            self.receipt["errors"].append("Normal world cleanup: " + str(error))
        finally:
            super().stop()

    def close(self, successful):
        if self.closed:
            return
        self.closed = True
        try:
            self.stop()
        except Exception as error:
            self.receipt["errors"].append("Shutdown: " + str(error))
        if self.created_report:
            if self.process is not None and self.process.poll() is None:
                self.receipt["errors"].append("Owned process still running after bounded cleanup")
            self.receipt["completed"] = bool(successful and not self.receipt["errors"]
                                              and self.receipt.get("shutdown") == "graceful"
                                              and self.receipt.get("exitCode") == 0)
            self.receipt["finishedUtc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            self.checkpoint()


def snapshot_of(source):
    require(source.receipt["completed"] and source.process.poll() == 0,
            "Snapshot requires a successfully stopped source client")
    saves = source.report / "saves"
    states = sorted(saves.glob("*.state"), key=lambda path: (path.stat().st_mtime_ns, path.name))
    require(1 <= len(states) <= 16, "Expected a bounded owned saved snapshot set")
    for state in states:
        info = state.with_suffix(".info")
        require(info.is_file() and info.stat().st_size <= 65536, "Snapshot metadata missing")
        parts = shlex.split(info.read_text(encoding="utf-8"))
        require(len(parts) == 3 and parts[2] == source.own_world, "Snapshot metadata identity mismatch")
    chosen = states[-1]
    require(chosen.stat().st_size <= 1024 * 1024
            and chosen.read_text(encoding="utf-8").split()[0] == "DWSTATE3",
            "Expected a complete native snapshot state")
    files, total = [], 0
    for suffix in (".dwv", ".dwg", ".state", ".info", ".options", ".png"):
        path = chosen.with_suffix(suffix)
        if suffix == ".png" and not path.exists():
            continue
        require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(saves.resolve()),
                "Owned snapshot companion is missing or escaped: " + suffix)
        size = path.stat().st_size
        total += size
        require(0 < size <= MAX_SNAPSHOT_FILE and total <= MAX_SNAPSHOT_TOTAL, "Owned snapshot size limit")
        files.append({"name": path.name, "bytes": size, "sha256": sha_file(path)})
    persisted = read_options(chosen.with_suffix(".options"))
    for key, value in source.expected_saved.items():
        fallback = source.catalogue[key]["default"] if key in source.catalogue else None
        actual = persisted.get(key, fallback)
        require(actual is not None and abs(actual - value) <= 1e-6, "Saved .options differs for " + key)
    result = {"worldId": source.own_world, "state": chosen.name, "files": files,
              "expected": source.expected_saved, "persistedOptions": persisted}
    menu.write_json(source.report / "snapshot.json", result)
    return result


def copy_snapshot(source, target, snapshot):
    require(source.process.poll() == 0 and target.state["page"] == 0
            and not target.state["worldActive"], "Snapshot copy requires stopped source and fresh target TITLE")
    destination = target.report / "saves"
    require(not destination.is_symlink() and destination.resolve().is_relative_to(target.report.resolve()),
            "Target saves directory escaped its owned report")
    require(not any(destination.iterdir()), "Fresh target saves directory is not empty")
    for item in snapshot["files"]:
        original = source.report / "saves" / item["name"]
        copied = destination / item["name"]
        require(original.name == item["name"] and not original.is_symlink()
                and original.resolve().is_relative_to((source.report / "saves").resolve()),
                "Snapshot source is outside the owned source saves")
        require(sha_file(original) == item["sha256"], "Source snapshot changed after its manifest")
        shutil.copyfile(original, copied)
        require(copied.stat().st_size == item["bytes"] and sha_file(copied) == item["sha256"],
                "Copied snapshot failed byte/hash verification")
    target.receipt["copiedSnapshot"] = snapshot
    target.check("owned_snapshot_copied_only_after_fresh_title", sourcePid=source.process.pid,
                 targetPid=target.process.pid, files=snapshot["files"])


def run(args):
    report = menu.beneath_build(args.report)
    require(not report.exists(), "A fresh world-options report directory is required")
    report.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    deadline = started + args.max_seconds
    budget = {"captures": 0, "bytes": 0}
    receipt = {"schema": 1, "completed": False, "phase": "world-options",
               "scope": "One owned world; two fresh processes; verified isolated snapshot copy",
               "visualParity": "not_assessed", "sessions": [], "errors": [],
               "limits": {"seconds": args.max_seconds, "captures": args.max_captures,
                          "actionsPerProcess": MAX_ACTIONS, "worldLoadSeconds": args.load_seconds,
                          "cleanupSecondsPerProcess": 90, "losslessExportSeconds": 180}}
    sessions = []
    try:
        for name in ("source", "reopen"):
            require(time.monotonic() < deadline, "Overall world-options audit time limit reached")
            child = argparse.Namespace(**vars(args))
            child.report, child.phase, child.max_scrolls = report / name, "world-options", 1
            session = WorldOptionsAudit(child, deadline, budget,
                                        sessions[0].own_world if sessions else None)
            if sessions:
                require(session.receipt["executableSha256"] == sessions[0].receipt["executableSha256"],
                        "Native executable changed between source and reopen")
            sessions.append(session)
            successful = False
            try:
                session.start()
                if name == "source":
                    session.source_scenario()
                else:
                    copy_snapshot(sessions[0], session, snapshot)
                    session.reopen_scenario(snapshot["expected"])
                successful = True
            except Exception as error:
                session.receipt["errors"].append(str(error))
                session.receipt["traceback"] = traceback.format_exc()
                raise
            finally:
                session.close(successful)
            require(session.receipt["completed"], "Native session did not complete with a normal exit: " + name)
            if name == "source":
                snapshot = snapshot_of(session)
        receipt["worldId"] = sessions[0].own_world
    except Exception as error:
        receipt["errors"].append(str(error))
        receipt["traceback"] = traceback.format_exc()
    finally:
        export_deadline = time.monotonic() + 180
        for session in sessions:
            if not session.closed:
                session.close(False)
            if args.chunks == "all" and session.created_report:
                try:
                    for capture in session.receipt["captures"]:
                        require(time.monotonic() < export_deadline, "Lossless export time limit")
                        capture["chunks"] = menu.export_chunks(session.report, capture)
                    session.checkpoint()
                except Exception as error:
                    receipt["errors"].append("Lossless evidence export: " + str(error))
            receipt["sessions"].append({
                "name": session.report.name, "audit": (session.report / "audit.json").relative_to(report).as_posix(),
                "completed": session.receipt["completed"], "pid": session.receipt.get("pid"),
                "shutdown": session.receipt.get("shutdown"), "exitCode": session.receipt.get("exitCode"),
                "checks": session.receipt["checks"], "captures": session.receipt["captures"],
                "errors": session.receipt["errors"]})
        receipt["completed"] = len(sessions) == 2 and all(
            item["completed"] for item in receipt["sessions"]) and not receipt["errors"]
        receipt["elapsedSeconds"] = round(time.monotonic() - started, 3)
        receipt["captureCount"], receipt["rawPngBytes"] = budget["captures"], budget["bytes"]
        receipt["finishedUtc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        menu.write_json(report / "audit.json", receipt)
    print(json.dumps({"completed": receipt["completed"], "report": report.relative_to(menu.ROOT).as_posix(),
                      "captures": budget["captures"], "errors": receipt["errors"]}, ensure_ascii=False))
    return 0 if receipt["completed"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exe", type=Path, default=menu.BUILD / "game-client-v9/DarkWinter.exe")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--chunks", choices=("all", "none"), default="all")
    parser.add_argument("--max-seconds", type=int, default=600)
    parser.add_argument("--max-captures", type=int, default=12)
    parser.add_argument("--load-seconds", type=int, default=120)
    args = parser.parse_args()
    if not (120 <= args.max_seconds <= 1800 and 7 <= args.max_captures <= 32
            and 10 <= args.load_seconds <= 300):
        parser.error("Bounds: seconds120..1800, captures7..32, load10..300")
    if os.name != "nt":
        parser.error("The native world-options audit launcher is Windows-only")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
