# S4 — Pattern detection and the scope-aware allowlist

The output validator currently catches only values it can compare against the
source (`RawValueLeakValidator`). That misses the case the platform most needs to
catch: a sensitive value that was never in a classified field — a national
identifier sitting in a free-text note, an IBAN pasted into a description.

Pattern detection closes that. It also creates the problem that made this slice
impossible to bolt on later, and the reason it must be built in one piece:

**A naive scanner rejects the platform's own output.** `SYNTHESIZE` produces
emails (`person.kz48@example.invalid`), addresses and names. An email detector
matches those, fail-closed refuses the response, and every synthesising profile
deadlocks. The validator therefore has to know which values this scope emitted.
See `docs/design-review.md` §A5.

## Owns

- `data-prism-validation/**` (all of it)
- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/ScrubbingEngine.java`
- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/JsonTreeScrubbingEngine.java`
- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/ScrubResult.java` (new, if you take that approach)
- `data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java`
- `data-prism-core/src/test/java/io/github/aindriub/dataprism/core/**`

Do not edit `data-prism-pseudonymisation`. S2a owns it and is running
concurrently. If you need the generator to change, stop and report instead.

## Depends on

None. S3 is merged.

## Acceptance

- `SensitiveDataScanner` detects, at minimum: IBAN (with the mod-97 check),
  payment card PAN (Luhn), Irish PPSN and US SSN shapes, email, international
  phone, JWT, and common API-key prefixes. Structure and detection are separate
  concerns: the scanner reports matches, the validator decides.
- **Every fixture uses documented invalid check digits.** A valid IBAN or
  national identifier committed to this repository is itself the leak the project
  exists to prevent. `docs/conventions.md` requires this.
- Detection runs over the whole tree at any depth, including inside arrays.
- A `SensitiveMatch` records path, classification and detection method, and
  **never the matched value** — violations reach logs and audit.
- The scope-aware allowlist: values this scope emitted are not violations. The
  engine has to report what it emitted; `ScrubbingEngine` returning a result
  carrying both the tree and the emitted set is the expected shape, but choose
  what is cleanest. Lookup must be constant-time and the emitted values must
  never be logged.
- **Prove the allowlist is not vacuous.** A synthesised email must pass, and the
  same response with a *real* email injected must be refused. If the first test
  passed because detection silently failed, the second catches it.
- Refusal stays fail-closed: a detected leak refuses the response, and there is
  no log-and-continue path.
- The existing `RawValueLeakValidator` behaviour is kept — exact-match against
  source values, normalisation-aware. Pattern detection is additive.
- Scanning is bounded: a size cap on what is scanned, so a large payload cannot
  turn the validator into the slowest part of a request.
- `mvn -B verify` passes from the repository root, and the end-to-end and
  architecture tests in `data-prism-example` still pass untouched.

## Context

- `data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/RawValueLeakValidator.java`
  — the existing validator and its normalisation handling.
- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/SourceValues.java`
  — how the prohibited set is built, including the nested walk.
- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/Text.java`
  — canonicalisation; comparisons use it, and a leak spelled in another Unicode
  form must still be caught.
- `docs/design-review.md` §A5 — why the allowlist exists.
- `docs/conventions.md` — the privacy rules a diff must satisfy.
