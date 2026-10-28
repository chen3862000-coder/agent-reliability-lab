# Security Policy

## Scope

Agent Reliability Lab is a local, synthetic, unauthenticated demo. It is not a production
commerce service and must not be exposed to an untrusted network or the public internet.

The demo intentionally does not implement:

- authentication or identity-bound authorization;
- durable audit storage;
- production rate limiting or multi-tenant isolation;
- production container hardening.

Confirmation IDs are demo safety controls, not authorization. Trace and confirmation
endpoints are reachable without authentication when the demo is exposed. Provider API keys
may incur charges and can be visible to the local container environment.

## Reporting a vulnerability

Please do not open a public issue for a suspected vulnerability or include credentials,
provider keys, or personal data in an issue.

Use GitHub's private security advisory feature for this repository to report:

- credential exposure or leakage paths;
- unintended writes or confirmation bypasses;
- injection or unsafe parsing;
- container or dependency vulnerabilities;
- any issue that could affect a user who exposes the demo.

Include the affected version or commit, a minimal reproduction, and the impact. Maintainers
will acknowledge the report and coordinate a fix before public disclosure.

## Safe operation

- Keep the default loopback binding for local use.
- Do not publish the Compose frontend port or backend API to an untrusted network.
- Supply provider keys as runtime environment variables; never commit them.
- Use synthetic data only.
- Treat traces as ephemeral diagnostics, not as an audit record.
