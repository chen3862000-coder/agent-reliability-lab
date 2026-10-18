## Change

Describe the concrete problem and resulting behavior. List affected requirement IDs.

## Verification

- [ ] `make verify` passed on this revision; attach CI evidence.
- [ ] Regression tests cover the changed behavior and relevant failure paths.
- [ ] Requirement mappings reflect actual evidence; skips are not claimed as passes.
- [ ] No live provider calls run in ordinary builds.

## Review

- [ ] Review [the checklist](../docs/code-review.md), including trust boundaries, isolation and retry behavior.
- [ ] Record unresolved findings, compatibility changes and any manual acceptance needed.
- [ ] Confirm this PR stays within the approved milestone.

Live-provider evidence, if applicable: provider/model, test command and actual result (never credentials).
