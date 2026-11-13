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
    return "\n".join(lines + ["", link(url, "Посмотреть коммит")])


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
                                          "", link(url, "Открыть релиз")])
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



STATE_FILE = ".telegram-notifier-state.json"
MAX_SEEN = 20000


def gh_get(repo, endpoint):
    token = os.environ.get("GITHUB_TOKEN", "")
    if not token:
        raise RuntimeError("GITHUB_TOKEN is missing")
    url = "https://api.github.com/repos/" + repo + ("/" + endpoint if endpoint else "")
    req = request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "Authorization": "Bearer " + token,
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "DarkWinter-Telegram-Notifier",
    })
    try:
        with request.urlopen(req, timeout=30) as response:
            return json.load(response)
    except error.HTTPError as exc:
        raise RuntimeError("GitHub API returned HTTP " + str(exc.code)) from None


def branch_heads(repo):
    result = {}
    for page in range(1, 101):
        batch = gh_get(repo, "branches?per_page=100&page=" + str(page))
        for item in batch:
            name = item.get("name")
            sha = (item.get("commit") or {}).get("sha")
            if not isinstance(name, str) or not re.fullmatch(r"[0-9a-fA-F]{40,64}", str(sha)):
                raise RuntimeError("Invalid branch data from GitHub")
            result[name] = sha
        if len(batch) < 100:
            return result
    raise RuntimeError("Too many branches: refusing to silently skip some")


def load_state():
    if not os.path.exists(STATE_FILE):
        return None
    with open(STATE_FILE, encoding="utf-8") as state_file:
        data = json.load(state_file)
    if data.get("version") != 1 or not isinstance(data.get("branches"), dict) or not isinstance(data.get("seen"), list):
        raise RuntimeError("Invalid persisted branch state")
    return data


def save_state(state):
    tmp_path = STATE_FILE + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as state_file:
        json.dump(state, state_file, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp_path, STATE_FILE)


def is_pr_merge_commit(repo, commit, branch):
    """Avoid double-notifying a PR's final merge/squash/rebase commit."""
    sha = commit.get("sha") or commit.get("id")
    if not sha:
        return False
    pulls = gh_get(repo, "commits/" + sha + "/pulls?per_page=100")
    return any(
        pull.get("merged_at")
        and pull.get("merge_commit_sha") == sha
        and (pull.get("base") or {}).get("ref") == branch
        for pull in pulls
    )


def format_direct_merge(commit, branch, repo):
    sha = commit.get("sha") or commit.get("id")
    message = str(commit.get("message") or (commit.get("commit") or {}).get("message") or "")
    title = message.splitlines()[0] if message else "Слияние веток"
    author = commit.get("author") or (commit.get("commit") or {}).get("author") or {}
    # Best-effort from Git's standard merge titles; no claim if unknown.
    match = re.search(r"Merge branch ['\"]([^'\"]+)['\"]", title)
    if not match:
        match = re.search(r"Merge pull request #\d+ from ([^\s]+)", title)
    source = match.group(1) if match else None
    lines = ["<b>DarkWinter — слияние веток</b>"]
    if source:
        lines += ["<b>Исходная ветка:</b> <code>" + safe(source, 230) + "</code>"]
    lines += [
        "<b>Целевая ветка:</b> <code>" + safe(branch, 230) + "</code>",
        "<b>Автор:</b> " + safe(author.get("name") or author.get("login") or "Неизвестен", 120),
        "<b>SHA:</b> <code>" + str(sha)[:12] + "</code>",
        "",
        safe(title, 400),
        "",
        link("https://github.com/" + repo + "/commit/" + sha, "Посмотреть слияние"),
    ]
    return "\n".join(lines)


