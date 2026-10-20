# Engineering quality harness

This harness checks the repository as software. It is separate from M2 runtime reliability
controls and the M3 agent evaluation system.

## One entry point

```sh
make verify          # canonical local/CI gate
make format          # apply Java formatting
make harness-test    # test the requirement gate itself
```

Requires OpenJDK 27, Python 3.9+ and Maven Wrapper. Initial dependency resolution needs
network access; provider APIs and keys are never needed. Set `MAVEN_ARGS=-o` for cached
offline builds. Transport regressions need loopback socket access.

`./mvnw clean verify` runs Java checks and tests. `make verify` additionally runs the
Python gate tests, verifies architecture boundaries, matches requirements to fresh
Surefire XML, and writes evidence bound to a source SHA-256. The canonical gate strips
provider credentials from the Maven environment and explicitly disables live tests.

## Enforced checks

| Gate | Behavior |
|---|---|
| Toolchain | Maven Enforcer: JDK 27, Maven 3.9.11–3.x |
| Formatting | Spotless 3.10.4 / google-java-format 1.36.1, production and test Java |
| Static source checks | Checkstyle 14.3.0 via plugin 3.6.0: unused imports, empty catches, equals/hashCode pairing, switch fall-through, broad catch/throws, modifier order, file/method size, cyclomatic complexity, coupling, fan-out, parameter count, nesting depth and return count |
| Duplication | PMD CPD 3.28.0 fails duplicated Java blocks at 55+ tokens across production and test sources |
| Regression | JUnit: real ReactAgent graph, HTTP stubs, limits, isolation, provider configuration, no fallback, timeout and failure behavior |
| Coverage | JaCoCo 0.8.15: each Java module must reach 71% classes, 71% methods, 85% lines and 75% branches; HTML/XML reports |
| Architecture | Core cannot directly depend on another ARL module or production Boot composition; source imports checked |
| Requirements | Every automated requirement must have mapped, passing test evidence; non-live skips and unexpected live execution fail |
| Frontend supply chain | `npm audit --audit-level=high` against the pinned lockfile; CI installs with `npm ci` |
| Evidence freshness | Clean build, report timestamps and unchanged source fingerprint; no reuse of historic evidence |

Class and method gates use an inclusive 71% minimum to enforce the requirement of
**greater than 70%**, rather than allowing exactly 70%. They apply separately to each
Java module, so one module cannot hide another's low coverage. JaCoCo class coverage
means a class has at least one executed method; method coverage means a method has at
least one executed instruction. These metrics do not imply every path is tested; line
and branch gates remain in place. Interfaces without executable code are not counted.

Coverage is a regression floor, not proof of correctness. Do not lower thresholds or
exclude hard-to-test logic to pass a build. Maintainability checks intentionally flag
large methods/classes, high branching, heavy coupling and copy/paste blocks; refactor
the code rather than suppressing or weakening these rules. Static checks do not replace a bug review.
Checkstyle currently parses language syntax through Java 25; the project uses ordinary
syntax compiled to Java 27. New Java 26/27 syntax requires a parser compatibility check.
JaCoCo 0.8.15 supports Java 27. google-java-format 1.37.0 failed through this Spotless
combination; 1.36.1 is pinned based on executable verification. No toolchain downgrade.

## Evidence

- `target/quality/requirements-report.json`: fresh requirement results and source hash.
- `arl-*/target/surefire-reports/`: individual Java results, including explicit live skips.
- `arl-*/target/site/jacoco/index.html` and `jacoco.xml`: module coverage.
- `arl-*/target/checkstyle-result.xml`: source-check results.
- `arl-*/target/cpd.xml`: duplicate-code report when CPD runs.

These outputs are ignored by Git and uploaded as CI artifacts. The original
`docs/m0-verification.json` is historical evidence only; it does not certify new code.
Manual requirements stay MANUAL, future work stays DEFERRED and unavailable live
provider evidence stays NOT_RUN. Automated success never marks those complete.

## Adding a requirement or regression

1. Identify the approved milestone and a stable requirement ID in `config/requirements.json`.
2. Add a behavioral test for the requirement, including a meaningful failure path.
3. Map it as `fully.qualified.TestClass#methodName`; parameterized invocations share that name.
4. Keep manual limitations explicit. Do not map a stub test as evidence of live model quality.
5. Run `make verify`, review the evidence and complete the code-review checklist.

The manifest traces M0 and M1 acceptance and records future milestone groups; it is not a
claim that all future requirements already have tests. Expand deferred groups into
specific acceptance tests when their milestone is approved.

## CI and review enforcement

`.github/workflows/quality.yml` runs the same gate on pushes and pull requests using
Java 27. Action revisions are immutable, token permissions are read-only, no provider
secrets are supplied and test/coverage evidence is retained even on failure.

The workflow is configured locally; it cannot report a GitHub run until pushed.
Repository administrators should require the `quality` check and one approving review,
dismiss approvals after changes, and block merging unresolved review conversations.
Branch protection is a GitHub setting and has not been changed by this work. Do not
claim the PR template itself prevents merging. Assign CODEOWNERS only after the actual
maintainer/reviewer identities have been selected.

See [code review](code-review.md) and [M1 readiness](m1-readiness.md).
