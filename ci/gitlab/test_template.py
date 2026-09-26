#!/usr/bin/env python3
"""Tests docswatcher.gitlab-ci.yml without GitLab.

Checks the file's structure, then runs the job's script exactly as written, the way a runner does
(one shell, all script items in order, `set -e`), against a fake release directory served over
file:// and a fake CLI. Each case runs under dash and under bash with pipefail, since the runner
picks whichever shell the image has.

    python3 ci/gitlab/test_template.py        # needs PyYAML (pip install pyyaml)
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

import yaml

TEMPLATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "docswatcher.gitlab-ci.yml")
ASSET = "docswatcher-linux-x64"
TAG = "v9.9.9"

# Job keywords GitLab accepts; anything else in the template is a typo that CI lint would reject.
JOB_KEYWORDS = {
    "after_script", "allow_failure", "artifacts", "before_script", "cache", "coverage", "dependencies",
    "environment", "extends", "id_tokens", "image", "inherit", "interruptible", "needs", "parallel",
    "release", "resource_group", "retry", "rules", "script", "services", "stage", "tags", "timeout",
    "trigger", "variables", "when",
}

# The CLI, faked: records its arguments, and writes the report FAKE_MODE asks for.
FAKE_CLI = r"""#!/bin/sh
echo "$*" >> "$FAKE_LOG"
if [ "$2" = "--help" ]; then
  echo "Missing required parameter: '<path>'"
  if [ "$FAKE_MODE" = "old" ]; then echo "Usage: docswatcher match [--format=json|text] <path>"; else echo "Usage: docswatcher match [--report=<file>] <path>"; fi
  exit 2
fi
report=""; prev=""
for a in "$@"; do [ "$prev" = "--report" ] && report="$a"; prev="$a"; done
echo "fake text summary"
case "$FAKE_MODE" in
  breaking) cp "$FAKE_REPORT" "$report"; exit 1 ;;
  clean) echo "[]" > "$report"; exit 0 ;;
  garbage) echo "not json" > "$report"; exit 0 ;;
  nosev) echo "[{}]" > "$report"; exit 0 ;;
  crash) exit 2 ;;
  noreport) exit 0 ;;