def format_fast_forward(source, target, sha, repo):
    """A matching tip indicates a fast-forward-like synchronization, not necessarily a PR."""
    return "\n".join([
        "<b>DarkWinter — синхронизация веток</b>",
        "<b>Обновлена ветка:</b> <code>" + safe(target, 230) + "</code>",
        "<b>Совпадает с веткой:</b> <code>" + safe(source, 230) + "</code>",
        "<b>Общий HEAD:</b> <code>" + sha[:12] + "</code>",
        "",
        link("https://github.com/" + repo + "/commit/" + sha, "Посмотреть коммит"),
    ])


def format_pr_merge(pr, repo):
    head = pr.get("head") or {}
    base = pr.get("base") or {}
    source_repo = (head.get("repo") or {}).get("full_name")
    source = head.get("ref") or "неизвестна"
    if source_repo and source_repo != repo:
        source = source_repo + ":" + source
    url = pr.get("html_url") or "https://github.com/" + repo + "/pulls"
    lines = [
        "<b>DarkWinter — слияние Pull Request #" + str(pr.get("number") or "?") + "</b>",
        "<b>Из ветки:</b> <code>" + safe(source, 230) + "</code>",
        "<b>В ветку:</b> <code>" + safe(base.get("ref") or "неизвестна", 230) + "</code>",
        "<b>Автор:</b> " + safe((pr.get("user") or {}).get("login") or "Неизвестен", 120),
    ]
    if (pr.get("merged_by") or {}).get("login"):
        lines.append("<b>Объединил:</b> " + safe(pr["merged_by"]["login"], 120))
    lines += ["", "<b>" + safe(pr.get("title") or "Без названия", 320) + "</b>"]
    if pr.get("body"):
        lines += ["", safe(pr["body"], 1700)]
    lines += ["", link(url, "Открыть Pull Request")]
    return "\n".join(lines)


def format_issue(issue, action, repo):
    number = issue.get("number") or "?"
    names = {"opened": "новая Issue", "edited": "Issue изменена",
             "closed": "Issue закрыта", "reopened": "Issue снова открыта"}
    title = names.get(action, "обновлена Issue")
    url = issue.get("html_url") or "https://github.com/" + repo + "/issues"
    lines = [
        "<b>DarkWinter — " + title + " #" + str(number) + "</b>",
        "<b>Автор:</b> " + safe((issue.get("user") or {}).get("login") or "Неизвестен", 120),
        "",
        "<b>" + safe(issue.get("title") or "Без названия", 320) + "</b>",
    ]
    if issue.get("body") and action in ("opened", "edited"):
        lines += ["", safe(issue["body"], 2400)]
    lines += ["", link(url, "Открыть Issue")]
    return "\n".join(lines)


def format_issue_comment(issue, comment, action, repo):
    number = issue.get("number") or "?"
    url = comment.get("html_url") or issue.get("html_url") or "https://github.com/" + repo + "/issues"
    header = "изменён ответ" if action == "edited" else "новый ответ"
    lines = [
        "<b>DarkWinter — " + header + " в Issue #" + str(number) + "</b>",
        "<b>Тема:</b> " + safe(issue.get("title") or "Без названия", 320),
        "<b>Автор ответа:</b> " + safe((comment.get("user") or {}).get("login") or "Неизвестен", 120),
        "",
        safe(comment.get("body") or "Без текста", 2500),
        "",
        link(url, "Открыть ответ на GitHub"),
    ]
    return "\n".join(lines)


