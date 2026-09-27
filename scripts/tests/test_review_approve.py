"""Check which `/reviewed` comments may approve the current pull request head."""

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import review_approve as gate

HEAD = "0123456789abcdef0123456789abcdef01234567"


def comment(body="/reviewed", association="OWNER", created_at="2026-09-27T12:00:00Z"):
    return {"body": body, "author_association": association, "created_at": created_at,
            "user": {"login": "someone"}}


def pull(state="open", draft=False, sha=HEAD):
    return {"state": state, "draft": draft, "head": {"sha": sha}}


BEFORE = "2026-09-27T11:59:00Z"
AFTER = "2026-09-27T12:01:00Z"


class ParseCommandTests(unittest.TestCase):
    def test_accepts_bare_and_sha_forms(self):
        self.assertEqual(gate.parse_command("/reviewed"), (True, None))
        self.assertEqual(gate.parse_command("/reviewed 0123abc\nnotes"), (True, "0123abc"))

    def test_rejects_other_text(self):
        for body in ("", "/reviewedx", "LGTM /reviewed", "/reviewed abc", "/reviewed 0123abc extra", None):
            with self.subTest(body=body):
                self.assertFalse(gate.parse_command(body)[0])


class DecideTests(unittest.TestCase):
    def test_owner_bare_command_approves_head_committed_before_comment(self):
        decision = gate.decide(comment(), pull(), BEFORE)
        self.assertTrue(decision.approve)
        self.assertEqual(decision.sha, HEAD)

    def test_bare_command_rejects_head_committed_after_comment(self):
        self.assertFalse(gate.decide(comment(), pull(), AFTER).approve)

    def test_sha_must_match_current_head(self):
        self.assertTrue(gate.decide(comment("/reviewed 0123456"), pull(), AFTER).approve)
        self.assertFalse(gate.decide(comment("/reviewed fedcba9"), pull(), BEFORE).approve)

    def test_only_owner_can_approve(self):
        for association in ("COLLABORATOR", "MEMBER", "CONTRIBUTOR", "NONE"):
            with self.subTest(association=association):
                self.assertFalse(gate.decide(comment(association=association), pull(), BEFORE).approve)

    def test_closed_or_draft_pull_is_not_approved(self):
        self.assertFalse(gate.decide(comment(), pull(state="closed"), BEFORE).approve)
        self.assertFalse(gate.decide(comment(), pull(draft=True), BEFORE).approve)

    def test_non_command_is_ignored(self):
        self.assertFalse(gate.decide(comment("looks good"), pull(), BEFORE).approve)


if __name__ == "__main__":
    unittest.main()