esac
exit 0
"""

FINDINGS = [
    {
        "id": "local:openai-assistants:openai:sdk_method:beta.assistants.create",
        "contract": "openai:sdk_method:beta.assistants.create", "change": "openai-assistants-api-v1-sunset",
        "severity": "breaking", "effective": "2026-08-26", "daysRemaining": -29,
        "evidence": [
            {"path": "app.py", "line": 12, "column": 5, "snippet": "client.beta.assistants.create(", "detector": "callsite", "layer": "callsite"},
            {"path": "jobs/worker.py", "line": 40, "column": 1, "snippet": "client.beta.assistants.create(", "detector": "callsite", "layer": "callsite"},
        ],
        "status": "open", "snoozedUntil": None, "fixPr": None,
    },
    {
        "id": "local:gpt35:openai:model:gpt-3.5-turbo-0125",
        "contract": "openai:model:gpt-3.5-turbo-0125", "change": "openai-gpt-3-5-turbo-0125-shutdown",
        "severity": "warning", "effective": None, "daysRemaining": None,
        "evidence": [{"path": "app.py", "line": 3, "column": 9, "snippet": 'MODEL = "gpt-3.5-turbo-0125"', "detector": "literal", "layer": "literal"}],
        "status": "open", "snoozedUntil": None, "fixPr": None,
    },
]


def load_template():
    with open(TEMPLATE) as f:
        return yaml.safe_load(f)


def job_script(doc):
    return "\n".join(doc["docswatcher"]["script"]) + "\n"


def shells():
    found = []
    if shutil.which("dash"):
        found.append(("dash", ["dash", "-c"], "set -e\n"))
    if shutil.which("bash"):
        found.append(("bash", ["bash", "-c"], "set -eo pipefail\n"))
    return found


class Structure(unittest.TestCase):
    def test_one_job_and_its_settings(self):
        doc = load_template()
        self.assertEqual(set(doc), {"variables", "docswatcher"})
        job = doc["docswatcher"]
        self.assertLessEqual(set(job), JOB_KEYWORDS)
        self.assertEqual(job["stage"], "test")
        self.assertEqual(job["image"], "$DOCSWATCHER_IMAGE")
        self.assertTrue(all(isinstance(s, str) for s in job["script"]))
        self.assertEqual(job["artifacts"]["reports"]["codequality"], "gl-code-quality-report.json")
        self.assertIn("docswatcher.json", job["artifacts"]["paths"])
        self.assertEqual(job["artifacts"]["when"], "always")
        self.assertEqual(job["rules"][0], {"if": "$DOCSWATCHER_DISABLED", "when": "never"})

    def test_every_setting_has_a_string_default(self):
        doc = load_template()
        variables = doc["variables"]
        for name in ("DOCSWATCHER_PATH", "DOCSWATCHER_FAIL_ON", "DOCSWATCHER_EXCLUDE", "DOCSWATCHER_VERSION",
                     "DOCSWATCHER_INCLUDE_LOW", "DOCSWATCHER_RELEASES", "DOCSWATCHER_IMAGE"):
            self.assertIsInstance(variables.get(name), str, name)
        used = set(re.findall(r"\$\{?(DOCSWATCHER_[A-Z_]+)", job_script(doc)))
        self.assertLessEqual(used, set(variables), "the script reads a variable with no default")

    def test_the_documented_include_url_names_this_file(self):
        with open(TEMPLATE) as f:
            head = f.read(2000)
        self.assertRegex(head, r"https://raw\.githubusercontent\.com/jameskomo/docswatcher/v\d+\.\d+\.\d+/ci/gitlab/docswatcher\.gitlab-ci\.yml")


class Behaviour(unittest.TestCase):
    """Each case builds a release and a project, runs the job, and returns what happened."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="dw-gitlab-")
        self.addCleanup(shutil.rmtree, self.tmp)
        self.releases = os.path.join(self.tmp, "releases")
        self.release = os.path.join(self.releases, "download", TAG)
        os.makedirs(self.release)
        self.cli = FAKE_CLI.encode()
        self.project = os.path.join(self.tmp, "project")
        os.makedirs(os.path.join(self.project, "services", "api"))
        self.report = os.path.join(self.tmp, "findings.json")
        with open(self.report, "w") as f:
            json.dump(FINDINGS, f)
        self.script = job_script(load_template())

    def publish(self, checksums=None, binary=None):
        with open(os.path.join(self.release, ASSET), "wb") as f:
            f.write(binary if binary is not None else self.cli)
        if checksums is None:
            digest = hashlib.sha256(self.cli).hexdigest()
            checksums = f"{'0' * 64}  docswatcher-macos-arm64\n{digest}  {ASSET}\n{'1' * 64}  docswatcher.jar\n"
        with open(os.path.join(self.release, "checksums.txt"), "w") as f:
            f.write(checksums)

    def run_job(self, shell, mode="breaking", path_prepend=None, **variables):
        doc = load_template()
        env = {k: v for k, v in os.environ.items() if not k.startswith("DOCSWATCHER_")}
        env.update(doc["variables"])
        env.update({
            "DOCSWATCHER_RELEASES": "file://" + self.releases, "DOCSWATCHER_VERSION": TAG,
            "CI_PROJECT_DIR": self.project, "FAKE_MODE": mode, "FAKE_REPORT": self.report,
            "FAKE_LOG": os.path.join(self.tmp, "cli.log"),
        })
        env.update(variables)
        if path_prepend:
            env["PATH"] = path_prepend + os.pathsep + env["PATH"]
        name, argv, prelude = shell
        return subprocess.run(argv + [prelude + self.script], cwd=self.project, env=env,
                              capture_output=True, text=True, timeout=120)

    def cli_ran(self):
        return os.path.exists(os.path.join(self.tmp, "cli.log"))

    def cli_args(self):
        with open(os.path.join(self.tmp, "cli.log")) as f:
            return f.read().splitlines()

    def codequality(self):
        with open(os.path.join(self.project, "gl-code-quality-report.json")) as f:
            return json.load(f)

    def each_shell(self, case):
        for shell in shells():
            with self.subTest(shell=shell[0]):
                for leftover in ("cli.log",):
                    p = os.path.join(self.tmp, leftover)
                    if os.path.exists(p):
                        os.remove(p)
                case(shell)

    # --- the release check

    def test_a_good_release_runs_scans_and_fails_on_a_breaking_finding(self):
        self.publish()

        def case(shell):
            r = self.run_job(shell)
            self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
            self.assertIn("matches checksums.txt", r.stdout)
            self.assertIn("fake text summary", r.stdout)
            self.assertIn("2 findings, 1 breaking", r.stdout)
            self.assertIn("1 breaking API deprecations affect this repository", r.stdout)
            self.assertEqual(self.cli_args()[-1], "match . --format text --report docswatcher.json")
            self.assertTrue(os.path.exists(os.path.join(self.project, "docswatcher.json")))
        self.each_shell(case)

    def test_a_tampered_binary_is_deleted_and_never_run(self):
        self.publish(binary=self.cli + b"\n# tampered\n")

        def case(shell):
            r = self.run_job(shell)
            self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
            self.assertIn("does not match checksums.txt", r.stdout)
            self.assertFalse(self.cli_ran())
            self.assertFalse(os.path.exists(os.path.join(self.project, "gl-code-quality-report.json")))
        self.each_shell(case)

    def test_a_release_whose_checksums_do_not_list_the_binary_is_refused(self):
        self.publish(checksums=f"{'0' * 64}  docswatcher-macos-arm64\n")

        def case(shell):
            r = self.run_job(shell)
            self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
            self.assertIn(f"has no single sha256 for {ASSET}", r.stdout)
            self.assertFalse(self.cli_ran())
        self.each_shell(case)

    def test_two_entries_for_the_binary_are_refused(self):
        digest = hashlib.sha256(self.cli).hexdigest()
        self.publish(checksums=f"{digest}  {ASSET}\n{'2' * 64}  *{ASSET}\n")

        def case(shell):
            r = self.run_job(shell)
            self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
            self.assertIn("has no single sha256", r.stdout)
            self.assertFalse(self.cli_ran())
        self.each_shell(case)

    def test_a_malformed_digest_is_refused(self):
        self.publish(checksums=f"{'z' * 64}  {ASSET}\n")

        def case(shell):
            r = self.run_job(shell)
            self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
            self.assertIn("has no single sha256", r.stdout)
            self.assertFalse(self.cli_ran())
        self.each_shell(case)

    def test_a_version_that_does_not_resolve_to_a_tag_is_refused(self):
        self.publish()
        with open(os.path.join(self.releases, "latest"), "w") as f:
            f.write("a page, not a redirect")

        def case(shell):
            # file:// does not redirect, so "latest" stays "latest": exactly what must not be run.
            r = self.run_job(shell, DOCSWATCHER_VERSION="latest")
            self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
            self.assertIn("could not resolve DocsWatcher release 'latest'", r.stdout)
            r = self.run_job(shell, DOCSWATCHER_VERSION="v1; rm -rf /")
            self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
            self.assertIn("could not resolve", r.stdout)
            self.assertFalse(self.cli_ran())
        self.each_shell(case)

    def test_a_runner_without_a_native_build_is_told_so(self):
        self.publish()
        shim = os.path.join(self.tmp, "shim")
        os.makedirs(shim)
        with open(os.path.join(shim, "uname"), "w") as f:
            f.write('#!/bin/sh\n[ "$1" = "-m" ] && echo aarch64 || echo Linux\n')
        os.chmod(os.path.join(shim, "uname"), 0o755)

        def case(shell):
            r = self.run_job(shell, path_prepend=shim)
            self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
            self.assertIn("no native build for Linux aarch64", r.stdout)
            self.assertFalse(self.cli_ran())
        self.each_shell(case)

    # --- the scan and its report

    def test_the_code_quality_report_maps_every_location(self):
        self.publish()

        def case(shell):
            r = self.run_job(shell, mode="breaking", DOCSWATCHER_PATH="services/api", DOCSWATCHER_FAIL_ON="never")
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            issues = self.codequality()
            self.assertEqual([(i["location"]["path"], i["location"]["lines"]["begin"], i["severity"]) for i in issues], [
                ("services/api/app.py", 12, "critical"),
                ("services/api/jobs/worker.py", 40, "critical"),
                ("services/api/app.py", 3, "major"),
            ])
            for i in issues:
                self.assertEqual(i["type"], "issue")
                self.assertTrue(i["check_name"].startswith("docswatcher/"))
                self.assertRegex(i["fingerprint"], r"^[0-9a-f]{32}$")
                self.assertEqual(i["categories"], ["Compatibility"])
            self.assertEqual(len({i["fingerprint"] for i in issues}), 3)
            self.assertIn("shut down 2026-08-26 (29 days ago)", issues[0]["description"])
            self.assertEqual(issues[2]["description"], "openai:model:gpt-3.5-turbo-0125 is affected by openai-gpt-3-5-turbo-0125-shutdown")
            self.assertEqual(self.cli_args()[-1], "match services/api --format text --report docswatcher.json")
        self.each_shell(case)

    def test_a_fingerprint_survives_the_line_moving(self):
        self.publish()
        fingerprints = []
        for line in (12, 30):
            moved = json.loads(json.dumps(FINDINGS))
            moved[0]["evidence"][0]["line"] = line
            with open(self.report, "w") as f:
                json.dump(moved, f)
            r = self.run_job(shells()[0], DOCSWATCHER_FAIL_ON="never")
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            fingerprints.append(self.codequality()[0]["fingerprint"])
        self.assertEqual(fingerprints[0], fingerprints[1])

    def test_exclude_include_low_and_a_clean_repository(self):
        self.publish()

        def case(shell):
            r = self.run_job(shell, mode="clean", DOCSWATCHER_INCLUDE_LOW="true",
                             DOCSWATCHER_EXCLUDE="  samples/\n\n test data/ \n*.snap\n")
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn("nothing your code calls has a known shutdown date", r.stdout)
            self.assertEqual(self.cli_args()[-1],
                             "match . --format text --report docswatcher.json --include-low --exclude samples/ --exclude test data/ --exclude *.snap")
            self.assertEqual(self.codequality(), [])
        self.each_shell(case)

    def test_a_report_that_is_not_a_report_fails_rather_than_passing(self):
        self.publish()

        def case(shell):
            for mode in ("garbage", "noreport"):
                r = self.run_job(shell, mode=mode)
                self.assertEqual(r.returncode, 3, mode + r.stdout + r.stderr)
                self.assertIn("did not produce a report", r.stderr)
            r = self.run_job(shell, mode="nosev")
            self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
            self.assertIn("carry no severity", r.stderr)
        self.each_shell(case)

    def test_the_scan_failing_is_not_a_finding(self):
        self.publish()

        def case(shell):
            r = self.run_job(shell, mode="crash", DOCSWATCHER_FAIL_ON="never")
            self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
            self.assertIn("the scan itself failed", r.stdout)
        self.each_shell(case)

    def test_a_release_without_report_support_or_a_bad_setting_is_refused(self):
        self.publish()

        def case(shell):
            r = self.run_job(shell, mode="old")
            self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
            self.assertIn("cannot write a report", r.stdout)
            r = self.run_job(shell, DOCSWATCHER_FAIL_ON="warning")
            self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
            self.assertIn("use breaking or never", r.stdout)
        self.each_shell(case)


if __name__ == "__main__":
    if not shells():
        sys.exit("needs dash or bash")
    unittest.main(verbosity=2)
