"""Turn the repository owner's `/reviewed` PR comment into a bot approval of the reviewed head commit."""

from dataclasses import dataclass
from datetime import datetime
import json
import os
import re
import sys
import urllib.request

COMMAND = re.compile(r"^/reviewed(?:[ \t]+(?P<sha>[0-9a-f]{7,40}))?[ \t]*$")
ALLOWED_ASSOCIATIONS = frozenset({"OWNER"})
API = "https://api.github.com"


@dataclass(frozen=True)
class Decision:
    approve: bool
    reason: str
    sha: str | None = None


def parse_command(body):
    """Return (matched, sha_prefix) for the first line of the comment."""
    first_line = (body or "").strip().splitlines()[0] if (body or "").strip() else ""
    match = COMMAND.match(first_line.strip())
    return (match is not None, match.group("sha") if match else None)


def parse_time(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def decide(comment, pull, head_commit_date):
    """Decide whether the comment approves the current head of the pull request."""
    matched, sha_prefix = parse_command(comment.get("body"))
    if not matched:
        return Decision(False, "comment is not a /reviewed command")
    if comment.get("author_association") not in ALLOWED_ASSOCIATIONS:
        return Decision(False, f"@{comment['user']['login']} is not the repository owner")
    if pull.get("state") != "open" or pull.get("draft"):
        return Decision(False, "pull request is closed or draft")
    head_sha = pull["head"]["sha"]
    if sha_prefix is not None:
        if not head_sha.startswith(sha_prefix):
            return Decision(False, f"reviewed {sha_prefix}, but head is {head_sha[:12]}; review again")
    elif parse_time(head_commit_date) > parse_time(comment["created_at"]):
        return Decision(False, f"head {head_sha[:12]} was committed after the comment; review again")
    return Decision(True, f"approved {head_sha[:12]}", head_sha)


def request(method, path, token, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(f"{API}{path}", data=data, method=method, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    with urllib.request.urlopen(req, timeout=30) as response:
        body = response.read()
    return json.loads(body) if body else None


def report(message):
    print(message)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as output:
            output.write(f"{message}\n")


def main():
    token = os.environ["GITHUB_TOKEN"]
    repo = os.environ["GITHUB_REPOSITORY"]
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as event_file:
        event = json.load(event_file)
    comment = event["comment"]
    number = event["issue"]["number"]
    pull = request("GET", f"/repos/{repo}/pulls/{number}", token)
    head = request("GET", f"/repos/{repo}/commits/{pull['head']['sha']}", token)
    decision = decide(comment, pull, head["commit"]["committer"]["date"])
    reaction = "+1" if decision.approve else "confused"
    if decision.approve:
        request("POST", f"/repos/{repo}/pulls/{number}/reviews", token, {
            "commit_id": decision.sha,
            "event": "APPROVE",
            "body": f"Reviewed by @{comment['user']['login']} ({comment['html_url']}).",
        })
    request("POST", f"/repos/{repo}/issues/comments/{comment['id']}/reactions", token, {"content": reaction})
    report(f"PR #{number}: {decision.reason}")
    return 0 if decision.approve else 1


if __name__ == "__main__":
    sys.exit(main())
