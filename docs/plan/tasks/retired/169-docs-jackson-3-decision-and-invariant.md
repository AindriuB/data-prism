# 169 — Record the Jackson 3 decision and restate the mapper invariant in architecture, conventions and README

**Repo:** .
**Base:** branch from `origin/main` after 167 has merged into it.
**Depends on:** 167
**Owns:**
- docs/architecture.md (lines ~150-157, boundary 1; the D-139-A entry at ~285-293; one new "Decisions worth knowing" entry dated 2026-10-08 for J3-0 to J3-5)
- docs/conventions.md (the "No new `ObjectMapper` in or below `mcp`" bullet at ~35-37 only)
- README.md (the enforcer sentence at ~210-212 only)

## Goal
Owner decision J3-5, docs part. The docs still say the classpath is deliberately Jackson 2
(D-139-A) and that "the scrubbing engine is a Jackson module on one mapper". Neither is true
now. The engine walks a tree (§A4) and was never a registered module. The real invariant is that
only `DataPrismObjectMapper` writes and only the designated classes build mappers. Make the docs
state what 167 built and the owner decided.

## Context
- docs/architecture.md:150-157. Boundary 1's stale "installed as a Jackson module" wording, and the rule name and allowlist that 167 rewrote for builders.
- docs/architecture.md:247. The §A4 "Scrubbing operates on a Jackson tree" decision. Keep it and do not edit it; the new text can point at it.
- docs/architecture.md:285-293. D-139-A and D-139-B. Mark D-139-A superseded by J3-5 (2026-10-08) in place. Do not delete it; this section is a decision log. D-139-B (Boot 3 users stay on 0.4.x) is unchanged.
- docs/conventions.md:35-37. The privacy-rule bullet with the same stale wording.
- docs/plan/PLAN.md, the J3-0 to J3-5 paragraph. The source text for the new decision entry. It covers: private fixed mappers that are not beans and cannot be customised, and why; Jackson 3 tree types public, the mapper not; the enforcer ban list and the `jackson-annotations` carve-out; Spring's own Jackson 3 mapper is the application's, not data-prism's.
- 167's hand-back: the final ArchUnit rule names and the "Behaviour differences for owner acceptance" table. If the owner accepted a YAML difference, such as YAML 1.2 booleans, the new decision entry states it in one sentence.

## Acceptance
- [ ] `git grep -n -i 'jackson module' -- docs/architecture.md docs/conventions.md README.md` prints nothing.
- [ ] `git grep -n -E 'D-139-A' -- docs/architecture.md` shows the entry still present and marked superseded by J3-5.
- [ ] Boundary 1 names the rules as they exist after 167 (mapper construction including builders and `rebuild()`; YAML readers never write) and the seven-class allowlist. 168 adds a public-API rule in parallel with this task, and 162 adds that rule to the boundary prose. Every rule name it cites exists: `git grep -n '<name>' data-prism-architecture/` matches for each one.
- [ ] The conventions bullet reads, in substance: "Only `DataPrismObjectMapper` writes what a model sees, and only the designated classes build a Jackson mapper. A new mapper, builder or `rebuild()` in data-prism code is the finding." Its heading no longer says "in or below `mcp`" if the rule now covers all of `io.github.aindriub.dataprism..`.
- [ ] README's enforcer sentence says the enforcer keeps the classpath on Jackson 3, with Jackson 2 banned apart from `jackson-annotations`.
- [ ] `mkdocs build --strict` passes (the docs-site job).
- [ ] `git diff --stat origin/main` touches only the three Owns files.

## Out of scope
- docs/migration-0.6.md and CHANGELOG.md (162).
- The components table and boundary or ArchitectureTest prose that 162 rewrites for the package moves. Edit boundary 1 only.
- docs/pack.md, docs/design-review.md and docs/development-plan.md. They are historical specifications and stay as written.
- `.github/workflows/build.yml`'s comment, which is still accurate.

## Outcome (2026-10-08, wave 6)
Merged onto `release/0.6.0-jackson3` (task branch head 8af419fc). Docs only: `docs/architecture.md`, `docs/conventions.md` and `README.md` record the Jackson 3 decision and the one-mapper invariant. The first review asked for changes because the text overclaimed enforcement that 168 would provide, invented "Rejected" alternatives and rewrote D-139-A; all three were fixed and the coordinator verified the diff. After 168 merged, the scribe updated `docs/architecture.md` to say what is enforced.

Accepted deviation: acceptance item 1's grep is not literally empty. The restored, verbatim D-139-A text still contains "is a Jackson module", and the decision log must stay a faithful record.
