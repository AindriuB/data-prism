# Contributing

The walking skeleton and every slice through S9a are built — 12 Maven modules,
a passing test suite, and a working end-to-end path from an MCP call to a
pseudonymised response. See `README.md` for what exists and `docs/plan/PLAN.md`
for what is open. Review of `docs/design-review.md` and `docs/architecture.md`
— particularly anywhere the reasoning is wrong rather than merely different —
is still useful, alongside code contributions against the open plan.

## Before a pull request

Read `docs/conventions.md`. Two of its sections carry more weight than the rest:

- **Privacy rules a diff must satisfy.** These are enforced literally, and a pull
  request that breaks one is rejected regardless of what else it does. Most are
  mechanical — no second `ObjectMapper` below the MCP layer, no source DTO in an
  MCP signature, no `Random` or `Instant.now()` in a pseudonymisation path, no
  value from a source payload interpolated into a log line.
- **Acceptance-criteria discipline.** Every change states what would have to be
  observed for it to be wrong.

Read `docs/architecture.md` for the module dependency rules. They are enforced by
ArchUnit, so a violation fails the build rather than review.

## Test data

**Never use real personal data**, in a fixture, a test, a doc or a commit
message. Test data is obviously synthetic. Leak-detection fixtures use documented
*invalid* check digits — a valid IBAN or national identifier in a test file is
itself the leak the project exists to prevent.

Leak tests assert on absence, which is easy to write vacuously. Every one is
accompanied by a mutation proving it fails when the protection is removed.

## Reporting a security issue

Do not open a public issue. The privacy engine and the response validator are
security-critical: a defect in either can disclose personal data. Report
privately through GitHub's security advisory form on this repository.

Findings in `docs/design-review.md` and `docs/pack.md` are published
deliberately — the threat model is public on purpose, and describing a weakness
already documented there is not a vulnerability report.

## Development setup

Work is split and run with an external agent kit; it is not vendored here, and
`.claude/` is a plain, untracked, gitignored directory populated by installing
the kit — not a symlink. Committing it would fork this copy from the kit's
upstream, and committing its `settings.json` would hand you a tool-permission
allowlist you never reviewed. Install it yourself and it stays current:

```bash
git clone <kit-repo> ~/.claude-kit
~/.claude-kit/install.sh .
```

On Windows, `install.ps1 -Target .`. Note that the installer's PowerShell script
must be saved as UTF-8 **with** a BOM, or Windows PowerShell 5.1 decodes its
em-dashes as CP1252 and the script will not parse.

None of this is required to contribute. It is how the maintainers split work; a
plain clone, an editor and Maven are enough.

## Licence

Contributions are accepted under the Apache License 2.0. By opening a pull
request you agree your contribution is licensed under it.
