#!/usr/bin/env python3
"""DarkWinter GitHub Actions -> Telegram notifications (Python standard library)."""
import html
import json
import os
import re
import sys
import time
from urllib import error, parse, request


def safe(value, limit=1200):
    text = str(value or "").strip()
    if len(text) > limit:
        text = text[:limit - 1] + "…"
    return html.escape(text, quote=True)


def link(url, label):
    return '<a href="' + html.escape(url, quote=True) + '">' + safe(label, 120) + "</a>"


def compare_commits(repo, base, head):
    """Fetch every commit in base..head, including pushes of more than 20 commits."""
    token = os.environ.get("GITHUB_TOKEN", "")
    if not token:
        raise RuntimeError("GITHUB_TOKEN is missing")
    endpoint = parse.quote(base + "..." + head, safe=".")
    commits = []
    for page in range(1, 101):
        url = ("https://api.github.com/repos/" + repo + "/compare/" +
               endpoint + "?per_page=100&page=" + str(page))
        req = request.Request(url, headers={
            "Accept": "application/vnd.github+json",
            "Authorization": "Bearer " + token,
            "User-Agent": "DarkWinter-Telegram-Notifier",
        })
        try:
            with request.urlopen(req, timeout=30) as response:
                data = json.load(response)
        except error.HTTPError as exc:
            raise RuntimeError("GitHub compare API returned HTTP " + str(exc.code)) from None
        batch = data.get("commits", [])
        commits.extend(batch)
        if len(batch) < 100:
            return commits
    raise RuntimeError("More than 10,000 commits: refusing silent truncation")


def get_push_commits(event, repo):
    if event.get("deleted"):
        return []
    before, after = str(event.get("before") or ""), str(event.get("after") or "")
    if not re.fullmatch(r"[0-9a-fA-F]{40,64}", after):
        raise RuntimeError("Invalid push SHA")
    if before == after:
        return []
    listed = event.get("commits") or []
    # GitHub push webhook commit lists can be truncated at 20 entries.
    if not event.get("created") and not event.get("forced") and 0 < len(listed) < 20:
        commits = listed
    else:
        if event.get("created") or not before or set(before) == {"0"}:
            # Avoid publishing the whole repository history when a branch is created.
            base = event.get("repository", {}).get("default_branch", "main")
        else:
            if not re.fullmatch(r"[0-9a-fA-F]{40,64}", before):
                raise RuntimeError("Invalid previous push SHA")
            base = before
        commits = compare_commits(repo, base, after)
    result, seen = [], set()
    for commit in commits:
        sha = commit.get("sha") or commit.get("id")
        if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-fA-F]{40,64}", sha):
            raise RuntimeError("Invalid commit SHA")
        if sha not in seen:
            result.append(commit)
            seen.add(sha)
    return result


def format_commit(commit, branch, repo):
    sha = commit.get("sha") or commit.get("id")
    nested = commit.get("commit") or {}
    message = str(commit.get("message") or nested.get("message") or "").strip()
    title, _, body = message.partition("\n")
    author = commit.get("author") or {}
    inner_author = nested.get("author") or {}
    name = author.get("name") or author.get("login") or inner_author.get("name") or "Неизвестен"
    url = "https://github.com/" + repo + "/commit/" + sha
    lines = [
        "<b>DarkWinter — новый коммит</b>",
        "<b>Ветка:</b> <code>" + safe(branch, 230) + "</code>",
        "<b>Автор:</b> " + safe(name, 120),
        "<b>SHA:</b> <code>" + sha[:12] + "</code>",
        "",
        "<b>" + safe(title or "Без описания", 320) + "</b>",
    ]
    if body.strip():
        lines += ["", safe(body, 1300)]
    return "\n".join(lines + [link(url, "Посмотреть коммит")])


def format_release(release, repo):
    name = release.get("name") or release.get("tag_name") or "Без названия"
    tag = release.get("tag_name") or "без тега"
    url = release.get("html_url") or "https://github.com/" + repo + "/releases"
    if not url.startswith("https://github.com/" + repo + "/"):
        url = "https://github.com/" + repo + "/releases"
    heading = "<b>DarkWinter — предварительный релиз</b>" if release.get("prerelease") else "<b>DarkWinter — новый релиз</b>"
    lines = [heading, "<b>Версия:</b> " + safe(name, 180),
             "<b>Тег:</b> <code>" + safe(tag, 180) + "</code>"]
    if release.get("body"):
        lines += ["", "<b>Описание:</b>", safe(release["body"], 2400)]
    assets = []
    for asset in (release.get("assets") or [])[:10]:
        asset_url = asset.get("browser_download_url") or ""
        if asset_url.startswith("https://github.com/" + repo + "/"):
            assets.append("• " + link(asset_url, asset.get("name") or "Скачать файл"))
    if assets:
        lines += ["", "<b>Файлы:</b>", *assets]
    lines += [link(url, "Открыть релиз на GitHub")]
    result = "\n".join(lines)
    if len(result) > 4096:
        result = "\n".join(lines[:3] + ["", "Полный список изменений и файлов — на GitHub.",
                                           + link(url, "Открыть релиз")])
    return result


def send(message):
    token = os.environ.get("TELEGRAM_BOT_TOKEN", "")
    if not token:
        raise RuntimeError("TELEGRAM_BOT_TOKEN secret is missing")
    payload = json.dumps({
        "chat_id": os.environ.get("TELEGRAM_CHAT_ID", "@winterkore"),
        "text": message, "parse_mode": "HTML", "disable_web_page_preview": True,
    }, ensure_ascii=False).encode("utf-8")
    url = "https://api.telegram.org/bot" + token + "/sendMessage"
    for attempt in range(5):
        req = request.Request(url, data=payload,
                              headers={"Content-Type": "application/json"}, method="POST")
        try:
            with request.urlopen(req, timeout=30) as response:
                answer = json.load(response)
            if not answer.get("ok"):
                raise RuntimeError("Telegram did not confirm delivery")
            return
        except error.HTTPError as exc:
            if exc.code == 429 and attempt < 4:
                try:
                    response = json.loads(exc.read().decode("utf-8"))
                    delay = int(response.get("parameters", {}).get("retry_after", 2))
                except (ValueError, KeyError, TypeError):
                    delay = 2
                time.sleep(max(1, min(delay, 60)))
                continue
            raise RuntimeError("Telegram API returned HTTP " + str(exc.code)) from None
    raise RuntimeError("Telegram delivery failed after retries")


def main():
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    if not re.fullmatch(r"[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+", repo):
        raise RuntimeError("Invalid GITHUB_REPOSITORY")
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as event_file:
        event = json.load(event_file)
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    if event_name == "push":
        ref = str(event.get("ref") or "")
        if not ref.startswith("refs/heads/"):
            print("Not a branch push, skipping")
            return
        branch = ref.removeprefix("refs/heads/")
        commits = get_push_commits(event, repo)
        print("Commits to publish:", len(commits))
        for i, commit in enumerate(commits):
            send(format_commit(commit, branch, repo))
            print("Published", commit.get("sha") or commit.get("id"))
            if i < len(commits) - 1:
                time.sleep(1.1)
    elif event_name == "release" and event.get("action") == "published":
        send(format_release(event.get("release") or {}, repo))
        print("Published release notification")
    elif event_name == "workflow_dispatch":
        send("<b>DarkWinter GitBot</b>\nGitHub Actions успешно отправил тест в @winterkore.")
        print("Published manual test")
    else:
        print("Event not handled, skipping")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError) as exc:
        print("Notification failed:", exc, file=sys.stderr)
        sys.exit(1)
