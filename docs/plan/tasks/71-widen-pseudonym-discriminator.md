# 71 — Widen the pseudonym discriminator to 40 bits and give ADDRESS one

**Repo:** .
**Depends on:** none
**Owns:**
- data-prism-pseudonymisation/src/main/java/io/github/aindriub/dataprism/pseudonymisation/HmacSyntheticGenerator.java
- data-prism-pseudonymisation/src/test/java/io/github/aindriub/dataprism/pseudonymisation/HmacSyntheticGeneratorTest.java
- data-prism-pseudonymisation/src/test/resources/golden-vectors-v1.tsv
- data-prism-pseudonymisation/src/test/resources/golden-vectors-western-v2.tsv
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/PseudonymisationVersion.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/PseudonymisationVersionTest.java (new)
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/WorkedExampleTest.java (pseudonym-width regex only)
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/McpHttpEndToEndTest.java (pseudonym-width regex only)
- data-prism-quickstart-extension/src/test/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartSmokeIT.java (pseudonym-width regex only)
- docs/tools.md (pseudonym literals only)
- docs/agents/stdio.md (pseudonym literals only)
- docs/agents/remote-http.md (pseudonym literals only)

## Goal
The synthetic-identity discriminator is twenty bits, and for `EMAIL`, `NONE` and
the `default` namespace branch it is the entire pseudonym, so two subjects in one
scope share an identity at around 1,205 subjects. `ADDRESS` is worse — it renders
no discriminator at all, giving 115,200 distinct values and a collision at around
400 subjects. Widen the discriminator to forty bits, give `ADDRESS` the same
discriminator every other namespace carries, and make an algorithm too short for
the generator's own digest reads fail at construction instead of at runtime.

## Context
- `data-prism-pseudonymisation/.../HmacSyntheticGenerator.java:142-156` — the
  javadoc and `discriminator`: `unsigned(d, 0) & 0xFFFFF`, rendered as four
  Crockford base32 characters.
- `HmacSyntheticGenerator.java:88-102` — `render`. The tag is the whole
  discriminating content for `EMAIL`, `NONE` and `default`; `:98-99` records that
  `NONE` is the scope-local token audit writes instead of the real identifier.
- `HmacSyntheticGenerator.java:122-127` — `address`: number from `unsigned(d, 12)`,
  street at offset 16, town at offset 20, and no tag. `unsigned(d, 20)` reads
  digest bytes 20-23, so the generator requires a MAC output of at least 24 bytes.
- `data-prism-pseudonymisation/.../HmacValueTokenSource.java:48-57` — the same job,
  already decided at 40 bits and eight characters, with the reasoning in its
  comment. This change makes the two agree.
- `data-prism-core/.../PseudonymisationVersion.java:27-32` — the compact
  constructor, four `requireNonNull` calls and no validation of `algorithm`.
- `HmacSyntheticGeneratorTest.java:178-182` — the three locale regexes pinning the
  four-character width; `:93-111` — the two golden-vector readers.
- `docs/conventions.md`, "Code comments" — a comment must not assert a state nobody
  established; "Tests" — golden vectors, and the mutation-proof rule.
- `docs/plan/HISTORY-INDEX.md`, task 47 — the precedent for re-capturing a worked
  example in `docs/agents/` by driving the fixture server rather than hand-editing.

## Settled design — implement, do not re-open
- Forty bits, eight Crockford base32 characters, matching `HmacValueTokenSource`.
- `ADDRESS` gains the tag, so every namespace follows one rule: a plausible value
  plus a visible discriminator. `ADDRESS` is the only deviation from that pattern
  today and that deviation is what produces its collision floor. The reason it is
  the worst site rather than merely another one: an address collision does not only
  make two subjects read as one, it fabricates a shared household between unrelated
  people — the platform inventing a relationship no source asserts, which is the
  opposite of what this project exists to do, and undetectable downstream because
  people genuinely do share addresses.
- **No `version` bump to `v2`.** Pseudonyms are deterministic from
  `(scope, subject, namespace, version, key)`, so this changes every previously
  issued value and none of them reproduce. The owner's decision is that nothing
  durable depends on v1 output yet, so this lands as a straight change on `v1`.
- Validate the MAC output length in `PseudonymisationVersion`'s compact constructor:
  reject an algorithm whose output is shorter than the generator reads. `HmacSHA1`
  (20 bytes) and `HmacMD5` (16 bytes) both are. `Mac.getInstance(algorithm)
  .getMacLength()` answers this without a key.
- State the reachability accurately wherever it is written down: `algorithm` is
  **not** settable from `dataprism.*` configuration today. Every construction path
  starts at `PseudonymisationVersion.HMAC_SHA256_V1` and applies `withKey` or
  `withVocabulary`, neither of which changes it, and there is no `withAlgorithm`.
  The hazard is that this is a public record in `data-prism-core`, published to
  Maven Central, so a consumer embedding the library can construct one with any
  algorithm string, and it becomes YAML-reachable the moment anyone adds a
  property. A public-API hazard and a latent configuration hazard — not a live one.
- Rejected, for the record: widening the street and town pools (linear effort across
  seven locale vocabulary files for a linear gain that never approaches the tag's
  resistance), and a synthetic postcode component (per-locale format authoring, and
  it risks emitting a real postcode — there is no address equivalent of the
  `.invalid` TLD that `EMAIL` relies on).

