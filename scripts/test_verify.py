import copy
from pathlib import Path
import tempfile
import unittest

from verify import (
    collect_results,
    validate_manifest,
    verify_requirements,
    check_boundaries,
)


class RequirementGateTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {
            "requirements": [
                {"id": "M0-01", "status": "automated", "tests": ["Example#works"]}
            ]
        }

    def test_successful_evidence_passes(self):
        self.assertEqual(
            "PASS",
            verify_requirements(self.manifest, {"Example#works": ["passed"]})[0][
                "result"
            ],
        )

    def test_missing_failed_or_skipped_evidence_fails(self):
        for results in (
            {},
            {"Example#works": ["failed"]},
            {"Example#works": ["skipped"]},
            {"Example#works": ["passed", "skipped"]},
        ):
            with self.subTest(results=results), self.assertRaises(ValueError):
                verify_requirements(self.manifest, results)

    def test_unmapped_failure_and_accidental_live_call_fail(self):
        for key in ("Unmapped#broken", "dev.arl.LiveProviderTest#deepseekToolCalling"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_requirements(
                    self.manifest,
                    {
                        "Example#works": ["passed"],
                        key: ["failed" if key.startswith("Unmapped") else "passed"],
                    },
                )

    def test_dropping_parameterized_provider_case_fails(self):
        self.manifest["minimumInvocations"] = {"Example#works": 2}
        with self.assertRaises(ValueError):
            verify_requirements(self.manifest, {"Example#works": ["passed"]})

    def test_duplicate_ids_and_empty_mapping_fail(self):
        duplicate = copy.deepcopy(self.manifest)
        duplicate["requirements"] *= 2
        with self.assertRaises(ValueError):
            validate_manifest(duplicate)
        self.manifest["requirements"][0]["tests"] = []
        with self.assertRaises(ValueError):
            validate_manifest(self.manifest)

    def test_manual_checks_are_not_reported_as_passed(self):
        manual = {
            "requirements": [
                {"id": "REVIEW", "status": "manual", "reason": "Reviewer required"}
            ]
        }
        self.assertEqual("MANUAL", verify_requirements(manual, {})[0]["result"])

    def test_missing_stale_and_parameterized_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                collect_results(root, 0)
            for module in ("arl-core", "arl-server", "arl-evaluation"):
                folder = root / module / "target/surefire-reports"
                folder.mkdir(parents=True)
                (folder / "TEST-Example.xml").write_text(
                    '<testsuite><testcase classname="Example" name="works(String)[1]"/></testsuite>'
                )
            self.assertEqual(
                ["passed", "passed", "passed"],
                collect_results(root, 0)["Example#works"],
            )
            with self.assertRaises(ValueError):
                collect_results(root, float("inf"))

    def test_core_dependency_boundary_rejects_boot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "arl-core").mkdir()
            (root / "arl-core/pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies><dependency><groupId>org.springframework.boot</groupId></dependency></dependencies></project>'
            )
            with self.assertRaises(ValueError):
                check_boundaries(root)


if __name__ == "__main__":
    unittest.main()
