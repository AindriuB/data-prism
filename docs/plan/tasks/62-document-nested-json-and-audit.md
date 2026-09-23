# 62 — Document nested catalogues and the durable audit chain

**Repo:** .
**Depends on:** 60, 64, 66, 71
**Owns:**
- docs/architecture.md
- docs/protect-your-own-api.md
- docs/extending.md
- docs/audit.md (new)
- examples/json-sources/**

## Goal
Two claims in the shipped docs go stale with this release: that the configured
JSON catalogue is flat by design, and that the only audit sink is a logger with
nothing verifying it. Correct both, publish a worked nested example, and state
exactly what the audit chain's tamper-evidence does and does not prove.

## Context
- `docs/architecture.md:104-113` — "its allowlisted `fields:` catalogue is flat by
  design (`ConfiguredJsonFieldMetadataResolver.descendable()` always returns
  `false`)", now false; `:166-170` — boundary 7, "partially enforced", which task
  65 extends to the file sink but still not to metric labels or trace attributes;
  `:277-285` — the "no vendor client in core" decision the file sink sits inside.
- `docs/protect-your-own-api.md` — the YAML-only walkthrough; `:440` cites the
  `UNKNOWN_FIELD` refusal and the guide currently sends a reader with a nested
  response to `docs/extending.md`.
- `docs/extending.md` — positioned by task 58 as the escape hatch for nested
  responses; one level of nesting is no longer a reason to leave the no-code path.
- `examples/json-sources/customer-api.yaml` — the published, fully commented
  catalogue to extend with a nested sub-catalogue.
- Task files 60, 64 and 66 for the exact grammar, record format, CLI flags, exit
  codes and limitation wording.

## Acceptance
- [ ] `docs/architecture.md`'s flat-by-design paragraph is replaced with what is
      actually true after task 60: one level of named sub-catalogues, no recursion,
      no dotted paths or JSONPath, exact-match property names, and the unchanged
      `subject-json-path`. It still says what the mode refuses to become.
- [ ] `docs/architecture.md`'s boundary 7 entry names the audit file scan from task
      65 alongside `PiiLogScanTest`, and still says metric labels and trace
      attributes are unscanned.
- [ ] A new `docs/audit.md` documents the chain, `FileAuditSink` (single file,
      append, fsync per record, **no rotation**), and the offline verifier: how to
      run it, what each exit code means, and how a break is reported.
- [ ] `docs/audit.md` states the limitation in the owner's terms: tamper-evidence
      here is **intra-writer edit and delete detection**; truncation of the most
      recent records cannot be detected, because an append-only file with its tail
      removed verifies perfectly end to end, and detecting that needs an external
      checkpoint outside the operator's control which this release does not build.
      Durable append-only-ness is an operator responsibility — `O_APPEND`, WORM,
      object lock. A truncated final record is "possibly in flight", not proof of
      either tampering or health.
- [ ] No sentence in any file this task owns claims the audit log is tamper-proof,
      immutable, or independently complete, and none claims rotation, retention or
      shipping is provided.
- [ ] `examples/json-sources/customer-api.yaml` (or a sibling example beside it)
      carries a commented nested sub-catalogue that parses under task 60's grammar,
      and a test or documented command shows it loading rather than only asserting
      in prose.
- [ ] `docs/protect-your-own-api.md` shows the nested case end to end — the YAML,
      the response, the scrubbed result — and names the deeper-than-declared refusal
      code from task 60.
- [ ] The three stale pseudonym literals in `docs/protect-your-own-api.md` —
      `SUBJ-3WR4` and `Casey Okafor (5K38)` at :387, and `SUBJ-3WR4` again at :394 —
      are refreshed to the eight-character forms task 71 produces, and the address in
      that output carries the tag task 71 gives `ADDRESS`. The replacements are pasted
      from a real run, not hand-edited: this walkthrough is verified by following it
      literally end to end, so its quoted output must match what the commands actually
      print, and an invented pseudonym is unfalsifiable. (Task 71 widens the
      discriminator, so the correct values do not exist until it lands — hence the
      dependency.)
- [ ] `docs/extending.md` no longer lists "a nested response" as a reason to leave
      the configuration-driven path; it lists two levels or more, custom fetch
      logic, and models a catalogue cannot express.
- [ ] Every command and YAML fence in these files was actually run or loaded by
      whoever wrote it, against the real server where the claim depends on the
      transport — the SSE-not-bare-JSON lesson from tasks 56 and 58.

## Out of scope
- `docs/configuration.md`, `docs/quickstart.md` and `README.md` — task 59 owns all
  three, and must gain the nested-catalogue reference, `dataprism.identity.resolver`,
  and the `dataprism.audit.sink: hash-chained` properties there.
- `CHANGELOG.md` and `server.json` (task 70).
- Any source change.

## Attempt 1 — failed on review (2026-09-23)

Tester: PASS. Build and full suite green (603 tests). The walkthrough in
`docs/protect-your-own-api.md` was executed against real processes and the
quoted SSE frame matched byte for byte, including the refreshed pseudonyms
`SUBJ-VHK4SXCQ`, `ORGANISATION_IDENTITY-6BE1NJ46`, `Casey Okafor (G2C8D3R4)`.
The `docs/audit.md` verifier cases were run for real: intact → exit 0,
same-length edit of a middle record → `CHAIN BREAK at sequence 2`, exit 2,
tail deletion → intact, exit 0 (the stated limitation, demonstrated).
`customer-api-nested.yaml` loads and scrubs as documented. Keep all of that.

Reviewer: CHANGES. Owner decisions all hold (no truncation-detection claim,
one level only, no Slf4j verification offered). Follow-up item 8
(`AUDIT_SINK_BEAN_REQUIRED`, `AUDIT_SINK_FILE_UNUSABLE`,
`dataprism.audit.file-path`) belongs to task 59, not here. WHAT FAILED:

1. `docs/audit.md:63-64` says the bring-your-own sink is "named by
   `dataprism.audit.credential-reference` (`APPROVED_SINK`)". Wrong: it is
   selected by `dataprism.audit.sink: approved-sink` and requires an
   `AuditSink` bean, else startup refuses with `AUDIT_SINK_BEAN_REQUIRED`
   (`DataPrismProperties.java:177`, `DataPrismContractValidator.java:72-75`).
   An operator setting credential-reference gets no custom sink. Naming the
   refusal code in that clause is welcome.
2. `docs/audit.md:122-124` says the verifier "refuses outright" to read
   `Slf4jAuditSink` output. It has no such check: log lines become
   `UNPARSEABLE_RECORD` and the run exits 2, "break detected"
   (`AuditChainVerifier.java:266,371`), which `audit.md:165` tells the reader
   to treat as edit/deletion evidence. Describe what actually happens — a
   false tamper alarm — and say plainly not to point it at log output.
3. `docs/protect-your-own-api.md:420-431` shows loading the nested example
   via a Java fence with its wiring elided (`// ... wire ...`), while the
   close-out table at `:570` claims every fence was run. Either make the
   fence something a reader can run as written (complete program or jshell
   script — a new test is outside Owns), or drop the "every fence run" claim
   for that fence.
4. `docs/audit.md:191-196`, the "What it proves" sentence ("that this check
   did not also have to recompute past to reach...") does not parse. Rewrite.

## Attempt 2 — failed on test and review (2026-09-23)

All four attempt-1 findings are resolved except that finding 2's correction
was itself wrong (see 1 below — the attempt-1 reviewer's source reading was
the error; this time the tester actually ran it). Nothing in
`architecture.md`, `extending.md` or the YAML examples changed; keep them.
`NestedCatalogueWalkthrough.java` compiles, prints byte-identical output on two
runs, and matches the doc verbatim ON A MACHINE THAT ALREADY HAS A BRANCH
BUILD INSTALLED. Scope clean, no secrets. WHAT FAILED:

1. `docs/audit.md:80-81` (tester, by execution) says Slf4jAuditSink log lines
   are reported as `UNPARSEABLE_RECORD` and the run exits 2. Actual: log lines
   are not 0x1F-delimited, so `AuditRecordFormat` throws exactly
   `IllegalArgumentException` on field-count mismatch, which
   `AuditChainVerifier.classifyParseFailure` (`:254-265`) deliberately
   classifies as `INTERRUPTED_WRITE_FRAGMENT` — output "INTERRUPTED WRITE, not
   tampering: ...", exit **4**. Reproduced for one line and for three. The
   hazard to document is therefore a FALSE REASSURANCE, not a false alarm: a
   file that is not an audit file at all reads as benign interrupted writes.
   Say that, and say plainly never to point the verifier at log output. Run it
   before quoting it. No code change is in scope.
2. `NestedCatalogueWalkthrough.java:14-20` and
   `docs/protect-your-own-api.md:496-500` (reviewer): the documented run is not
   reproducible on a clean machine. The branch is versioned 0.2.0, which is on
   Maven Central; the build step is `package` (installs nothing), and
   `mvn dependency:build-classpath` run inside the module resolves
   `data-prism-pseudonymisation:0.2.0` from Central — pre-task-71
   discriminators or a linkage error, not `Rowan Walsh (4MZ4CCK9)`. It worked
   for the author only because a branch build was already in `~/.m2`. Fix:
   either `mvn -q install -DskipTests -pl data-prism-connectors-rest -am`
   first, or put every `-am` module's `target/classes` (pseudonymisation,
   validation, orchestration, ...) on the classpath ahead of the resolved
   dependencies. Prove it with an empty/isolated local repo
   (`-Dmaven.repo.local=<fresh dir>`), not the shared `~/.m2`.
3. Minor, fix while there: the doc (`:490`) and the program header (`:2`) call
   it "package-private itself" but it is `public final class` — say it lives
   in that package. Use a fixed instant
   (`Instant.parse("2030-01-01T00:00:00Z")`, as the mirrored test does) rather
   than `Instant.now()` (`:93`). The close-out row at `:563` says "run once
   per shown output" but one run prints all three; name the file.

## Attempt 3 — failed on review (2026-09-23)

Tester: PASS. Build and full suite green. The nested walkthrough's exact
commands, run against a fresh empty local repo (`-Dmaven.repo.local=<empty>`),
reproduce the quoted output byte for byte, twice. Real Slf4jAuditSink-format
lines give `INTERRUPTED WRITE, not tampering`, exit 4, exactly as
`docs/audit.md:79-90` now says. Every exit code (0-4) and anomaly name in
`docs/audit.md` matches `AuditChainVerifierCli`. All three attempt-2 findings
are resolved; keep all of it. WHAT FAILED:

1. Wrong grammar claim, present since attempt 1, in three places:
   `docs/extending.md:38-39`, `docs/protect-your-own-api.md:485`,
   `examples/json-sources/customer-api-nested.yaml:8-9` all say a nested
   catalogue's leaves "may be `identifier`, `nonSensitive` or classified".
   The loader refuses `identifier: true` inside a nested catalogue
   (`ConfiguredJsonSources.java:349-353`: "a nested catalogue carries no
   identifier of its own, and inherits its subject from the enclosing record";
   pinned by `ConfiguredJsonSourcesTest.nestedCatalogueEntryCannotBeIdentifier`).
   It also contradicts `docs/architecture.md:114` and the same YAML's own
   comment at `:66-67`. Say `nonSensitive` or classified only, and that
   `identifier` and `nested:` are both refused there. Grep the whole diff for
   any other place stating the leaf grammar.
2. Minor, fix while there: `docs/protect-your-own-api.md:545` says neither the
   fixture's field name nor ... appears in the refusal message, but the
   quoted refusal contains `$.address.postcode` — it means the raw values; say
   so. `:496` says "install both modules first" but the `-am` command
   installs every upstream module; say that, and note that it overwrites the
   reader's local `0.2.0` artifacts in `~/.m2`.

## Attempt 4 — failed on review (2026-09-23)

Tester: PASS. Build and full suite green; the walkthrough output still
matches verbatim. The new leaf-grammar claim holds by execution: scratch
catalogues with `identifier: true` or `nested:` inside a nested catalogue are
refused with the loader's messages, while `nonSensitive` and classified
leaves load. The three attempt-3 locations are fixed; keep them.

Process note: attempt 4 rebased onto `origin/v0.3.0/audit-trail-and-nested-json`,
which is 25 commits behind the LOCAL branch of that name, producing duplicate
copies of base history. The branch was rebuilt by hand as local base + the
four task-62 commits. Rebase onto the LOCAL `v0.3.0/audit-trail-and-nested-json`
ref only, and afterwards check that
`git log --oneline v0.3.0/audit-trail-and-nested-json..HEAD` lists only
commits starting `62:`.

WHAT FAILED:

1. The same leaf-grammar error in a fourth place: `docs/architecture.md:111-113`
   says a nested catalogue's leaves have "exactly the same three leaf shapes
   as the root". The root's three include `identifier`, which is refused
   there, and the very next sentence says so, so the paragraph contradicts
   itself. Say "the root's `nonSensitive` and classified leaf shapes".
   Then grep EVERY file in the diff for "three", "same", "shapes", "leaf" and
   "identifier" and check each hit near a statement about nesting.
2. `docs/protect-your-own-api.md:549-551` still says the raw field name from
   the response body does not appear in the refusal. It does: names match
   exactly, so `postcode` in `$.address.postcode` IS the wire name
   (`ConfiguredJsonNestedLeafShapeGuard.java:92-94`). Attempt 3 asked for "raw
   values". Say that no raw value, and nothing from inside the unexpected
   structure, appears in the message.
3. Minor: the intro's statement of limits (`docs/protect-your-own-api.md:18-20`)
   names only the `nested:` refusal inside a nested catalogue; add "or
   `identifier: true`" to match the other statements.

## Attempt 5 — failed on review; now also waits on task 75 (2026-09-23)

Tester: PASS. Build and full suite green; walkthrough output still verbatim.
By execution, a distinctive raw value, key and structure at the stale nested
leaf do not appear in the `NESTED_LEAF_NOT_SCALAR` message — only the
catalogue-declared path does, exactly as the doc now says. All attempt-4
findings resolved; keep them. WHAT FAILED:

1. BLOCKING. `docs/audit.md:159-161` says "Editing or deleting a record
   anywhere but the very end of a writer's chain breaks every hash after it".
   Editing the FINAL record is caught too: the verifier recomputes each
   record's own hash (`AuditChainVerifier.java:211-218`), so editing e.g.
   `subjectPseudonym` in the last record gives CHAIN BREAK, exit 2. Only
   DELETING the most recent records goes undetected. Say: edits are caught
   anywhere, including the last record; only deleting the tail escapes.
2. `examples/json-sources/customer-api-nested.yaml:60-62` points at
   docs/architecture.md for the refusal of an unreferenced nested catalogue,
   but architecture.md never mentions it (the refusal is real,
   `ConfiguredJsonSources.java:209-213`). Drop the pointer or cite the loader.
3. Minor, fix while there: `docs/audit.md:172` and table row 3 give "possibly
   in flight" as exit 3 without saying that applies only when there is no
   break or anomaly (`AuditChainVerifierCli.java:92-101`, precedence 2 > 4 >
   3). `docs/audit.md:150` says the CLI "prints the limitation below, in
   full", but the CLI prints its own, differently worded LIMITATION
   paragraph; say it prints a limitation statement and the section below is
   the full account.

NEW DEPENDENCY, owner decision 2026-09-23: a hash-chained server restarted
with the same `writer-id` currently raises a false CHAIN BREAK (every boot
restarts at GENESIS/sequence 1 under the same instanceId). Task 75 fixes this
in code by making instanceId `<writer-id>/<per-boot uuid>`. Do not start
attempt 6 until 75 has merged. Then `docs/audit.md` must describe the real
instanceId shape and state plainly, alongside the truncation limitation, that
deleting ALL of one boot's records is undetectable from inside the file. Do
not document the pre-75 behaviour.