def scan_branches(repo):
    """Runs on the default branch. Polling covers branches without the workflow file."""
    default_branch = gh_get(repo, "").get("default_branch") or "main"
    heads = branch_heads(repo)
    state = load_state()
    if state is None:
        save_state({"version": 1, "branches": heads, "seen": []})
        print("Initial branch baseline saved; previous commits were not republished")
        return
    previous = state["branches"]
    seen_list = state["seen"]
    seen = set(seen_list)
    # Process feature branches before main so existing SHAs keep their original branch label.
    order = sorted(name for name in heads if name != default_branch)
    if default_branch in heads:
        order.append(default_branch)
    published = 0
    try:
        for branch in order:
            head = heads[branch]
            before = previous.get(branch)
            if before == head:
                continue
            if before is None:
                if branch == default_branch:
                    previous[branch] = head
                    continue
                base = default_branch
            else:
                base = before
            try:
                commits = compare_commits(repo, base, head)
            except RuntimeError:
                if before is None or branch == default_branch:
                    raise
                # A force push/rebase can invalidate a previously stored branch tip.
                print("Nonlinear history for branch", branch, "- comparing against default branch")
                commits = compare_commits(repo, default_branch, head)
            sent_from_branch = 0
            for commit in commits:
                sha = commit.get("sha") or commit.get("id")
                if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-fA-F]{40,64}", sha):
                    raise RuntimeError("Invalid commit SHA in comparison")
                if sha in seen:
                    continue
                # The merge PR event provides richer branch details; do not post the
                # same final merge/squash/rebase SHA twice.
                if is_pr_merge_commit(repo, commit, branch):
                    seen.add(sha)
                    seen_list.append(sha)
                    continue
                parents = commit.get("parents") or []
                message = (format_direct_merge(commit, branch, repo) if len(parents) > 1
                           else format_commit(commit, branch, repo))
                send(message)
                seen.add(sha)
                seen_list.append(sha)
                published += 1
                sent_from_branch += 1
                time.sleep(1.1)
            # Direct fast-forward merges have no merge commit and no PR event.
            # Describe only what is verifiable: two existing branches have the same HEAD.
            if before is not None and not sent_from_branch:
                sources = [name for name, value in heads.items()
                           if name != branch and value == head and name in previous]
                if sources and not is_pr_merge_commit(repo, {"sha": head}, branch):
                    send(format_fast_forward(sorted(sources)[0], branch, head, repo))
                    published += 1
            previous[branch] = head
        state["branches"] = {name: previous.get(name, sha) for name, sha in heads.items()}
        state["seen"] = seen_list[-MAX_SEEN:]
        print("New commits published:", published, "; active branches:", len(heads))
    finally:
        # Save successful sends even if a later notification fails. The branch
        # cursor only advances after an entire branch was processed successfully.
        state["seen"] = seen_list[-MAX_SEEN:]
        save_state(state)


def main():
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    if not re.fullmatch(r"[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+", repo):
        raise RuntimeError("Invalid GITHUB_REPOSITORY")
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    path = os.environ["GITHUB_EVENT_PATH"]
    with open(path, encoding="utf-8") as event_file:
        event = json.load(event_file)

    if event_name in ("schedule", "push"):
        scan_branches(repo)
    elif event_name == "workflow_dispatch":
        if (event.get("inputs") or {}).get("mode") == "scan":
            scan_branches(repo)
        else:
            send("<b>DarkWinter GitBot</b>\nТестовое сообщение из GitHub Actions.")
            print("Published manual test notification")
    elif event_name == "pull_request_target" and event.get("action") == "closed":
        pr = event.get("pull_request") or {}
        if pr.get("merged"):
            send(format_pr_merge(pr, repo))
            print("Published merged pull request", pr.get("number"))
    elif event_name == "issues" and event.get("action") in ("opened", "edited", "closed", "reopened"):
        issue = event.get("issue") or {}
        if not issue.get("pull_request"):
            send(format_issue(issue, event["action"], repo))
            print("Published issue", issue.get("number"), event["action"])
    elif event_name == "issue_comment" and event.get("action") in ("created", "edited"):
        issue = event.get("issue") or {}
        if not issue.get("pull_request"):
            send(format_issue_comment(issue, event.get("comment") or {}, event["action"], repo))
            print("Published issue comment", (event.get("comment") or {}).get("id"))
        else:
            print("PR conversation comment (not an Issue): ignored")
    elif event_name == "release" and event.get("action") == "published":
        send(format_release(event.get("release") or {}, repo))
        print("Published release notification")
    else:
        print("Event not handled:", event_name)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError) as exc:
        print("Notification failed:", exc, file=sys.stderr)
        sys.exit(1)
