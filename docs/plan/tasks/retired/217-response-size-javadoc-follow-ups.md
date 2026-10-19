# 217 — Fix the response-size Javadoc found in the task 193 review

**Repo:** .
**Release:** 0.7 (small)
**Branch:** from `main`.
**Depends on:** none
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/limits/RequestLimits.java (class Javadoc only)
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ResponseSize.java (Javadoc of `tree`, `:130-133`, only)
- docs/plan/tasks/193-enforce-record-and-response-size-limits.md (the Outcome sentence at `:92-93` only)
- docs/plan/tasks/217-response-size-javadoc-follow-ups.md (Outcome section only)

## Goal
The review of task 193 found two documentation faults. In `RequestLimits`,
the paragraphs on what counts as a record and what the byte limit measures
sit under `@param maxSources`, so Javadoc renders them as part of that
parameter. The claim that the measured size is "at most three times the
compact UTF-8 size" holds only for well-formed text. A lone surrogate
reserves six bytes but encodes to fewer, so the claim fails as written. The
overcount is safe because it refuses early, and the docs should say so.

## Context
- `RequestLimits.java:6-38`: the class Javadoc. `@param maxSources` is at `:15`, and the record and byte-limit paragraphs follow at `:16-27`.
- `ResponseSize.java:130-133`: the Javadoc of `tree(JsonNode)`, which contains the three-times claim.
- `docs/plan/tasks/193-enforce-record-and-response-size-limits.md:92-93`: the same claim in the task 193 Outcome.

## Acceptance
- [ ] In `RequestLimits`, the record paragraph and the byte-limit paragraph come after the opening description and before the first `@param`. `@param maxSources` is followed directly by the next `@param`. `./mvnw -pl data-prism-core javadoc:javadoc` shows no new warning.
- [ ] `ResponseSize.tree`'s Javadoc states the bound as holding for well-formed text (no lone surrogates). It also says that a lone surrogate can make the result exceed three times the compact UTF-8 size, and that this overcount is safe because it can only refuse a body that would fit. The `@param maxResponseBytes` text in `RequestLimits` and its byte-limit paragraph make no unqualified "three times" claim. (They contain none today; if you add one, qualify it the same way.)
- [ ] The task 193 Outcome sentence at `:92-93` carries the same qualification. No other line of that file changes.
- [ ] `git diff` shows only comment and Markdown changes, with no executable line changed. `./mvnw -pl data-prism-core,data-prism-orchestration -am verify` exits 0.

## Out of scope
- `docs/configuration.md`, including the `dataprism.limits.max-response-size` row. Task 201 owns that wording.
- Any change to how `ResponseSize` measures, including making lone surrogates exact.
- Other Javadoc in either class.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Any example is invented and neutral. Use no real person, organisation, workplace or business domain.
- Say "supports", never "compliant".

## Outcome

Record and byte-limit paragraphs moved above the first `@param` in `RequestLimits`. The three-times bound is now qualified to well-formed text in `ResponseSize.tree` and the task 193 Outcome, with the lone-surrogate overcount noted as safe. Comments and Markdown only.
