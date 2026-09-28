"""Exercise the issue-closing workflow with a harmless GitHub CLI stub."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "close-fixed-issues.yml"


def workflow_script():
    lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
    start = lines.index("        run: |") + 1
    script = []
    for line in lines[start:]:
        if line and not line.startswith("          "):
            break
        script.append(line[10:])
    return "\n".join(script) + "\n"


@unittest.skipIf(os.name == "nt" or not shutil.which("bash"), "requires Unix bash")
class CloseFixedIssuesTests(unittest.TestCase):
    def run_workflow(self, body, commits=""):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            gh = directory / "gh"
            log = directory / "calls"
            gh.write_text(
                "#!/bin/sh\n"
                'if [ "$1 $2" = "pr view" ]; then printf "%s\\n" "$MOCK_COMMITS"; exit; fi\n'
                'if [ "$1 $2" = "issue view" ]; then printf "OPEN\\n"; exit; fi\n'
                'printf "%s %s %s\\n" "$1" "$2" "$3" >> "$MOCK_LOG"\n',
                encoding="utf-8",
            )
            gh.chmod(0o755)
            env = os.environ.copy()
            env.update(
                PATH=f"{directory}:{env['PATH']}",
                BODY=body,
                MOCK_COMMITS=commits,
                MOCK_LOG=str(log),
                PR="73",
                SHA="abc123",
            )
            result = subprocess.run(
                ["bash", "-c", workflow_script()],
                env=env,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            return log.read_text(encoding="utf-8").splitlines() if log.exists() else []

    def test_full_fix_without_partial_reference_closes_issue(self):
        self.assertEqual(self.run_workflow("Closes #12"), ["issue close 12"])

    def test_partial_reference_keeps_issue_open_in_bot(self):
        self.assertEqual(
            self.run_workflow("Closes #12\nRefs #12\nFixes #34"),
            ["issue close 34", "issue comment 12"],
        )

    def test_commit_keyword_is_processed(self):
        self.assertEqual(self.run_workflow("", "Resolves #56"), ["issue close 56"])


if __name__ == "__main__":
    unittest.main()
