# Security Policy

## Supported versions

| Version                 | Supported |
|--------------------------|-----------|
| Latest released version | Yes       |
| All earlier versions    | No        |

This project is pre-1.0; only the latest released version receives security
fixes.

## Reporting a vulnerability

Do not open a public issue. The privacy engine and the response validator are
security-critical: a defect in either can disclose personal data.

Report privately using [GitHub's private vulnerability
reporting](https://github.com/AindriuB/data-prism/security/advisories/new) on
this repository (the "Report a vulnerability" button under the Security tab).

You should receive an acknowledgement within 5 business days. We will work
with you to understand and confirm the issue, and aim to ship a fix before
any public disclosure.

Findings already described in `docs/design-review.md` and `docs/pack.md` are
published deliberately — the threat model is public on purpose, and
reporting a weakness already documented there is not a vulnerability report.
