#!/usr/bin/env python3
"""Run the offline quality gate and derive requirement evidence from fresh test XML."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def fingerprint(root):
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if not path.is_file() or any(part in {".git", "target", "__pycache__"} for part in path.relative_to(root).parts):
            continue
        if path.name.startswith(".env") and path.name != ".env.example":
            continue
        digest.update(str(path.relative_to(root)).encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def validate_manifest(manifest):
    requirements = manifest["requirements"]
    ids = [item["id"] for item in requirements]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate requirement IDs")
    for item in requirements:
        if item["status"] not in {"automated", "manual", "deferred", "not_run"}:
            raise ValueError(f"Unknown status: {item['id']}")
        if item["status"] == "automated" and not item.get("tests"):
            raise ValueError(f"No test mapping: {item['id']}")
        if item["status"] != "automated" and not item.get("reason"):
            raise ValueError(f"No limitation/reason: {item['id']}")
    return requirements


def collect_results(root, started):
    results = {}
    for module in ("arl-core", "arl-server"):
        reports = list((root / module / "target/surefire-reports").glob("TEST-*.xml"))
        if not reports:
            raise ValueError(f"Missing test reports: {module}")
        for report in reports:
            if report.stat().st_mtime < started:
                raise ValueError(f"Stale test report: {report.name}")
            for case in ET.parse(report).getroot().iter("testcase"):
                # Surefire appends argument types and invocation indexes to parameterized names.
                method = re.split(r"[\[(]", case.attrib["name"], maxsplit=1)[0]
                key = f"{case.attrib['classname']}#{method}"
                status = "failed" if case.find("failure") is not None or case.find("error") is not None else "skipped" if case.find("skipped") is not None else "passed"
                results.setdefault(key, []).append(status)
    return results


def verify_requirements(manifest, results):
    requirements = validate_manifest(manifest)
    for key, outcomes in results.items():
        is_live = key.startswith("dev.arl.LiveProviderTest#")
        if any(status != ("skipped" if is_live else "passed") for status in outcomes):
            raise ValueError(f"Unexpected offline outcome: {key}: {outcomes}")
    evidence = []
    for item in requirements:
        if item["status"] == "automated":
            for key in item["tests"]:
                if len(results.get(key, [])) < manifest.get("minimumInvocations", {}).get(key, 1) or any(status != "passed" for status in results[key]):
                    raise ValueError(f"Missing or unsuccessful evidence: {item['id']} -> {key}")
        evidence.append({**item, "result": "PASS" if item["status"] == "automated" else item["status"].upper()})
    return evidence


def check_boundaries(root):
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    core = ET.parse(root / "arl-core/pom.xml")
    for dep in core.findall("./m:dependencies/m:dependency", ns):
        group = dep.findtext("m:groupId", namespaces=ns)
        scope = dep.findtext("m:scope", namespaces=ns)
        if scope != "test" and (group == "org.springframework.boot" or group == "dev.arl"):
            raise ValueError("arl-core cannot directly depend on Boot composition or another ARL module")
    for source in (root / "arl-core/src/main/java").rglob("*.java"):
        if re.search(r"\b(?:org\.springframework\.boot|dev\.arl\.server)\.", source.read_text()):
            raise ValueError(f"Core composition-boundary violation: {source.name}")


def main():
    os.chdir(ROOT)
    destination = ROOT / "target/quality/requirements-report.json"
    destination.unlink(missing_ok=True)
    validate_manifest(json.loads((ROOT / "config/requirements.json").read_text()))
    check_boundaries(ROOT)
    subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"], check=True)
    before = fingerprint(ROOT)
    started = time.time()
    # This entry point is always offline with respect to LLM APIs, even on a developer machine.
    env = {key: value for key, value in os.environ.items() if key not in {"DEEPSEEK_API_KEY", "XAI_API_KEY"}}
    subprocess.run(["./mvnw", "--batch-mode", "--no-transfer-progress", "clean", "verify", "-Darl.live=false"], env=env, check=True)
    if fingerprint(ROOT) != before:
        raise ValueError("Source changed during verification; rerun the gate")
    results = collect_results(ROOT, started)
    evidence = verify_requirements(json.loads((ROOT / "config/requirements.json").read_text()), results)
    coverage = {}
    for module in ("arl-core", "arl-server"):
        path = ROOT / module / "target/site/jacoco/jacoco.xml"
        if not path.exists() or path.stat().st_mtime < started:
            raise ValueError(f"Missing/stale coverage report: {module}")
        counters = {counter.attrib["type"]: {k: int(counter.attrib[k]) for k in ("covered", "missed")}
                    for counter in ET.parse(path).getroot().findall("counter")}
        if counters.get("LINE", {}).get("covered", 0) == 0:
            raise ValueError(f"No line coverage recorded: {module}")
        coverage[module] = counters
    report = {
        "gate": "PASS", "sourceSha256": before, "verifiedAtEpoch": time.time(),
        "passed": sum(values.count("passed") for values in results.values()),
        "skipped": sum(values.count("skipped") for values in results.values()),
        "requirements": evidence, "coverage": coverage,
        "note": "Automated evidence only. Manual review and provider live verification are separate gates.",
    }
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Quality gate PASS: {report['passed']} passed, {report['skipped']} skipped. {destination}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        print(f"Quality gate FAILED: {error}", file=sys.stderr)
        sys.exit(1)
