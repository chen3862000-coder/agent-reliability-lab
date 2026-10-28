# Code Review Checklist

Use this checklist for each PR; automated checks are necessary but not sufficient.
The author supplies affected requirement IDs, fresh `make verify` evidence and any
limitations. A reviewer inspects the final diff and the relevant surrounding code.

## Review order

1. **Scope and requirements:** Does behavior match the documented scope? Are tests
   asserting requirements rather than copying implementation? Are unsupported claims
   and skipped live checks clearly labeled?
2. **Correctness:** Follow success and failure paths, missing records/configuration,
   malformed input, partial results, exceptions, timeouts and cleanup. Check whether
   failures can be mislabeled as successful responses.
3. **State and concurrency:** Verify fresh graph/hook/context state, immutable provider
   defaults and no cross-request history or option leakage. Look for hidden SDK retries.
4. **Trust boundaries:** A model never grants authorization or invents trusted keys.
   Additional write paths need exact-argument confirmation and transactional idempotency.
   No business operation may bypass the controlled execution boundary.
5. **Compatibility:** Inspect changed dependencies and actual upstream APIs. Rerun wire
   regressions when changing providers, serialization, retries or framework versions.
6. **Maintainability:** Keep core independent of Boot composition, avoid duplicate
   orchestration and speculative layers, and ensure public APIs describe their limits.
7. **Evidence:** Read tests and coverage, not just green badges. Check for disabled tests,
   threshold reductions, altered requirement statuses and overly broad assertions.

## Findings and disposition

Record file/location, concrete trigger, impact, severity and a reproducible check.
P0/P1 issues block merge; P2 correctness issues need a fix or an explicit, documented
maintainer decision. Do not silently mark an unresolved finding as accepted. After
fixing a defect, add a regression and rerun the canonical gate on the final revision.

Automated checks and author self-review are not independent approval. A separate review
is recommended before merging. No automatic review bot posts comments or sends source code
to another service as part of this harness.
