# M1 Readiness and Initial Code Review

> Historical M1 entry-readiness review. It predates M1 implementation and does not
> describe current project status. M1-M4 are now implemented; see
> [project status](project-status.md) and [architecture](architecture.md) for current
> behavior. Retained as a record of the M1 decision and its original evidence.

## Decision

At the time of this review, the foundation was suitable for **scoped M1 development once
the canonical quality gate passed**, with the existing experimental-stack limitations
retained. This was not a production-readiness decision; M1 functionality had not yet
started at that historical point.

The original M0 code had passing smoke tests but lacked automated quality enforcement,
current requirement evidence and important isolation regressions. The engineering
harness addresses those gaps before new business functionality is added.

## Implementation-session self-review

| Finding | Disposition and evidence |
|---|---|
| Dense Java formatting had no enforced convention | Spotless formats and checks production/test sources |
| No executable review/quality gate in CI | Pinned GitHub workflow runs the same `make verify`; PR template and review checklist added |
| Historical JSON could be mistaken for current verification | Fresh reports, source fingerprint and failing checks for missing/stale evidence; original JSON explicitly historical |
| Concurrent graph histories and budgets lacked regression coverage | Eight concurrent invocations against one model, with separate histories and per-invocation budgets |
| Provider request options were only partially checked | Both tool turns assert DeepSeek thinking/temperature and absence of those fields for Grok |
| Failure and timeout tests covered only DeepSeek | Both providers parameterized; minimum invocation counts checked by requirement gate |
| Timeout assertion accepted any exception | Now requires an HTTP timeout cause and one request attempt |
| Startup smoke bypassed production main | Boot test uses `UseMainMethod.ALWAYS` and closes its context |
| Missing automatic coverage floor | 85% line / 75% branch per module; no production exclusions |

This is an author/implementation-session review, not independent reviewer approval.
No unresolved M0 code defect was identified by this review beyond the documented
compatibility limitations. Final test/coverage outcomes are in the generated
`target/quality/requirements-report.json`, not inferred from this document.

## Remaining conditions and risks

- Java 27 works locally, while Boot's published support range ends at Java 26.
- AI/Alibaba dependencies remain milestones. DeepSeek thinking is disabled with a
  provider-specific serialization workaround. Do not remove it without wire tests.
- At the time of this M1 review, DeepSeek and Grok live verification were NOT_RUN.
  Offline stubs did not prove account/model availability or model quality. Later
  operator-reported DeepSeek verification is recorded in
  [framework compatibility](framework-compatibility.md); Grok remains NOT_RUN.
- CI files are local until pushed; GitHub execution and required-review/branch-protection
  settings have not been verified or changed. A maintainer should enable the documented
  protections and arrange independent review before merging.
- The registry exposes ChatModels for trusted internal use. M1 must keep request options
  and mutable model defaults out of client control; streaming remains outside M0 scope.

## M1 entry and exit gates

Entry: review the compatibility report, run `make verify`, and obtain the user's
explicit M1 go-ahead. Build synthetic read-only commerce tools, provider-selected chat,
and basic traces according to `m1-plan.md`. Keep reservations as proposals until M2
provides authorization, confirmation and durable transactional idempotency.

Before M1 is declared complete, split its deferred requirement group into executable
product/inventory/order, bad-input, unknown-tool, provider-error, REST validation and
trace acceptance tests. Run the same quality gate and review the final diff. Do not
claim Protected mode, write safety or evaluation improvements prematurely.