## Acceptance
- [ ] `discriminator` derives 40 bits and renders eight Crockford base32
      characters. A test over at least 1,000 distinct subjects asserts every tag is
      exactly eight characters drawn from the declared alphabet.
- [ ] `ADDRESS` renders the tag. Both branches are asserted on a real generated
      value: the non-Han form (`<number> <street>, <town>` plus the tag) and the
      family-name-first form from the `zh` vocabulary. `ADDRESS` has no assertion of
      its own in `HmacSyntheticGeneratorTest` today — only one golden-vector row
      pins it — so this criterion closes that gap as well as changing the format.
- [ ] **Collision property, non-vacuous.** A test generates at least 20,000
      distinct subject ids within one scope and asserts no two produce the same
      value, for each of `NONE`, `EMAIL`, `GOVERNMENT_IDENTIFIER` (the `default`
      branch) and `ADDRESS`. The test is deterministic — fixed key, generated
      subject ids, no randomness — so it either holds or fails reproducibly. The
      commit body records the mutation proving it non-vacuous: the mask restored to
      `0xFFFFF` and the width to four characters, the test run, and the observed
      failure with its namespace and duplicate count.
- [ ] The javadoc at `HmacSyntheticGenerator.java:142-148` no longer claims
      collisions inside a scope "stop being a practical concern" at twenty bits.
      What replaces it states the width, and states the ASCII-only constraint that
      the existing comment is right about, and asserts nothing else.
- [ ] `PseudonymisationVersion`'s compact constructor rejects an algorithm whose
      `Mac` output is under 24 bytes, and an algorithm name no provider offers.
      Tested: `HmacSHA1` and `HmacMD5` are both rejected; the failure carries a
      stable machine-readable code and names the algorithm; `HmacSHA256` and
      `HMAC_SHA256_V1` still construct, and `withKey`/`withVocabulary` on the latter
      still work. No message contains a key or key id.
- [ ] Both golden-vector files are regenerated and every row changes except the
      `GOVERNMENT_IDENTIFIER` prefix and the `SUBJ-`/`person.`/`@example.invalid`
      fixtures' non-tag parts. In place of a `version` bump, the commit body cites
      by reference the dated entry in `docs/plan/PLAN.md` headed **Owner decision,
      recorded 2026-09-22: pseudonym discriminator widens from 20 to 40 bits;
      `PseudonymisationVersion.version` stays at v1** — a pointer to where the
      decision was settled, not a restatement of its reasoning. The amended
      "Tests" rule in `docs/conventions.md` reads an unbumped golden-vector edit
      that cites no such entry as a finding, and equally reads a commit body that
      argues its own exception as no exception at all.
- [ ] `grep -rn '0-9A-Z\]{4}' --include='*.java' .` over tracked sources returns
      nothing. The four-character regexes at `HmacSyntheticGeneratorTest.java:180-182`,
      `WorkedExampleTest.java:62`, `McpHttpEndToEndTest.java:321` and
      `QuickstartSmokeIT.java:165` are updated to the new width.
- [ ] The pseudonym literals in `docs/tools.md` (`SUBJ-P7MF`, `Rory Vance (1WJX)`,
      `SUBJ-278Y`, `SUBJ-JJT2`), `docs/agents/stdio.md:110` and
      `docs/agents/remote-http.md:100` are replaced with values taken from an actual
      run against the fixture server, not hand-composed to look right. The commit
      body names the command that produced each capture. This is task 47's precedent
      and the reason for it: a worked example a reader follows literally must have
      been observed at least once.
- [ ] `docs/quickstart.md`, `docs/configuration.md`, `README.md`,
      `docs/protect-your-own-api.md` and `docs/extending.md` are **unchanged** by
      this diff, and the commit body carries the hand-off inventory below, one line
      per stale literal, each as `path:line — <literal>`:
      `docs/quickstart.md:146 SUBJ-AE9Y`, `docs/quickstart.md:150 Rowan Okafor (2TV5)`,
      `docs/protect-your-own-api.md:387 SUBJ-3WR4`,
      `docs/protect-your-own-api.md:387 Casey Okafor (5K38)`,
      `docs/protect-your-own-api.md:394 SUBJ-3WR4`. A re-run of the search that
      produced it (`grep -rn 'SUBJ-\|Okafor\|Vance' README.md docs/*.md`) at the tip
      of this branch returns no literal outside that inventory and outside the files
      this task owns.
- [ ] `mvn -pl data-prism-pseudonymisation,data-prism-core -am test` passes, and
      `mvn -pl data-prism-integration-tests test` passes.

## Out of scope
- Bumping `PseudonymisationVersion.version`, adding `withAlgorithm`, or making
  `algorithm` configurable from YAML. The first is decided against above; the other
  two would turn a latent hazard into a live one.
- Widening any vocabulary pool, adding a postcode component, or editing any file
  under `data-prism-pseudonymisation/src/main/resources/vocabulary/`.
- Editing `docs/quickstart.md`, `docs/configuration.md`, `README.md`,
  `docs/protect-your-own-api.md` or `docs/extending.md` — tasks 59 and 62 own those
  and take the inventory above as input.
- Caching, `CachingSyntheticValueSource`, or anything in `data-prism-hazelcast`.
- The `audit/` package under `data-prism-core` (tasks 63, 64, 66) and anything in
  `data-prism-spring-boot-autoconfigure` (tasks 67, 68, 69).
- Running or repairing `QuickstartSmokeIT` itself, which needs Docker; the regex
  edit is the whole of this task's interest in that file.
