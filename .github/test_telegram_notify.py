"""Offline tests for the DarkWinter GitHub -> Telegram notifier."""
import importlib.util
import json
import os
import pathlib
import tempfile
import unittest
from unittest import mock

SCRIPT = pathlib.Path(__file__).with_name("telegram_notify.py")
spec = importlib.util.spec_from_file_location("telegram_notify", SCRIPT)
notify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notify)


def commit(letter, message, parents=1):
    sha = letter * 40
    return {
        "sha": sha,
        "commit": {"message": message, "author": {"name": "Tester"}},
        "parents": [{"sha": "0" * 40}] * parents,
    }


class NotifierTests(unittest.TestCase):
    def test_escapes_user_content(self):
        issue = {
            "number": 12, "title": "<script> & text", "body": "Test <b> & a",
            "user": {"login": "contributor"},
            "html_url": "https://github.com/Axlecsha-sys/DarkWinter-Mod/issues/12",
        }
        message = notify.format_issue(issue, "opened", "Axlecsha-sys/DarkWinter-Mod")
        self.assertIn("&lt;script&gt; &amp; text", message)
        self.assertIn("Test &lt;b&gt; &amp; a", message)
        self.assertNotIn("<script>", message)

    def test_issue_comment_and_link(self):
        issue = {"number": 17, "title": "Crash <bug>"}
        comment = {
            "id": 13, "body": "Ответ: a < b & c",
            "user": {"login": "maintainer"},
            "html_url": "https://github.com/Axlecsha-sys/DarkWinter-Mod/issues/17#issuecomment-13",
        }
        message = notify.format_issue_comment(
            issue, comment, "created", "Axlecsha-sys/DarkWinter-Mod"
        )
        self.assertIn("Issue #17", message)
        self.assertIn("maintainer", message)
        self.assertIn("a &lt; b &amp; c", message)
        self.assertIn("issuecomment-13", message)

    def test_pr_merge_contains_both_branches(self):
        pr = {
            "number": 42, "title": "Fix <fog>", "body": "merge & test",
            "head": {"ref": "feature/fog", "repo": {"full_name": "Axlecsha-sys/DarkWinter-Mod"}},
            "base": {"ref": "dev"}, "user": {"login": "alice"},
            "merged_by": {"login": "bob"},
            "html_url": "https://github.com/Axlecsha-sys/DarkWinter-Mod/pull/42",
        }
        message = notify.format_pr_merge(pr, "Axlecsha-sys/DarkWinter-Mod")
        for part in ["feature/fog", "dev", "alice", "bob", "Fix &lt;fog&gt;", "pull/42"]:
            self.assertIn(part, message)

    def test_direct_merge_commit(self):
        message = notify.format_direct_merge(
            commit("a", "Merge branch 'feature/weather' into dev", parents=2),
            "dev", "Axlecsha-sys/DarkWinter-Mod"
        )
        self.assertIn("feature/weather", message)
        self.assertIn("<code>dev</code>", message)

    def test_large_release_has_safe_fallback(self):
        release = {
            "name": "0.1", "tag_name": "v0.1", "body": "a" * 9000,
            "html_url": "https://github.com/Axlecsha-sys/DarkWinter-Mod/releases/tag/v0.1",
            "assets": [{"name": "bundle-" + str(i) + ("x" * 180),
                        "browser_download_url": "https://github.com/Axlecsha-sys/DarkWinter-Mod/releases/download/v0.1/file-" + str(i)}
                       for i in range(10)],
        }
        text = notify.format_release(release, "Axlecsha-sys/DarkWinter-Mod")
        self.assertLessEqual(len(text), 4096)
        self.assertIn("Полный список", text)
        self.assertIn("v0.1", text)

    def test_initial_scan_baselines_and_deduplicates_branches(self):
        a, b, x, y = ("a" * 40, "b" * 40, "c" * 40, "d" * 40)
        repo = "Axlecsha-sys/DarkWinter-Mod"
        with tempfile.TemporaryDirectory() as tmp, \
             mock.patch.object(notify, "STATE_FILE", os.path.join(tmp, "state.json")), \
             mock.patch.object(notify, "gh_get", return_value={"default_branch": "main"}), \
             mock.patch.object(notify, "branch_heads", side_effect=[
                 {"main": a, "feature/sky": x},
                 {"main": b, "feature/sky": y},
             ]), \
             mock.patch.object(notify, "compare_commits", side_effect=lambda _r, base, head: (
                 [commit("d", "Improve clouds")] if (base, head) == (x, y)
                 else [commit("d", "Improve clouds"), commit("b", "Other change")]
             )), \
             mock.patch.object(notify, "is_pr_merge_commit", return_value=False), \
             mock.patch.object(notify, "send") as send, \
             mock.patch.object(notify.time, "sleep"):
            notify.scan_branches(repo)
            send.assert_not_called()
            notify.scan_branches(repo)
            self.assertEqual(send.call_count, 2)
            self.assertIn("feature/sky", send.call_args_list[0].args[0])
            self.assertIn("<code>main</code>", send.call_args_list[1].args[0])

    def test_failed_send_does_not_advance_branch_cursor(self):
        a, b = ("a" * 40, "b" * 40)
        repo = "Axlecsha-sys/DarkWinter-Mod"
        with tempfile.TemporaryDirectory() as tmp, \
             mock.patch.object(notify, "STATE_FILE", os.path.join(tmp, "state.json")), \
             mock.patch.object(notify, "gh_get", return_value={"default_branch": "main"}), \
             mock.patch.object(notify, "branch_heads", side_effect=[{"main": a}, {"main": b}, {"main": b}]), \
             mock.patch.object(notify, "compare_commits", return_value=[commit("c", "One"), commit("b", "Two")]), \
             mock.patch.object(notify, "is_pr_merge_commit", return_value=False), \
             mock.patch.object(notify, "send", side_effect=[None, RuntimeError("temporary"), None]) as send, \
             mock.patch.object(notify.time, "sleep"):
            notify.scan_branches(repo)
            with self.assertRaises(RuntimeError):
                notify.scan_branches(repo)
            state = notify.load_state()
            self.assertEqual(state["branches"]["main"], a)
            self.assertIn("c" * 40, state["seen"])
            notify.scan_branches(repo)
            self.assertEqual(send.call_count, 3)
            self.assertIn("Two", send.call_args_list[-1].args[0])

    def test_pr_issue_comment_is_ignored(self):
        event = {
            "action": "created",
            "issue": {"number": 1, "title": "PR", "pull_request": {"url": "https://github.com/pull/1"}},
            "comment": {"body": "message"},
        }
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "event.json")
            with open(path, "w", encoding="utf-8") as f:
                json.dump(event, f)
            with mock.patch.dict(os.environ, {
                "GITHUB_REPOSITORY": "Axlecsha-sys/DarkWinter-Mod",
                "GITHUB_EVENT_NAME": "issue_comment",
                "GITHUB_EVENT_PATH": path,
            }), mock.patch.object(notify, "send") as send:
                notify.main()
                send.assert_not_called()

    def test_fast_forward_matching_branch_head(self):
        a, x, y = ("a" * 40, "c" * 40, "d" * 40)
        repo = "Axlecsha-sys/DarkWinter-Mod"
        with tempfile.TemporaryDirectory() as tmp, \
             mock.patch.object(notify, "STATE_FILE", os.path.join(tmp, "state.json")), \
             mock.patch.object(notify, "gh_get", return_value={"default_branch": "main"}), \
             mock.patch.object(notify, "branch_heads", side_effect=[
                 {"main": a, "feature/sky": x},
                 {"main": y, "feature/sky": y},
             ]), \
             mock.patch.object(notify, "compare_commits", return_value=[commit("d", "New sky")]), \
             mock.patch.object(notify, "is_pr_merge_commit", return_value=False), \
             mock.patch.object(notify, "send") as send, \
             mock.patch.object(notify.time, "sleep"):
            notify.scan_branches(repo)
            notify.scan_branches(repo)
            self.assertEqual(send.call_count, 2)
            self.assertIn("feature/sky", send.call_args_list[0].args[0])
            self.assertIn("синхронизация веток", send.call_args_list[1].args[0])

    def test_issue_and_merge_events_are_dispatched(self):
        repo = "Axlecsha-sys/DarkWinter-Mod"
        cases = [
            ("issues", {"action": "opened",
                        "issue": {"number": 5, "title": "Ошибка <fog>",
                                  "user": {"login": "author"}}}, "Issue #5"),
            ("issue_comment", {"action": "created",
                               "issue": {"number": 5, "title": "Ошибка"},
                               "comment": {"body": "Ответ найден",
                                           "user": {"login": "helper"}}}, "Ответ найден"),
            ("pull_request_target", {"action": "closed",
                                     "pull_request": {"number": 7, "merged": True,
                                                      "head": {"ref": "fix/fog"},
                                                      "base": {"ref": "main"}}}, "слияние Pull Request #7"),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "event.json")
            for kind, event, expected in cases:
                with open(path, "w", encoding="utf-8") as f:
                    json.dump(event, f)
                with mock.patch.dict(os.environ, {
                    "GITHUB_REPOSITORY": repo,
                    "GITHUB_EVENT_NAME": kind,
                    "GITHUB_EVENT_PATH": path,
                }), mock.patch.object(notify, "send") as send:
                    notify.main()
                    send.assert_called_once()
                    self.assertIn(expected, send.call_args.args[0])


    def test_format_does_not_add_emoji(self):
        repo = "Axlecsha-sys/DarkWinter-Mod"
        messages = [
            notify.format_commit(commit("a", "Update weather"), "dev", repo),
            notify.format_direct_merge(commit("b", "Merge branch 'dev' into main", 2), "main", repo),
            notify.format_fast_forward("dev", "main", "c" * 40, repo),
            notify.format_pr_merge({"number": 2, "head": {"ref": "dev"}, "base": {"ref": "main"}}, repo),
            notify.format_issue({"number": 3, "title": "Bug"}, "opened", repo),
            notify.format_issue_comment({"number": 3}, {"body": "Reply"}, "created", repo),
            notify.format_release({"name": "v1", "tag_name": "v1"}, repo),
        ]
        for message in messages:
            self.assertFalse(any(0x1F300 <= ord(char) <= 0x1FAFF for char in message),
                             "A decorative emoji was added to a Telegram message")



if __name__ == "__main__":
    unittest.main()
