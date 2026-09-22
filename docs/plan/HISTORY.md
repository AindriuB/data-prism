# History

What was built, newest first. Append-only. Only `scribe` edits this file.

**Read this before redesigning anything.** The `Cost` line on each entry is the
point of the file — it is where the sessions that already tried the obvious
approach tell you what happened.

Do not load this file to find out whether something exists. It grows without
bound. `HISTORY-INDEX.md` carries one row per entry; scan that, then grep this
file for the exact heading it names. Every entry added here gets its index row
in the same commit.

<!--
## YYYY-MM-DD — <what landed>
<Two or three sentences: what it does now that it did not before.>
**Cost:** <what was hard, what was tried and abandoned, what not to retry.>
-->

## 2026-09-22 — Task 58: a walkthrough for a stranger's own API, and six attempts to make it both work and not write key material into the repo

A YAML-only walkthrough, `docs/protect-your-own-api.md`, that takes a reader
with a flat JSON REST API from nothing to a pseudonymised MCP response
without writing Java — using `data-prism-connectors-rest` plus
`dataprism.identity.resolver: pass-through` (task 53) and the
single-statement transport (task 54). It publishes the former test-only
catalogue as `examples/json-sources/customer-api.yaml`, fully commented,
and repositions `docs/extending.md` from the front door to the escape
hatch for nested responses, custom fetch logic and models a flat
catalogue cannot express. This is what makes the no-code path discoverable:
before this wave the path existed, shipped and published, but had no
walkthrough, its only example was a test fixture named after an internal
task number, and it was not actually no-code because nothing supplied an
`IdentityResolver` bean.

**Cost:** six attempts. Each failed round found a real defect that the
previous round's method could not see.

- Attempt 1: reviewer APPROVE, tester FAIL. The document read well and did
  not work. Four completeness defects: no build command; a run command
  whose jar paths and repo-root-relative config-location could not both be
  satisfied from any single working directory; no start instructions at all
  for the two dependent fixture services, including no `keytool` invocation,
  so the tester had to open `QuickstartSmokeIT` and reverse-engineer alias,
  SAN and `--server.ssl.*` flags; and a quoted `tools/call` response shown as
  bare JSON when the document's own curl headers produce an SSE frame.
- Attempt 2: tester PASS, reviewer CHANGES. The document worked and
  instructed the reader to write private key material into the repository
  working tree, where `.gitignore` covered none of it — a CLAUDE.md rule 3
  breach. Both precedents the document itself cited (`QuickstartSmokeIT`'s
  `@TempDir`, `generate-certs.sh`'s Docker volume) deliberately contain
  theirs; the document borrowed the invocations and dropped the
  containment.
- Attempt 3: both FAIL/CHANGES. The `mktemp -d` fix closed rule 3 properly
  but used session-scoped shell state, while the document itself tells the
  reader to open a second terminal. Reproduced:
  `java.io.FileNotFoundException: /walkthrough.p12`.
- Attempt 4: tester PASS, reviewer CHANGES. `${KS_DIR:-...}` honoured a
  reader's pre-set variable: a reader with `KS_DIR=certs` would get a
  keystore in the repo root, and one with `KS_DIR=$HOME/.keystores` would
  have the document's own `rm -rf` delete their real keystores. Also an
  undocumented `keytool` failure on re-run, now that the directory persists
  by design.
- Attempt 5: tester PASS, reviewer CHANGES. Attempt 5's own tidy was
  half-done — the fails-closed fence restated one variable but the command
  used two, so the attempt-3 failure mode survived at the variable the
  inlining missed. The tester passed it only because it carried the
  variable across from the sed step rather than using a genuinely separate
  shell, as the document instructs.
- Attempt 6: APPROVE.

Two lessons worth recording as lessons, not just events. First, a
documentation task needs a tester that follows the document literally —
using only what it says, not substituting knowledge of the codebase — and a
reviewer reading it against the project's own rules. Neither method alone
would have caught what the other did: attempt 1 was approved on a
read-through and did not work; attempt 2 passed testing cleanly and
breached rule 3. Second, two separate tasks this wave (56 and 58) both
assumed a bare JSON body from the MCP endpoint, which negotiates SSE. Both
were caught only by exercising the real server. That is a recurring trap,
not two coincidences.

**Verification note.** No tester ran attempt 6. Attempt 5 passed
independent end-to-end testing under multi-shell execution (happy path
byte-for-byte including `SUBJ-3WR4` and "Casey Okafor (5K38)", all three
refusals verbatim, rule 3 clean by `git status --short`,
`git status --ignored --short` and a filename search, cleanup leaving
nothing under `$HOME`). Attempt 6 added only restatement lines inside
existing fences plus a rephrased sentence; its implementer ran the
separate-shell check, the reviewer confirmed the diff touches no output
fence and swept the whole defect class across all eight variable-using
fences, and the main session verified the three edits directly. Solid, but
not a full independent test run of the final commit.

Left open: four verification/curl fences (`docs/protect-your-own-api.md:132,
:157, :337, :351`) follow foreground processes without naming a terminal,
unlike the server section's "a third terminal" — polish, not a defect. And
`docs/configuration.md` still does not document `dataprism.identity.resolver`
at all, despite it being the property the entire no-code path depends on —
task 59's acceptance criteria already require this.

## 2026-09-21 — Wave 1 (tasks 53, 54, 56, 57): the no-code path made real, and the demo's transport bug that a stub had hidden

The wave opened from a measured UX review, not a guess: `docker compose up
--build` cold took 5m44s (329.8s of it the server image's own Maven build);
warm `up` was 1.9s with a correct pseudonymised answer 6s later; a bare
`docker run ghcr.io/aindriub/data-prism-server:0.2.0` died in ~1.5s with a
20-line Spring stack trace ending in `MISSING_IDENTITY_RESOLVER`. The
Compose quickstart demos well but onboarded nobody, because
`QuickstartCustomerAdapter` hardcodes `SOURCE_NAME="customer"` and
`CustomerModel.class` into the image — there was no path from the demo to a
user's own API. The configuration-driven REST connector (task 20) already
protected a flat JSON API in ~20 lines of YAML, but nothing supplied an
`IdentityResolver` bean, so it was not actually no-code, had no walkthrough,
and its only example was a test fixture.

Task 53 adds an opt-in `dataprism.identity.resolver: pass-through` property
selecting `PassThroughIdentityResolver`, with `UNSUPPORTED_IDENTITY_RESOLVER`
for an unrecognised value and `@ConditionalOnMissingBean` so an
app-supplied resolver still wins. Absent the property, `MISSING_IDENTITY_RESOLVER`
stands unchanged — proven by a test asserting zero `IdentityResolver` bean
definitions exist at preflight, plus a `matchIfMissing=true` mutation run.
This is what makes the no-Java path real. Task 54 collapses the duplicate
base-url: `DataPrismContractValidator`'s cross-check moved from
`supplied.equals(configured)` to `supplied.containsAll(configured)`, so a
configured JSON source's adapter can exist with no matching
`dataprism.sources` entry, with paired positive/negative mutation tests on
both refusals. Task 56 extracts a one-command demo
(`examples/quickstart-demo/run.sh`, `mcp-handshake.sh`) out of
`smoke-test.sh`. Task 57 adds a Spring `FailureAnalyzer` that renders every
`DataPrismConfigurationException` as an operator block naming the code,
what to supply, and the two docs pages, with no stack frame — the refusal
itself untouched (`git diff` over `DataPrismAutoConfiguration.java`,
`DataPrismProperties.java` and `DataPrismContractValidator.java` is empty).
Verified on the real packaged jar: exit 1, operator block present, zero
stack frames.

Task 55 (publish the quickstart images so `compose.yaml` can pull them) is
verified PASS/APPROVE but deliberately **not merged** — see `PLAN.md`,
"Held: task 55", for why.

**Cost:** task 56's first attempt failed verification outright, and the
failure mode is the lesson: it was tested only against stub HTTP servers
returning plain JSON, but the real MCP server returns SSE-framed
`tools/call` bodies, so the demo never worked against the real stack it was
built to demonstrate — a stub standing in for the real transport hid a
total failure of the task's central claim. It also had a `set -euo
pipefail` bug where the one-line failure reason was unreachable on the
commonest failure (issuer down). Attempt 2 fixed both and was verified
against a live Compose stack, not a stub. Task 53's review also caught a
guardrail evasion worth naming honestly: the new resolver bean sits on a
nested `@Import`ed static configuration class, which keeps it outside
`AutoConfiguredBeanClassificationTest`'s reflection sweep over
`DataPrismAutoConfiguration.class.getDeclaredMethods()` — acceptable on that
branch only because its `Owns` list forbade editing
`PrivacyExtensionPoints.java`, and not acceptable to leave; see PLAN.md's
open item for the two-part fix required. Task 54's own summary described
the base-url relaxation as guarantee-preserving, which review judged
stronger than warranted: `dataprism.sources` was also the operator's
allow-list, and any `DataSourceAdapter` bean on the classpath is now
implicitly approved without appearing anywhere an operator reviewed — not
a fail-closed breach, but a real weakening, with a narrow fix scheduled
rather than done here.



`README.md` had said "Until Task 20 delivers…" the configuration-driven
JSON REST mode since before that task shipped, and it cost readers real
work: it told a stranger to write a Java adapter when a configuration-only
path may already serve them. The fix was established from the code, not
from the stale claim or the task file: `data-prism-connectors-rest` carries
no `maven.deploy.skip`, so it is a normally published artifact; it
self-registers via `AutoConfiguration.imports`, so it needs no Java from an
operator; and `ConfiguredJsonFieldMetadataResolver.descendable()` returns
`false` unconditionally, so it covers only flat JSON — scalar fields and
arrays of them, never nested objects. `README.md` and `docs/extending.md`
now agree, both written from the code independently rather than one copied
from the other.

The task was planned against four stale "only one tool ships" claims. The
count grew twice under examination: a reviewer found a fifth during task 51
(`docs/agents/stdio.md` claiming the fixture caller holds only
`GET_ENTITY_CONTEXT`), and the executing scribe found a sixth
independently — `docs/architecture.md`'s Deployment paragraph calling
configuration-driven JSON sources "deferred", the same defect as the README
line. All six are corrected using tool names taken from a real `tools/list`
response captured by driving the compiled fixture over a FIFO stdio
session, not inferred from reading `Capability.java`'s registration code.

**A false causal claim was caught at review, not planning.** The first pass
wrote that both tools appear in `tools/list` "because this fixture's caller
holds both capabilities", implying the tool listing is capability-filtered.
It is not: both tool specs register unconditionally, which is exactly why
the Compose quickstart's caller can *list* `compare_entity_sources` and not
*call* it. The underlying policy fact was true; the causal link was false,
and it contradicted both `docs/quickstart.md` and the grant-before-call
rule `docs/tools.md` now teaches. Fixed before merge. That grant-before-call
behaviour is now stated rather than left to surprise a new user:
`docker/server/application.yaml` grants the quickstart's `investigator`
role only `GET_ENTITY_CONTEXT`, so a reader following the Compose quickstart
sees both tools listed and gets `TOOL_NOT_PERMITTED` calling the second —
live in the first thing a new user runs.

Also carried: every remaining `data-prism-example` module reference across
the seven owned files renamed to `data-prism-integration-tests`;
`ArchitectureTest`'s ownership re-pointed from the renamed module to
`data-prism-architecture`; `docs/extending.md` and `docs/tools.md` linked
from `README.md`, `docs/quickstart.md` and `docs/agents/README.md`. Merged
2026-09-17 (#84).

**Cost:** the task file's own cited line numbers were stale in three
places, including `docs/quickstart.md:81`, which the task file named as
the one-tool claim's location — it was a section header, not the claim; the
real one was at `:113`. Small standing lesson: a line number cited in a
task file ages between planning and execution, and an executor that trusts
it without re-deriving (`rg -n`) edits the wrong line. Re-derive every cited
line before editing rather than assuming the coordinating brief is still
accurate.

## 2026-09-17 — Tasks 49, 50, 51: `data-prism-example` renamed to `data-prism-integration-tests`, and the first two consumer guides ship

`data-prism-example` was never an example: it is the reactor's integration
test suite, 11 test classes with no duplicate anywhere else, including
`PiiLogScanTest`, which `docs/architecture.md:156-159` names as the sole
enforcement of privacy rule 7. An architect raised the question after the
owner asked whether the module was still required — a name that says
"example" invites deletion of the thing actually guarding a privacy rule.
Task 49 renamed it to `data-prism-integration-tests`, `git mv`'d so history
still follows every file; nothing was removed. Tasks 50 and 51 then shipped
the first consumer-facing documentation this repository has had for
extending it: `docs/extending.md`, the adapter developer guide, and
`docs/tools.md`, the reference for both shipped MCP tools. Merged through
protected, green pull requests 2026-09-17 (#80, #82, #81 — 51 before 50,
see below). Post-merge full-reactor re-run: `BUILD SUCCESS`, 0 failures, 0
errors.

**A green build was treated as insufficient evidence for the rename.**
`ArchitectureCoverageTest` derives its module list dynamically from the root
`pom.xml` at runtime; its own javadoc records `data-prism-connectors-rest`
once silently dropping out of every whole-graph architecture rule this way —
a rename that misses a reference does not fail, it quietly narrows what
every rule checks, with nothing red to say so. Both the implementer and the
tester instrumented the test to print its computed module list, confirmed
the renamed module was present and the count was 18 on both the branch and
`main`, then reverted the instrumentation byte-identical.

**Six `data-prism-example` strings were left alone, deliberately.** The
rename's own sweep excludes the JWT `issuer` and audit `writer-id`
configuration values (and the tests asserting on them): these are observable
audit output, not a module name, so changing them would have been a
behaviour change disguised as a refactor. The `docs/`-facing sweep is task
52's, not 49's.

**Both guides were executed, not described.** Task 50 compiled a
deliberately-unclassified `@LlmExposedModel` field outside the repository to
capture the real annotation-processor `error:` line, and ran the packaged
server with a mismatched source name to capture a real
`UNRESOLVED_SOURCE_ADAPTER` refusal. Task 51 drove the shipped stdio fixture
over real JSON-RPC for every case it could vary, and wrote a small harness
from the repository's own public classes for the cases the fixture could
not — including reproducing a real `TOOL_NOT_PERMITTED` refusal and the
pseudonym-collapse case (two sources, different values, same pseudonym,
distinguishable only by the finding's `kind`). Both close-outs report which
examples were captured versus transcribed from a test.

**Cost:** The most transferable lesson is task 50's, and it cost three
review rounds. The guide's pom snippet was "verified" by building a
throwaway external project against it — and still shipped a defect, because
that project was not byte-identical to the block the guide displayed: it
silently carried `maven.compiler.release=21`, which the guide never told a
reader to add. A reader pasting exactly what was shown would have hit
`records are not supported in -source 8`. The fix was to reproduce that
failure first, then extract the guide's fences *programmatically* rather
than retype them, so the built artifact is provably identical to what a
reader sees; the reviewer independently reassembled and rebuilt the fences
to confirm. Verifying a neighbouring artifact is not verifying the artifact
— the same shape of mistake as a test that shares its implementation's
assumptions. Separately, the expected sequential-merge race showed up twice,
not once: after 49 merged, both 50 and 51 went `BEHIND`; 51 was updated and
merged first since its checks finished first, which put 50 `BEHIND` a
second time against 51's own merge before it, too, could go green and
merge. Each cycle was a plain `git merge origin/main` with no conflicts,
since 50 and 51 touch only `docs/extending.md` and `docs/tools.md`
respectively and 49 touches no file under `docs/`. Found but not fixed here,
because it belongs to task 52: `README.md:156-158` still says "Until Task 20
delivers…" the configuration-driven JSON REST mode, which shipped in task
20 and needs no Java for a flat-JSON source — `docs/extending.md` now points
flat-source readers at it, but the README itself remains stale until 52
corrects it. Also not referenced by task 50: the external consumer demo at
`/Users/Andrew/workspace/data-prism-github-demo` is not a public
repository (`github.com/AindriuB/data-prism-github-demo` returns 404), so no
link to it would have resolved.

## 2026-09-17 — 0.2.0 release complete: GitHub Release, Maven Central, GHCR multi-arch, and the first successful MCP registry publish

Task 48 corrected `server.json`'s OCI package block after `mcp-publisher
publish` returned 400 against the `v0.2.0` tag: "OCI packages must not have
'registryBaseUrl' field - use canonical reference in 'identifier' instead".
With that fixed and the tag moved, the publish succeeded, and every artifact
0.2.0 was cut for is now live: the GitHub Release, Maven Central (all
modules, synced roughly 12 minutes after the Portal press, confirmed by HTTP
200 on `repo1.maven.org`), the GHCR multi-arch image (verified resolving
`linux/arm64` on an Apple Silicon Mac and `linux/amd64` on an x86_64 Ubuntu
host from the same tag, both failing closed with `MISSING_IDENTITY_RESOLVER`
absent a mounted adapter jar), and the MCP registry entry —
`io.github.AindriuB/data-prism` version `0.2.0`, status `active`, published
2026-09-17T18:59:49Z, pointing at `ghcr.io/aindriub/data-prism-server:0.2.0`.
Merged through a protected, green pull request 2026-09-17 (#78). Tester: PASS,
492 tests unchanged. Reviewer: APPROVE. No task file remains under
`docs/plan/tasks/`.

**Three publish attempts, three different failures, and what each taught.**
First, 403: the registry preserves the GitHub login's casing
(`io.github.AindriuB`) in the granted namespace, Maven Central's coordinates
use lowercase (`io.github.aindriub`), and GHCR requires a lowercase image
path — three different identifiers in three different systems, each correct
as written once distinguished. Fixed by task 44. Second, 400:
`registryBaseUrl` is forbidden on an OCI package; the canonical reference,
tag included, belongs in `identifier` alone. Fixed by task 48. The decisive
move in task 48 was reading the registry's server-side validator source
(`internal/validators/registries/oci.go`, `ValidateOCI`) instead of inferring
the whole contract from the 400's error text, which named only
`registryBaseUrl`. The validator source showed `version` is *also* rejected
on an OCI package once `identifier` carries the tag — never mentioned by the
error. Fixing only what the 400 named would have produced a third failed
publish over a field the message never flagged.

**A green `mcp-publisher validate` predicted neither failure.** The
2025-12-11 schema this repository declares still lists `registryBaseUrl` and
`version` as valid optional package properties, and `validate` passed before
both the 403 and the 400. The registry enforces server-side rules the schema
does not express. Recorded here plainly because the assumption that a green
local validate meant a publish would succeed cost two real release attempts,
each needing an owner-dispatched workflow to discover.

**The `v0.2.0` tag was force-moved, deliberately, and it does not
misrepresent anything already shipped.** The tag originally pointed at
`196a4f1`, the commit carrying the rejected package block; `workflow_dispatch`
reads both the workflow file and `server.json` from the ref it is dispatched
against, so re-dispatching against the old tag would have republished the
same rejected block. The tag was moved to `ff11e0e`, PR #78's merge commit.
The only files that differ between those two commits are `server.json`,
`.github/workflows/publish-mcp.yml` and `CHANGELOG.md` — none of them inputs
to the published Docker image or the Maven Central jars, both already live
for 0.2.0 and not rebuilt by this move. Contrast with the 0.1.1 decision
(task 41): there, a tag move was rejected because `main` had diverged in ways
that would change the built artifacts. The two situations differ because
what changed differs, not because moving a published tag is more acceptable
this time.

**The guard task 48 added.** Folding the tag into `identifier` put the
version in two places inside one string — `.version` and the tag suffix of
`.packages[0].identifier` — which task 45's version sweep, built to find
version literals as whole tokens, would not have caught inside a longer
string. `publish-mcp.yml` now asserts both the identifier's repository path
(`ghcr.io/aindriub/data-prism-server`) and its tag against `.version`, each
proven non-vacuous by editing the value wrong, running the guard's script
body, and observing the `::error::` line before reverting. Parsing was
probed independently against a tagless reference, an `@sha256:` digest
reference, and a host with a port, so no shape of `identifier` produces a
false pass.

**Cost:** the two-round failure was expensive mainly in owner time — each
round trip needed a manual `workflow_dispatch` and a wait for the rejection,
and the schema's own optimism (`validate` green both times) meant nothing
short of dispatching against the real registry could have surfaced either
error sooner. The lesson that generalizes: for any registry or API with a
published JSON Schema and separate server-side business rules, treat the
schema as necessary and not sufficient, and go straight to the server
implementation's own validator source once an error message names only part
of the rejection — inferring the rest from prose cost this release its
second failed attempt.

## 2026-09-17 — Task 47: the PII scan's reflection-depth and word-boundary holes closed

`PiiLogScanTest`'s banned-value derivation now recurses through `Record`
components and `Collection` elements to arbitrary depth (depth-capped, and
proven to terminate against a self-referential structure) instead of stopping
one level deep and banning a nested component's `toString()`. `findLeaked`'s
bounds changed from `\b` to `(?<!\w)`/`(?!\w)` lookarounds, closing the case
where a banned value ending in punctuation — both fixture order notes — could
never match at end-of-line or before a space. `OrderDto` gained one nested
component, `DeliveryDto` (a courier reference plus a notes collection,
`@SensitiveObject`-annotated), so the recursion is falsifiable against a real
fixture rather than a record declared inside the test file. Merged through a
protected, green pull request 2026-09-17 (#76, after a merge of main/task 45
to land on top of it — no file overlap between the two tasks). Full reactor
`mvn -B clean verify` green, 492 tests, `PiiLogScanTest` itself still well
under a second. See `docs/plan/HISTORY-INDEX.md` for the row.

**Both holes were proven real, not just closed, by watching the old code
leak.** For hole 1, a production code path logging a raw `DeliveryDto` leaf
went RED under the recursive derivation, naming the leaf; the same mutation
with the descent reverted to one level deep went GREEN with the raw leaf
still sitting in the log, still-mutated, still leaking — that second run,
not the first, is the evidence the hole was real. For hole 2, a raw order
note ("No issues raised.") on an ordinary application log line went GREEN
under the pre-fix `\b` bounds — the hole itself — and RED under the
lookaround bounds. Both mutations were reverted; tree byte-identical after
each. The standard this repository is holding itself to now: a leak-test fix
is not proven by the new code passing, only by the old code demonstrably
failing to catch what it should have.

**The defence being replaced was characterised before it was touched, not
guessed at.** The `\b` bounds existed so that a banned three-digit id
(`"123"`, `"456"`) could not match by coincidence inside a hex run where
every neighbour is a word character — an event UUID, the 24-hex fingerprint,
the 64-hex hash chain. A new test pins that: it is shown RED under a
`contains`-based `findLeaked` (proving it isn't vacuous), and GREEN under
both the old `\b` version and the new lookaround version (proving the
lookaround constrains the boundary in both directions, the same as `\b` does
for a value whose first and last characters are word characters). Replacing
a guard whose purpose nobody had written down would have traded a known hole
for an unknown one; this is the alternative.

**A design decision worth restating because it will be questioned again: the
banned set task 46 derived, and this task extended, is classification-blind.**
It bans every fixture leaf regardless of whether the field carrying it is
`@SensitiveData` or `@NonSensitive` — the live set includes several
`@NonSensitive` values (`ACC-1`, `ORD-9`, both order notes, `CR-771`, now the
delivery notes). `@NonSensitive` authorises a value into the tool's
*response*; it says nothing about the *log*. A payload value appearing on a
log line means the pipeline is dumping payload, which is a defect regardless
of that value's classification. The one existing exclusion,
`CustomerDto.status` ("ACTIVE"), is not a counter-example: task 46 recorded
that its exclusion is about prose-collision risk against ordinary log text,
not about classification-based entitlement to appear in a log. It is not a
precedent for excluding a value because its field is `@NonSensitive`.

**Cost:** the coordinator-supplied fact "adding a nested block to `OrderDto`
adds no consistency finding and perturbs no existing assertion" was a
prediction, not a given — the implementer verified it rather than trusting
it, and it held: `EndToEndTest`, `WorkedExampleTest`,
`CompareEntitySourcesWorkedExampleTest` and `McpHttpEndToEndTest` are green
and byte-identical, all four outside this task's `Owns`. Also known and
deliberately unfixed here: `docs/agents/stdio.md:102` printed the merged
entity tree verbatim and drifted once `delivery` existed on subject `123`'s
order record. Both the implementer and reviewer flagged it rather than
editing a doc outside their `Owns`; fixed at record time by actually driving
the stdio fixture server end to end and capturing the real response rather
than hand-writing the expected shape — the same discipline the doc's own
prose claims for its captures.

## 2026-09-17 — Task 45: version 0.2.0 cut, and a sweep that had been running blind to `.github/`

Moved the tree from 0.1.1 to 0.2.0 — 19 module poms plus root, both
`server.json` version fields, the two `serverInfo` literals in
`DataPrismMcpServer.java`, all four Dockerfiles, `README.md`, and the three
PackagingIT/SmokeIT tests — so the second MCP tool (`compare_entity_sources`,
task 42/43) and the corrected MCP registry namespace (task 44) can ship.
`CHANGELOG.md` gained a 0.2.0 entry naming both changes plus the
`ContextResponse` record-component addition, and stating explicitly that the
privacy engine, pseudonymisation and security modules did not change
behaviour — every claim checked against the diffs of tasks 42-44 before being
written. Merged through a protected, green pull request 2026-09-17 (#75).
Full reactor `mvn -B clean verify` green, 488 tests, unchanged from task 46's
baseline, as expected for a version-literal-only cut. See
`docs/plan/HISTORY-INDEX.md` for the row.

**A verification method had a blind spot, and this task found it.** Every
repo-wide "no old version literal remains" sweep run in this repository has
been `rg`-based, and `rg` skips dotdirectories by default — so every sweep
that has ever signed off a version bump has been running blind to
`.github/`. Adding `--hidden` to the sweep this task's acceptance criteria
required turned up two stale `0.1.1` literals in
`.github/workflows/publish-image.yml` (the `workflow_dispatch` input's
example text and its `default:` value, lines 53 and 55) that task 41's
0.1.1 cut had missed the same way. The stale default was not a safety hole —
`publish-image.yml`'s version guard fails closed regardless of what the
input defaults to — but dispatching it unedited would have wasted a release
attempt, and a future reader finding a workflow that silently defaults to
the wrong version is exactly the kind of thing that invites someone to "fix"
it by weakening the guard instead. Every sweep this plan credits as having
verified "no old version remains" from here on should be read as having
verified that only for the paths `rg` shows by default, unless `--hidden` is
named explicitly.

**The task file said no workflow edits; the merged diff has two lines of
one.** Task 45's `Owns` list and its "Out of scope" section both barred
workflow edits — written before anyone knew a version literal was hiding in
one. The coordinator authorized a scope extension limited to exactly the two
literals at `publish-image.yml:53,55`, recorded in the branch's own second
commit rather than folded into the first so the extension is visible in the
history, not just asserted. The reviewer confirmed the guards, the
per-architecture matrix, the digest-push steps and the manifest-assembly job
are byte-identical to `main` apart from those two lines. Recorded here so the
now-deleted task file and the merged diff do not read as disagreeing with
each other.

**Cost:** none beyond the `--hidden` discovery above — this is the same
mechanical sweep task 41 ran one version earlier, and the CHANGELOG claims
were checkable line by line against tasks 42-44's diffs before being
written, so there was no second false-premise incident this cycle.

## 2026-09-17 — Task 46: PiiLogScanTest's banned values derived from the stub fixtures

`PiiLogScanTest`'s banned set is no longer a hand-maintained literal list. It
is derived from `Stub{Customer,Account,Order}Adapter`'s own `RECORDS` via a
minimal `fixtureRecords()` accessor, so adding or changing a fixture value
automatically extends what the scan looks for. The derivation yields 16
values, including the six the hand-maintained list omitted —
`Pat Murphy`, `P. Murphy`, `ACC-1`, `ORD-9`, `4200.55`, `18.00` — plus both
order notes. Proven by mutation: logging a raw record from `SourceFanOut`, a
production fetch path, turned the scan red naming 14 leaked values, including
exactly those six; under the old list it would have stayed green. Merged
through a protected, green pull request 2026-09-17 (#73). Full reactor
`mvn -B clean verify` green, 488 tests, re-confirmed independently after
merge.

`CustomerDto.status` is excluded from the derived set, and that exclusion was
challenged during review: was the scan green WITH `status` included, or did
excluding it silence a real redness — the latter being the exact failure this
task existed to fix? The implementer tested it in an isolated clone: green
with `status` included, so the exclusion is pre-emptive against future false
positives, not a silencing, and it corrected its own comment, which had
originally been written from reasoning rather than observation. The `AUDIT_KEYS`
javadoc's placeholder count was also corrected, verified against
`Slf4jAuditSink`: twenty `{}` placeholders, nineteen named fields, with
`seq={}/{}` folding two.

Two holes remain in the same control, the same shape as the bug this task
fixed — a control that narrows itself with no signal — and are recorded as
task 47: the reflection is one level deep, so a future nested record or
collection component would enter the banned set as its `toString`, leaving
the leaf values silently unbanned; and `findLeaked`'s `\b` word-boundary bound
makes a value ending in punctuation unmatchable at end-of-line or before a
space on the plain (non-audit) path, which is why the two order notes — both
in the derived set — went unflagged during this task's own mutation proof.

**Cost:** the implementer's close-out reported 462 tests before its change and
463 after. The real figures are 487 and 488 — its delta was right, its
absolute count was low by 25, because it ran a restricted reactor
(`-pl data-prism-example -am`) rather than a full `mvn -B clean verify`. A
restricted reactor silently skips whole modules (hazelcast, connectors-rest,
server, the quickstart modules, architecture-rules). This is the second
distinct test-counting error recorded in this repository, after the earlier
surefire/failsafe double-count (tasks 21-24); both were caught only because a
tester re-counted independently rather than trusting the report. A test count
in a close-out is not evidence until someone reproduces it.

## 2026-09-17 — Task 43: compare_entity_sources wired into the example, and the identity assumption task 42 could not prove is now proven

`compare_entity_sources` now runs on the real assembly — real stub adapters,
the real `JsonTreeScrubbingEngine`, the real `AuthorizationService`, real audit
— instead of only the stubbed orchestrators task 42's own tests used. The
shipped example/fixture-development role is pinned to exactly
`{GET_ENTITY_CONTEXT, COMPARE_ENTITY_SOURCES}` in all three places that grant
it. Merged through a protected, green pull request 2026-09-17 (#71). Full
reactor `mvn -B clean verify` green, 487 tests. Tester reproduced the mutation
proof independently: changing `JsonTreeScrubbingEngine`'s SYNTHESIZE case to
pass raw values through reddened two of the three end-to-end tests, then
reverted byte-identical.

**The assumption task 42 could not prove is now proven.** Task 42's `identity`
implementation rests on the real `ScrubbingEngine` keying the scrubbed tree by
`FieldMetadata.fieldName()`, not by the namespace constant. All of task 42's
own tests ran against a scrubber *stub* that already modelled that keying, so
the assumption was asserted, never tested. Task 43's end-to-end test drives
the real `JsonTreeScrubbingEngine` via `DataPrismAssembly.standard()` over
`CustomerDto.customerName` and `AccountDto.holderName` — both classified
`PERSON_NAME`, neither named `PERSON_NAME` — and gets a non-empty `identity`
keyed by the real serialised field names.
`JsonTreeScrubbingEngine.scrubObject` (`data-prism-core/.../JsonTreeScrubbingEngine.java:143-168`)
writes `out.set(field, scrubbed)` under the source's own field name, which is
exactly what the test now exercises. The assumption holds, and is checked
rather than believed — and only a test that can be broken by mutating the real
engine could have settled that; the stub-based tests never could.

**All three sources of the shipped role are now pinned, not two.** The example
grants its role in three places — `ExampleApplication`, `DataPrismAssembly`,
and `application.yaml`. `ShippedDefaultsTest` previously pinned only the two
Java factories, so a widening introduced only in the YAML would have failed
nothing. A new test binds the YAML through the same `Binder` +
`YamlPropertySourceLoader` path `DataPrismAutoConfiguration` uses at startup,
and pins both roles by equality. This is the same shape of defect task 09
exists to fix — a shipped default that did not match what was tested — caught
before it shipped this time.

**Cost:** none in effort — the real cost of this task is what it exposed, not
what it took to build. Two findings carried forward rather than fixed here:

- **The PII log scan is a weakened control, opened as task 46.**
  `PiiLogScanTest.BANNED_VALUES` is hand-maintained literals. It omits `Pat
  Murphy` and `P. Murphy` — the account-api and order-api spellings
  `get_entity_context`'s merged tree already carries — and also `ACC-1`,
  `ORD-9`, the account balance and the order note. A regression that logged a
  raw `customerName` or `holderName` from those adapters would leave the scan
  GREEN today. Nothing is leaking; this is degradation of a control, not a
  breach. The fix is to *derive* the banned set from the stub fixtures, not to
  patch in the missing literals — patching closes today's six omissions and
  leaves the drift mechanism that produced them exactly where it is.
- **A coordinator premise corrected before it could mislead an implementer.**
  Task 46 was briefed on the premise that `PiiLogScanTest` is a costly HTTP
  integration run. It is not: it drives the tool handler in-process, and the
  whole class runs in 0.022s. The planner checked and corrected the premise
  before task 46 opened. This is the third time this release cycle a
  downstream agent has corrected a factual premise supplied by the
  coordinator rather than a defect it introduced (the first two: task 41's
  false nimbus-jose-jwt claim, task 42's own record-compatibility risk) —
  worth recording alongside those two because the pattern, not any one
  instance, is the point.

**Unconfirmed, not fixed:** `QuickstartSmokeIT` failed once with an
SSL-handshake timeout under full-reactor load during implementation, but did
not reproduce across two subsequent full runs by the tester. Recorded as
suspected-environmental, not as a known bug and not as resolved.

## 2026-09-17 — Tasks 42 and 44: the second MCP tool, and the registry namespace corrected

Task 42 shipped `compare_entity_sources`, the second MCP tool: per-field
`identity` plus findings that distinguish agreement, disagreement
(`INCONSISTENT`/`FORMATTING_ONLY`/`ABBREVIATION`) and
`MISSING_IN_SOME_SOURCES` by an explicit discriminator, over the same
correlated, scrubbed `ContextResponse` that `get_entity_context` already
builds. Task 44 corrected the MCP registry namespace from
`io.github.aindriub/data-prism` to `io.github.AindriuB/data-prism`, the casing
the registry actually grants for the GitHub login, in the three strings that
carry it, and added a guard so they cannot drift apart again. Both merged
2026-09-17 through protected, green pull requests (#69, #68); full reactor
`mvn -B clean verify` green at 483 tests (466 baseline, +17).

**The defect, and why three tests missed it — the most valuable thing in this
wave.** `CompareEntitySourcesTool` looked up `entity.get(finding.field())`
using the *namespace* name a `ConsistencyFinding` carries (e.g.
`PERSON_NAME`), but the scrubbed tree `ContextResponse.entity()` is keyed by
each model's *serialised field name* (e.g. `customerName`). Two sources can
and do use different field names for the same namespace, so this lookup
matched nothing for any realistic model: `identity` was silently empty every
time, present in the tree but never found. It failed closed — no leak — but
the contract's second item was effectively unimplemented, and the tool
shipped that way past its own test suite. All three tests that covered
`identity` shared the implementation's assumption rather than checking it:
one declared a record component literally named `PERSON_NAME` so the
namespace name and the field name happened to coincide, and the other two
hand-built response fixtures whose keys were already chosen to match the
lookup. A test written alongside the implementation it tests tends to encode
that implementation's assumptions rather than challenge them; a test that has
never been run against a deliberately broken version of the code it covers
has not been shown capable of failing, and proves nothing about the code
being right. The fix adds `ContextResponse.fieldsFor(...)`, backed by the new
`fieldsByNamespace` component, to translate a finding's namespace to the real
per-source field name(s) the tree holds it under, tried alongside the direct
lookup for every finding. It was accepted only after the regression test was
run against the pre-fix code and reproduced the reviewer's exact failure,
then passed after the fix.

**Binary compatibility, checked twice with `javap` against the published
jar, not assumed.** `data-prism-orchestration` is on Maven Central at 0.1.0
and 0.1.1, immutably. Both `ContextRequest` and `ContextResponse` gained a new
record component for this task (`fieldsByNamespace` on the response; an
equivalent addition on the request), which changes each record's canonical
constructor — normally a linkage break for anything compiled against the
published jar. Both records got an explicit legacy constructor reproducing
the old parameter list and descriptor, confirmed twice by decompiling the
published 0.1.1 jar's class files with `javap` and diffing the method
descriptor against the new build's, not by reasoning about it. Recorded
because adding a component to an already-published record is a trap worth
checking every single time, not something to eyeball.

**Agreement is computed pre-scrub, and has to be.** After pseudonymisation,
two genuinely different values that collapse onto the same pseudonym are
indistinguishable from two identical ones — visible today in the GitHub demo,
where a profile name and a differing commit-author name for the same subject
both render as one pseudonym and only the finding itself reveals they
differed. So `compare_entity_sources`'s agreement findings come from
`NamespaceCorrelationService`, on the trusted side, over raw values, before
scrubbing — not reconstructed from the already-scrubbed `ContextResponse` in
the MCP layer, which would be too late to see the difference. Getting this
backwards would have produced a tool that silently reports everything
consistent, a failure mode a naive equality-on-pseudonyms test would not have
caught either.

**The spec was superseded; the amendment is recorded, not the sketch.**
`docs/pack.md:1436-1471` (§42) predates the privacy engine: its example
response shows a raw internal id, a raw personal name and raw source-system
names, all three of which would breach CLAUDE.md rule 5 if shipped literally.
Its *shape* — entity type, subject, an identity block, per-field findings — is
authoritative; its *values* are not. The amendment is recorded in
`docs/design-review.md`, section E, which is the file that amends `pack.md`
where the two disagree.

**Owner decisions recorded during this wave:** the tool's argument and
response field is `subjectId`, not the spec's `idInternal` — consistency with
the shipped `get_entity_context` tool an MCP client sees alongside it, which
already uses `subjectId` across its surface; `identity` carries pseudonymised
values copied verbatim out of the scrubbed tree, never raw and never
re-derived; and findings report agreement and disagreement and
missing-in-some-sources, each distinguishable by an explicit discriminator
rather than by absence, because silence would leave a caller unable to tell
"compared and consistent" from "never compared".

**Three namespaces differ in casing, each correct in its own system, and a
naive consistency sweep across them would break two already-published
artifacts.** MCP registry: `io.github.AindriuB/data-prism` (GitHub login
casing, what the registry actually grants). Maven Central:
`io.github.aindriub` (published immutably at 0.1.0 and 0.1.1, must stay
lowercase). GHCR: `ghcr.io/aindriub/data-prism-server` (Docker repository
paths must be lowercase). Task 44 changed only the first, in exactly the
three files that carry it (`server.json`, `README.md`'s marker,
`docker/distribution/Dockerfile`'s label), and added a step to
`publish-mcp.yml`'s validate job that extracts the name from all three and
fails with `::error::` if they are not byte-identical — proven non-vacuous
independently by the reviewer, not just by the implementer's own paste.

**Task 44 does not clear the 403 on its own.** The MCP server-name label is
baked into the GHCR image at build time, so the corrected namespace only
takes effect once `publish-image.yml` rebuilds and re-pushes the image under
the next version (task 45's 0.2.0). `mcp-publisher publish` failed 403
against `v0.1.1` during this wave and nothing was published; that remains
true after this merge.

**Cost:** the sequential-merge race predicted going in happened exactly as
expected — task 44's PR (#68) was already open and merged first, carrying the
previously-unpushed `plan: tasks 42-45` commit `main` was already sitting on
(same handling as tasks 40 and 41's equivalent situation); task 42's branch
had to be pushed fresh, opened as PR #69, then updated (`gh pr update-branch`)
once main moved out from under it and its `build` check re-run green before
it could merge. Tasks 43 and 45 stay open: 43 is now unblocked (42 merged)
but still needs an explicit criterion proving `identity`'s field-name keying
against the *real* `ScrubbingEngine`, not the stub task 42's own tests used;
45 needs a `CHANGELOG.md` line about `ContextResponse.equals`/`hashCode`/
`toString` now including the new component, and inherits the documented
(not yet exercised) gap that any `ContextOrchestrator` other than
`DefaultContextOrchestrator` gets an empty `identity` via the legacy
5-arg constructor. See `docs/plan/PLAN.md`'s task 42-45 section for the full
carry-forward detail on both.

## 2026-09-17 — Task 41: cut version 0.1.1, and a planning false premise caught at review

Moved the whole tree from `0.1.0` to `0.1.1` — 18 module poms plus root, both
`server.json` version fields, the two MCP `serverInfo` handshake literals in
`DataPrismMcpServer.java`, all four Dockerfiles, and `publish-image.yml`'s
comment — plus a `CHANGELOG.md` entry, with `git diff` otherwise touching no
dependency version, no production code path, and no file added or removed.
`mvn -B clean verify` stayed at 466 tests. Merged through a protected, green
pull request (#66), which also carried the previously-unpushed `plan: task
41` commit `main` was already sitting on — same handling as task 40's
equivalent situation, confirmed by checking that the merge base of the task
branch and `origin/main` predated that commit, so the PR's diff picked it up
along with the task's own three commits.

**Why 0.1.1, for the record — the task file states a different, false reason
and must not be the surviving account.** The `v0.1.0` tag predates the
multi-architecture publish pipeline: `git show
v0.1.0:.github/workflows/publish-image.yml` contains no matrix and no arm64
leg, so dispatching `workflow_dispatch` against that tag would silently
re-run the old single-architecture pipeline instead of task 40's work —
`workflow_dispatch` reads the workflow file from the ref it targets, not from
`main`. Separately, `main` has diverged from the tag: `git diff --stat
v0.1.0..main` touches CI workflows, documentation, and
`data-prism-quickstart-issuer/pom.xml` only — no production source, no other
module pom. Force-moving a tag a published GitHub Release already points at
is possible but dishonest; cutting a patch version is cheaper and more
honest than either.

**The false premise, and how it got caught.** The brief this task was
planned from claimed `main` carried a nimbus-jose-jwt security patch (PR #55)
reaching the shipped server image, so an image tagged `:0.1.0` built from
current `main` would carry a different JWT library than the artifacts
already on Maven Central — the stated justification for cutting a new
version at all. That is not true. PR #55's only dependency change is a
version pin in `data-prism-quickstart-issuer/pom.xml`; `data-prism-server`
receives nimbus transitively through `spring-security-oauth2-jose`,
untouched by that PR, and neither the server nor the distribution image
builds the issuer module. The task file
(`docs/plan/tasks/41-cut-version-0-1-1.md`, now deleted) asserted the false
claim at its old lines 33-40 and required the `CHANGELOG.md` entry to repeat
it at lines 97-102 — which would have told users 0.1.1 fixed a JWT
vulnerability it did not fix. The planner and implementer both wrote the
claim in faithfully; the reviewer caught it on the first round and required
a correction commit (`9ad2dfa`, "correct rationale — 0.1.1 is release
plumbing, not a JWT security fix") before approving. The branch was right to
contradict its own task file.

**Process lesson, worth keeping.** This is the second time in this release
cycle a reviewer has caught a false premise supplied by the coordinator
planning the task, rather than a defect introduced by an implementer
executing it. Both times, the planner and implementer downstream did exactly
what they were briefed to do and had no mechanism to challenge a factual
claim handed to them as context — the verification chain caught it, but only
at review, after the false claim had already been written into two files.
That is a gap in the loop, not a one-off implementer error, and it is worth
naming plainly rather than filing under "reviewer did its job."

**Cost:** the real cost was not the version bump — that part was mechanical
and the acceptance criteria (`rg` sweep for surviving `0.1.0` hits, jar/image
builds, MCP handshake check) worked as designed. The cost was diagnosing that
the premise motivating the whole task was wrong, which took a dependency-tree
read (`mvn dependency:tree` on `data-prism-server`, confirming nimbus arrives
via `spring-security-oauth2-jose` with no path through the issuer pin) plus
confirming which images actually build the issuer module (neither). Do not
trust a coordinator-supplied claim about what a dependency bump affects
without tracing the actual dependency path for the artifact in question —
"the pom changed" is not evidence that a specific shipped jar changed.

## 2026-09-16 — Task 40: publish the server image for linux/amd64 and linux/arm64

`ghcr.io/aindriub/data-prism-server` was published amd64-only, and the
(unpublished) MCP registry entry points strangers at exactly that coordinate —
a large share of them on Apple Silicon. `docker save`/`load` cannot carry a
multi-arch manifest list, so this was a rebuild of `publish-image.yml`'s
shape, not a `--platform` flag: the old build → verify → save → artifact →
load → push pipeline is replaced with a native per-architecture matrix
(`ubuntu-latest` for amd64, `ubuntu-24.04-arm` for arm64), each leg building
its own image, verifying the no-config refusal on its own native hardware,
and pushing by digest, with a final job needing both legs and assembling the
two digests into the `:0.1.0` and `:latest` manifest lists. No QEMU, no
partial-publish path: if the arm64 runner is unavailable, the job fails
outright rather than shipping an amd64-only manifest. Merged through a
protected, green pull request (#64).

GitHub Actions run 35162338759 (`workflow_dispatch` on the branch) proved two
things local execution and code reading could not: `ubuntu-24.04-arm`
resolves to a real GitHub arm64 runner and the no-config refusal verification
succeeded there natively, not under emulation; and every registry-touching
step in both matrix legs, plus the publish job, correctly skipped on a
non-tag ref, with the publish job skipped entirely.

**Cost:** the default `docker buildx` driver rejects `push-by-digest=true`
("not implemented for docker driver") — the whole digest-push design would
have failed on first real use. Fixed with a
`docker buildx create --driver docker-container --use --bootstrap` step ahead
of the build. The implementer was explicit that inspection alone would not
have found this; it took actually running the build. The reviewer also found
a gap worth keeping in mind rather than fixing: the image that gets pushed is
a *second* `buildx build`, not the `--load`ed artifact that was verified —
they are identical only because the builder's cache is warm, seconds apart in
the same job. The guarantee this workflow gives is "verified a build that
should be byte-identical to the one pushed", not "the verified bytes were
pushed"; low risk, but a real distinction. Two paths remain undemonstrated by
any actual run rather than merely reasoned about: the tag-push no-write case
(the only `v*`-tag run, 35153755398, was against the *old* workflow), and the
digest-push/`jq`/`--metadata-file` steps themselves (skipped in the only run
that exercised this workflow, since it ran on a branch) — the first real
release-tag dispatch will be their first execution. The image is re-pushed as
`:0.1.0` rather than cut as a new version, because nothing external
references it yet: the MCP registry entry is unpublished and the only puller
so far is this project's own demo; that option expires once the registry
entry goes live. The reviewer separately confirmed, rather than assumed, that
`publish-mcp.yml`'s `docker manifest inspect` pullability guard is satisfied
by a manifest list, so no successor task is needed there. `server.json`
needed no platform change; only `README.md` did.

## 2026-09-16 — Task 38: the MCP registry entry, and the release plan is complete

Closes the release plan opened 2026-09-16 (tasks 33-39). Merged through a
protected, green pull request (#61). Adds `server.json`, the `mcp-name`
marker and a registry-arrival section in `README.md`, and
`.github/workflows/publish-mcp.yml`, all gated on `workflow_dispatch` plus a
`refs/tags/v*` ref like the other two publish workflows.

The reviewer found a defect that would have shipped a broken public listing:
`server.json` originally declared `DATAPRISM_SECURITY_POLICY_ROLES_INVESTIGATOR`
as a required environment variable, and it does not bind. On Spring Boot
3.5.16, map keys under a hyphenated prefix (`dataprism.security-policy.roles`)
are discovered by enumerating `SystemEnvironmentPropertySource`, which splits
`SECURITY_POLICY` into `security.policy` rather than `security-policy`, so a
consumer setting exactly the variable the entry marked required would get
`MISSING_ROLE_POLICY` and no startup. The implementer confirmed the correct
spelling empirically, against Spring Boot's real `Binder` and
`SystemEnvironmentPropertySource`, testing three candidate spellings rather
than reasoning about it:
`DATAPRISM_SECURITYPOLICY_ROLES_INVESTIGATOR` is the one that binds. The
entry also contradicted itself, claiming elsewhere that the role map could
not come from an environment variable at all; that was resolved rather than
left standing. `DataPrismProperties` has exactly two `Map` fields — `sources`
(unhyphenated prefix, POJO value type, unaffected) and `roles` (under the
hyphenated `security-policy`, affected); `caller-claims` and `hmac-key` are
fixed POJOs so their underscore-split spellings bind normally, and
`purposes` is a `List` and binds directly. Only `roles` needed the fix.

`publish-mcp.yml` also gained an ordering guard: two independently-dispatched
workflows have no ordering guarantee in GitHub Actions, so it runs `docker
manifest inspect` against the image tag first and refuses to publish the
registry entry unless the image is already pullable, and it asserts
`server.json`'s `.version` equals `.packages[0].version` before either guard,
so a drifted file fails loudly instead of publishing an entry pointing at a
stale image.

Nothing is published. Confirmed at record time:
`curl -s "https://registry.modelcontextprotocol.io/v0/servers?search=io.github.aindriub/data-prism"`
returns `{"servers":[],"metadata":{"count":0}}`. All three publish workflows
(central, image, mcp) remain gated on `workflow_dispatch` and a
`refs/tags/v*` ref, so nothing fires until the owner tags a release and
dispatches each workflow by hand, in order — see `PLAN.md`'s "Release the
tagged version" section for that sequence.

**Cost:** the env-var spelling defect was reasoned-then-verified, not
reasoned-and-trusted — the implementer built a throwaway harness against
Spring Boot's real `Binder`/`SystemEnvironmentPropertySource` rather than
inferring the relaxed-binding rule from documentation, because a wrong guess
here ships silently as a broken public onboarding instruction, not a test
failure. Tester validated `server.json` two independent ways (Python
`jsonschema` against the fetched schema, and the real `mcp-publisher
validate` CLI) rather than trusting one validator's interpretation of the
schema.

## 2026-09-16 — Tasks 36, 37, 39: Maven Central publishing, distributable server image, default-mode transport fail-open closed

Closes the release wave opened the same day. All three merged through
protected, green pull requests (#57, #58, #59).

Task 39 closed the fail-open reviewer 35 found: every MCP transport bean in
`DataPrismAutoConfiguration` is `@ConditionalOnWebApplication(SERVLET)`, so a
non-web application at the *default* `dataprism.transport.mode=HTTP` started
cleanly with no MCP transport and no refusal — reachable without any
misconfiguration, unlike the stdio case task 35 closed. The fix is a
`BeanFactoryPostProcessor`, `dataPrismMcpTransportPreflight`, refusing with a
new code, `MCP_TRANSPORT_UNAVAILABLE`, before any DataPrism singleton is
constructed. The fail-open was real and widespread: closing it broke tests in
three separate modules, because `ServerPackagingIT` and
`ConfiguredJsonSourcesPackagingIT` in `data-prism-server`, and
`StarterStartupFailureTest` in `data-prism-example`, were each relying on a
non-web context starting cleanly at default `mode=HTTP` — the very bug being
closed. The test suite was depending on the defect, which is itself the
strongest evidence it was worth fixing. All three were migrated to servlet
contexts with every original assertion preserved; a reviewer confirmed no
test was deleted or weakened to hold the count stable — `@Test` counts
unchanged, zero `@Disabled`, method-name lists byte-identical. Task 39's own
file contained a contradiction — an "Out of scope: data-prism-example" line
alongside an acceptance bullet requiring the example suite to pass — and two
different implementers hit it and both correctly stopped rather than picking
a side; a task's scope and its acceptance criteria have to agree, and
refusing to guess on a contradiction is the behaviour this plan wants. Four
codes now name "this deployment has no usable MCP transport":
`STDIO_DEVELOPMENT_ONLY`, `STDIO_TRANSPORT_UNSUPPORTED`,
`STANDALONE_HTTP_ONLY`, and the new `MCP_TRANSPORT_UNAVAILABLE`; a consumer
keying on the shared condition must match all four. `docs/configuration.md`
now documents the new code and its table row.

Task 36 wired Maven Central publishing for the 12 deployable library modules
plus the root aggregator; `data-prism-server`, `data-prism-example`,
`data-prism-architecture`, and the three quickstart modules are explicitly
non-deployable. Verified both with `mvn clean verify` (no signing key) and
`mvn -Prelease clean verify` (a throwaway key), and all 49 `.asc` signature
files were individually `gpg`-verified rather than trusted by inspection; the
reviewer checked the profile-merge mechanism with `mvn help:effective-pom`
for the same reason. `data-prism-spring-boot-starter` ships a deliberately
empty javadoc jar — a package containing only `package-info.java` cannot be
documented by the javadoc tool at all, confirmed against the CLI with a
minimal repro, and a marker type was rejected as dishonest. The maintainer's
personal email is deliberately absent from `SECURITY.md` (GitHub private
vulnerability reporting is the sole channel) and from the POM `<developers>`
block, on the grounds that a published POM is immutable. Coordinator-granted
extension: task 36 gained
`data-prism-spring-boot-starter/src/main/java/.../package-info.java`, checked
against no concurrent task owning that path.

Task 37 built the fixture-free distributable server image, gated behind a
`central` GitHub Environment and tag-plus-dispatch-plus-version-match
publish workflows — nothing has actually been published; `publish-central.yml`
is workflow_dispatch-only behind the `central` environment with
`<autoPublish>false</autoPublish>` as a third layer, and `publish-image.yml`
requires workflow_dispatch AND a `refs/tags/v*` ref AND a reactor-version
match. Its own acceptance criteria named unreachable refusal codes — items 4
and 5 expected `MISSING_JWT_ISSUER`/`MISSING_SOURCE_ADAPTER`, but
`dataPrismIdentityResolverPreflight` always fires first, so the observed code
is `MISSING_IDENTITY_RESOLVER`. This was a deliberate amendment, not a
silent retirement: the workflow's refusal check now greps generically for
`MISSING_[A-Z_]+`, which is also more robust against task 39 adding a fourth
`BeanFactoryPostProcessor` to the same ordering. Three rounds of reviewer
CHANGES, each fixing a named instance and finding a sibling of the same
class still standing: (a) the publish job was gated on `workflow_dispatch`
but not a tag ref, so a dispatch on untagged `main` could still push; (b) the
ARG/ENV rework replaced a JSON-array `ENTRYPOINT` with `sh -c`, silently
swallowing every operator-appended `docker run` argument; (c)
`${{ inputs.version }}` shell-injection hardening landed on the publish job
but not on build-and-verify. The final fix swept for the whole class instead
of the named line — a programmatic YAML parse confirming zero `${{ }}`
expressions inside any `run:` block.

A single serialized full-reactor `mvn -B clean verify` from the main checkout
after all three merges gives 466 tests, 0 failures, 0 errors, 19 modules —
+4 over 462, all from task 39's new tests.

**Cost:** Both merge-branch races (PR checks reporting `pass` while GitHub's
merge API still returned "Required status check build is expected", and two
sequential merges each leaving the next PR's branch behind `main` and
blocked as not-up-to-date) needed `PUT .../pulls/<n>/update-branch` and a
wait for the re-triggered `build` run before the merge would go through —
routine with three PRs landing in sequence against one protected branch, not
a defect in any of the three.

**Owner actions still outstanding:** the `central` GitHub Environment exists
but has no required reviewers ticked, so it currently gates nothing;
`CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD` should move from
repository secrets to that environment's scope now that
`publish-central.yml`'s stage job no longer references them; task 38 will
need an owner-only MCP registry namespace claim before it can publish.

## 2026-09-16 — Tasks 33, 34, 35: 0.1.0 cut, release hygiene files, stdio refuses instead of serving nothing

Three tasks opening the release wave. All merged through protected, green pull
requests (#41, #42, #43).

Task 33 cut the 0.1.0 release version across the reactor and added a
tag-triggered GitHub Release workflow. `release.yml` has no deploy phase and no
`packages:`/`id-token:` permission, so it cannot publish to Maven Central or a
container registry on its own — that is left to tasks 36 and 37, which now
have a version to publish.

Task 34 added `CHANGELOG.md`, `SECURITY.md`, `dependabot.yml`, and a
`CONTRIBUTING.md` security section. The branch shipped with a personal email
as `SECURITY.md`'s fallback contact; the reviewer caught it and it was fixed
by an amendment on the same branch before merge — GitHub private vulnerability
reporting is now the sole channel, `rg -i 'aindriubannister|@gmail'` over the
worktree returned no matches, and the supported-version table row was
generalised to "Latest released version".

Task 35 made `dataprism.transport.mode=stdio` refuse startup unconditionally
in the shared Spring auto-configuration (`DataPrismAutoConfiguration`'s new
`dataPrismStdioTransportRefused` bean, `STDIO_TRANSPORT_UNSUPPORTED`), closing
the fail-open where a starter or standalone-server context configured for
stdio previously started with no MCP transport at all. `docs/configuration.md`
line 55 (the `dataprism.transport` vocabulary row) said `stdio` was
fixture-development-reachable; that is now false and has been corrected here.
`dataprism.transport.fixture-development=true` is consequently unreachable
everywhere in the Spring surface — `ConfiguredJsonSourcesInitializer`'s
plaintext-loopback relaxation and `DataPrismContractValidator`'s zero-source
early return are dead-in-effect paths (still executed, still pinned by tests,
judged safe to leave as debt rather than removed here), and
`data-prism-connectors-rest`'s own `fixture-development` read is dead code.
Reviewer 35 also found a second fail-open outside this task's scope: the MCP
HTTP transport beans are `@ConditionalOnWebApplication`, so a non-web starter
application at the *default* `mode=HTTP` starts with no transport and no
refusal either. That is now task 39, opened depending on 35.

A single serialized full-reactor `mvn -B clean verify` from the main checkout
after all three merges — not any individual tester's run — gives 462 tests, 0
failures, 0 errors, 19 modules.

**Cost:** One tester (34) reported "920 passed"; that number is a double-count
of surefire and failsafe report files on a branch that touches no test code at
all, and was not used. Testers 33 and 35, run on the same reactor, correctly
reported 460 and 462. The serialized re-run here (`find ... surefire-reports
... failsafe-reports`, summed per-module aggregate lines only, not per-class
lines, to avoid the same double-count) confirms 462. Do not sum per-class
`Tests run:` lines from a `mvn` log across both report directories — count the
one aggregate line per module instead, or the failsafe integration-test
modules get counted twice.

The planner's task-file commit for this wave (`1c22663`, adding task files
33-38) was made directly to a local `main` that had already diverged from
`origin/main`, rather than through a PR — inconsistent with this repository's
protected-branch flow (`docs/workflow.md`, Phase 4). It reached `origin/main`
only because one of the three worktrees (task 35's) happened to be branched
from that local `main` after the stray commit landed, carrying it along
through PR #43's merge. It worked here by coincidence, not by design; a wave
whose worktrees are all branched before a stray local commit would leave that
commit permanently unreachable from `origin/main`. Planner commits need the
same PR discipline as every other change to this repository.

## 2026-09-15 — Simplification wave 2 (tasks 31-32): descriptor resolver wired, data-prism-audit merged into core

Two tasks closing the remaining dead-code and module-count debt from the
simplification plan. Both merged locally, no conflicts.

Task 31 gave `core/descriptor` — `DescriptorFieldMetadataResolver`,
`ModelDescriptors`, `ModelDescriptor`, roughly 320 lines with tests but no
production caller anywhere in the reactor — its first real caller: an
optional `dataprism.privacy.descriptor-file` property that, when set, loads
and validates a model-descriptor YAML file and wraps the default
`FieldMetadataResolver` in `DescriptorFieldMetadataResolver`. Four distinct
refusal codes cover a missing file, an unreadable file, a file with no
`models` section, and a descriptor that tries to set
`undeclaredFields: NON_SENSITIVE`; every path fails closed at startup with no
fallback to the undecorated resolver, and no descriptor file content reaches
a refusal message. No new `@Bean` was added, so `PrivacyExtensionPoints`
needed no new classification row.

Task 32 deleted the `data-prism-audit` module and moved its four classes —
`AuditEvent`, `AuditSink`, `AuditRecorder`, `Slf4jAuditSink` — plus its test
into `data-prism-core`, package unchanged, so no consumer's imports changed.
All renames landed at 100% similarity; no `.java` body changed and the
per-writer audit hash chain is untouched. Five poms and the architecture
module table were updated to match. The reactor is now 19 modules with main
code (from 20), and `docs/architecture.md`'s planned `reidentification` row
no longer names a module that does not exist.

A single serialized full-reactor `mvn clean verify` from the main checkout
after both merges — not the two testers' individual runs — gives 460 tests, 0
failures, 0 errors.

**Cost:** Concurrent Maven builds against this repository's shared local
repository (`~/.m2`) are unreliable and have now produced spurious failures
five times across two simplification waves, most recently eight fake
security-boundary failures on the task 32 branch that passed 11 of 11 when
re-run in isolation. Every occurrence has cleared on a serialized re-run with
no code change. Testers and `/record` must run `mvn verify` one at a time
against this repo, never concurrently — the flakiness is an artefact of
shared-repository contention, not of the code under test, and re-diagnosing
it from scratch a third time would waste a wave's worth of time for nothing.
A stale `data-prism-audit/target/` directory also survived task 32's merge
(the module's tracked files were deleted but its untracked, gitignored build
output was not); removed by hand before the post-merge build so it could not
be mistaken for a live module.

Two non-blocking follow-ups from task 31's review, not fixed there because
each needs its own acceptance criteria:
- An application that registers its own `FieldMetadataResolver` bean
  silently suppresses the descriptor wiring — `@ConditionalOnMissingBean`
  means a set `descriptor-file` is then never read or validated, and startup
  succeeds without warning. The reviewer scoped the fix to a successor task
  because closing it means guarding a `REPLACEABLE` extension point, which is
  a different-shaped change than this task's.
- A descriptor file containing only a YAML document marker (`---` with no
  `models:` key) produces a `NullPointerException` from `ModelDescriptors`
  rather than a named refusal code. Startup still fails closed, so the
  fail-closed invariant holds, but `docs/conventions.md` expects a stable
  code for every refusal, not an incidental `NullPointerException`.

`docs/pack.md` still lists `data-prism-audit` in its directory tree
(around line 376). Left unchanged: that file is the frozen original
specification, kept verbatim by its own header note, and `docs/design-review.md`
is where amendments to it belong — not an edit to the historical document
itself.

## 2026-09-15 — Simplification wave 1 (tasks 26-30): shared JWT decoder, orchestrator cleanup, StrictYaml helper, OwnerScope record, properties formatting

Five small tasks closing duplication and readability debt, independent of
the slice plan above. All merged locally, no conflicts. A single serialized
full-reactor `mvn clean verify` from the main checkout afterwards — deliberately
not trusting the five testers' parallel results, three of which had hit
transient classpath failures from five concurrent Maven builds sharing one
local repository and only cleared on an isolated re-run — gives 440 tests, 0
failures, 0 errors.

Task 26 found `ServerSecurityConfiguration` and the example's `SecurityConfig`
carrying byte-for-byte equivalent OIDC discovery, SSRF guards, discovery-
document parsing and audience validation, plus two `JwtCallerContextExtractor`
classes differing only in package and comments — the exact defect shape
already paid for twice, where one copy gets hardened and the other silently
does not. Both collapsed into `JwtDecoderSupport` and
`JwtCallerContextExtractor` in `data-prism-spring-boot-autoconfigure`; the
server and example filter chains now only carry their own
`SecurityFilterChain` bean and delegate construction to the shared class. The
Spring Security architecture exemption was widened by fully-qualified class
name, not by package, so `data-prism-security` still cannot depend on Spring
Security. The task's own Owns list, derived from same-package usage, missed
`data-prism-server`'s `ServerSecurityBoundaryTest`, a cross-package caller
that needed a one-line import fixed after the class moved. The implementer
correctly stopped at the boundary rather than editing outside it; the scribe
authorized the one-file amendment before merge. **Lesson for future task
files:** an Owns list built from "what else lives in this package" is not
enough — it has to be built from "who else imports this class", i.e. derived
from callers (`rg -l` on the moved symbol across the whole reactor), not from
package co-location. This is the second time a task's scoped file list has
needed a late, reviewer-or-scribe-authorized addition for a cross-package
caller; the pattern is worth catching at `/plan` time, not at merge time.

Task 27 reduced `DefaultContextOrchestrator` to the two constructors actually
called anywhere in the reactor and lifted the fetch/scrub/merge loop out of
`buildContext` into its own private method, leaving the fail-closed try block
that wraps it intact and independently re-verified.

Task 28 extracted the duplicated `enumValue` parsing logic — previously
inlined separately in `PrivacyProfiles` and `ModelDescriptors` — into one
`StrictYaml` helper in `data-prism-core`. Reviewer follow-up, not a blocker:
`StrictYamlTest` carries a comment claiming the old logic was never invoked
with `null`; the reviewer showed four call sites in `PrivacyProfiles` and
`ModelDescriptors` do call it with `null`. The helper's `null` handling is
correct — only the comment's justification is wrong. Recorded in `PLAN.md`'s
small open items rather than fixed here, since fixing a comment is not this
task's acceptance criteria and no task currently owns the test file for a
change beyond what shipped.

Task 29 bundled `JsonTreeScrubbingEngine`'s parent/siblings/owner-object
parameters, previously threaded separately through several method
signatures, into a private `OwnerScope` record. The nested-descent fail-open
hole this shape could have reopened was checked and stays closed. Reviewer
follow-up, not a blocker: the new cross-field test added to exercise
`OwnerScope` duplicates an existing assertion on the same fixture and does
not actually exercise a nested parent scope, so the record's behaviour under
real nesting is still unproven by a dedicated test. Also recorded in
`PLAN.md`'s small open items.

Task 30 reformatted `DataPrismProperties` to one statement per line — no
behaviour change, proven by a token-stream diff rather than by eyeballing a
644-line reformat.

**Cost:** the real cost here was process, not code. Three of the five
testers' `mvn verify` runs failed on transient classpath errors that had
nothing to do with the change under test — five worktrees running Maven
concurrently against one shared local repository corrupted each other's
resolution, and each failure cleared on an isolated re-run. Parallel tester
results are not proof a merge is sound; the one serialized full-reactor
build after all five merges is the number that counts (440 tests, 0
failures, 0 errors), not any individual tester's report. Task 26's task file
was also amended in the main checkout instead of its worktree — the
implementer had no choice, since the file was untracked on main when the
worktree was cut, so it never existed inside the worktree to edit. That
amendment survived the merge intact and unduplicated only because the
worktree's branch never touched the file at all; a future task whose file
*is* tracked before the worktree is cut would not have this escape hatch, and
an edit made outside the worktree in that case would be a real ownership
breach.

## 2026-09-15 — Tasks 19 and 20: tested agent connection guides, and a configuration-driven JSON REST mode

The last wave of this plan. Both merged through protected, green pull
requests (#37, #38); post-merge full-reactor re-run: 441 tests, 0 failures, 0
errors.

Task 19 published `docs/agents/**` (stdio and remote HTTP) and
`examples/agent-config/**`, verified against a real client — Claude Code CLI
2.1.271 — rather than reasoned about: `claude mcp get` reports Connected, raw
JSON-RPC `initialize`/`tools/list`/`tools/call` were captured live, and task
18's quickstart was brought up with a live `docker compose up --build`. GUI
clients are excluded with a stated reason rather than listed unverified.
`examples/agent-config/remote-http/smoke-test.sh` now issues a real
`get_entity_context` call and asserts the three raw fixture values are absent
and a `SUBJ-` pseudonym plus `[REDACTED]` are present, mirroring
`QuickstartSmokeIT` — previously it only grepped for "Connected", which would
have passed against an endpoint returning unpseudonymised data. It also exits
77 rather than 0 when `claude` is absent, so a CI runner no longer records a
pass having tested nothing. A captured `tools/list` transcript was found
damaged in transcription (missing `"type":"string"` on `entityType`), caught
because the same schema is captured twice from one `Map.of` literal and the
two disagreed; fixed by re-capturing from a live run rather than hand-patching
the key back in, and the reviewer confirmed the re-capture was genuine — the
fresh and committed blobs differ only in key ordering, which is `Map.of`'s
per-JVM randomised iteration order, something a hand-edit would not have
reproduced. The README's module count was wrong twice before being right:
stale at 15 against an actual 16, then "corrected" to 18 by applying a delta
to that wrong base. It is now 19 submodules / 20 reactor projects, counted
directly from `pom.xml:24-42`, with the README stating which number counts
what.

Task 20 wired `data-prism-connectors-rest`, dormant and unconsumed since it
was built, into a `json-sources:` YAML vocabulary with a per-source
`FieldMetadataResolver` feeding the real `JsonTreeScrubbingEngine`, loaded
opt-in via `-Dloader.path`. Its test count went from 13 to 49. Every emitted
field must be explicitly classified; unknown fields refuse at
`JsonTreeScrubbingEngine.java:138`, nested objects and arrays hit
`UNCLASSIFIED_STRUCTURE` because the resolver returns `descendable=false`,
and `PrivacyProfile.UnclassifiedBehaviour` has no pass-through value, so no
profile can loosen it. The JSON-path grammar is
`^[A-Za-z_][A-Za-z0-9_]*$` feeding a flat `ObjectNode.get(name)` — it cannot
spell `.`, `[`, `/`, `:` or `..`, and no expression evaluator exists to inject
into. An end-to-end parity test proves a configured source and a Java-first
adapter with deliberately different field shapes produce the same synthetic
value with raw values absent.

**Cost:** three review rounds on task 20, each finding and closing a real
defect at the edges of an otherwise sound design. Plaintext `http://` was
accepted for a configured `base-url`, bypassing the HTTPS gate every
Java-first source must clear — a plaintext relaxation reintroduced by a
different door two tasks after task 21 removed the last one; now refused,
with a loopback-only fixture exception strictly narrower than the original's
and closable entirely by a `tls:` block. The gate was reimplemented rather
than called, and had drifted: it initially missed `trustedUri`'s refusal of
`userInfo`, query and fragment; now at parity, verified clause by clause. A
configured source declares its transport twice — its own config, plus the
`dataprism.sources.<name>` entry `DataPrismContractValidator` requires — and
the two could disagree with the validated one silently losing; the
implementer's own fixture encoded the contradiction. Disagreement is now
fatal and names both values. Two more `isInstanceOf(RuntimeException.class)`
assertions — the thirteenth and fourteenth found in this repository, in the
module `docs/conventions.md` already names for this defect class — now assert
the real `RestClientException` with an `HttpMessageNotReadableException`
cause, verified not to match an unstarted server.

One finding was graded down on evidence rather than taken on authority. A
review found the transport-disagreement check read properties via raw
`Environment.getProperty`, which does not resolve Spring Boot's
hyphen-dropped environment-variable form, and graded it a live hole in the
deployed path. The implementer tested the claim instead of accepting it: it
reverted the fix, ran the real packaged server with the environment variable
set, and the disagreement was still caught, because
`SpringApplication.prepareEnvironment` calls
`ConfigurationPropertySources.attach` before any `ApplicationContextInitializer`
runs. The reviewer then verified this independently against spring-boot
3.5.16, including in bytecode, and retracted its own grading — the defect was
latent fragility, correct by an accident of ordering the code did not
control, not an open door. The fix stands; the discriminating test had to be
built as a bare `AnnotationConfigApplicationContext`, because both a real boot
and `ApplicationContextRunner` call `attach()` first. Every defect found in
this wave, across both tasks, had the same root shape: a reviewer comparing
an implementation against the thing it claimed parity with, rather than
checking whether it worked — the reimplemented HTTPS gate and the earlier
`ServerArchitectureTest` false claim (see task 21-24's entry) both trace to a
stated equivalence nobody had checked, the same root cause as this
repository's cannot-fail-assertion problem: a claim carrying more authority
than its evidence.

A previous entry (task 18's, above) recorded as an open item that
`ServerPackagingIT.DEVELOPMENT_KEY_MARKERS` should be extended with the
quickstart's HMAC literal. That recommendation was wrong and is retracted:
`data-prism-server` never compiles or reads the quickstart env file, so no
artefact that scan inspects can emit the literal, and the class's own
javadoc forbids a marker no build artefact emits. Task 20 added the marker,
then correctly removed it once this was established; a tester confirmed by
unzip-scanning the freshly repackaged jar that the literal appears zero
times. Corrected in `PLAN.md` rather than left standing.

## 2026-09-14 — Task 18: a reproducible local quickstart

The first task in this repository to produce a runnable demonstration of the
whole system rather than a library plus tests. `docker compose up --build`
now brings up the standalone server, synthetic fixture APIs and a local JWT
issuer, and a genuine MCP handshake over the resulting `/mcp` endpoint
returns a pseudonymised response — verified for real, not reasoned about:
the full run returned `customerName:"Rowan Okafor (2TV5)"` and
`email:"[REDACTED]"`, with none of the raw fixture values present; absent
and garbage tokens both got 401; the stack was torn down with `docker
compose down --volumes` and confirmed gone.

Three new Maven modules exist because none of the three runnable pieces
could be borrowed: `data-prism-quickstart-fixtures` (synthetic source APIs;
`data-prism-example` had stub adapters but no `spring-boot-maven-plugin`, so
it built a library jar that cannot start), `data-prism-quickstart-issuer` (a
local JWT issuer, promoting to main code the RSA-key-and-JWKS-over-HTTPS
pattern that previously existed only at test scope in
`McpHttpEndToEndTest`), and `data-prism-quickstart-extension` (a reviewed
adapter jar loaded through `-Dloader.path`, since the standalone server
refuses startup without a `DataSourceAdapter` for every configured source).
Plus `compose.yaml`, `docker/**`, `docs/quickstart.md` and `.env.example`.

Two constraints from earlier tasks shaped the whole design: task 21 made the
server refuse `fixture-development=true`, so the quickstart issues real JWTs
against a real issuer with no development bypass; and plaintext `http://` is
refused for both the JWKS location and every source `base-url`, so the
issuer and fixtures serve HTTPS from a certificate generated at first run
into a Docker-managed volume, never the repository.

Adding the three modules to the root `<modules>` made task 23's
`ArchitectureCoverageTest` fail, because `data-prism-architecture`'s pom
must declare every module with main code as a test dependency. The
implementer stopped and reported rather than editing a module it did not
own; the repository owner amended task 18's `Owns` to add
`data-prism-architecture/pom.xml`, deciding to scan the three new modules
rather than exempt them. The rules now police 17 modules and 201 classes, up
from 14 and 186.

**Cost:** two mutation proofs were run, both directions — de-classifying
`CustomerModel.customerName` made the raw fixture name leak into the
response and the smoke test fail, proving the `doesNotContain` assertions
load-bearing; attaching a valid token to the "absent token" request also
failed the test, proving the refusal assertion is not vacuous. The tester
verified jar shapes by unzipping rather than trusting them, and confirmed
`QuickstartSmokeIT`'s 8.5s runtime is consistent with genuinely booting
three JVMs via `ProcessBuilder` against real HTTPS health endpoints.

The one environmental cost worth recording for future tasks: `.env*` paths
cannot be read or written by any agent in this environment, in this session
or the implementer's. `.env.example` had to be created by hand by the
repository owner, and its contents could not be machine-verified by the
implementer, the tester, or the reviewer — it was committed on a line count
alone. Any future task owning a dotfile of that shape should expect the same
and plan for a human step rather than discovering it mid-task. Also left
open: `ServerPackagingIT.DEVELOPMENT_KEY_MARKERS` was not extended with the
quickstart's HMAC literal (harmless today, since nothing packages
`compose.yaml`, but the file's own javadoc obliges the extension), and
`QuickstartSmokeIT` proves the synthetic name's shape but not its stability
across repeated calls for the same subject. Both logged in `PLAN.md` rather
than fixed here, since neither blocks anything and no task alive owns the
files.

## 2026-09-14 — Task 25: wire the shared read budget, make the topology an explicit choice

Closes the fifth and last defect found reviewing the tasks 13-17 work
delivered by OpenAI Codex, outside this kit. `dataprism.hazelcast.*` was
bound and validated but read by nothing: the auto-configuration always
supplied `InMemoryScopeBudget`, so a two-member deployment enforced the read
budget once per process and a configured budget of 100 meant 200.
`HazelcastScopeBudget` already existed, failed closed, and was tested — it
was simply never wired. It is now wired for the embedded topology, and a
per-process budget became a named choice rather than the silent default.

`dataprism.hazelcast.topology` now has no default and is required for a
protected HTTP deployment: absent refuses with `MISSING_CLUSTER_TOPOLOGY`,
unknown with `UNSUPPORTED_HAZELCAST_TOPOLOGY`
(`DataPrismProperties.java:57-58`). `embedded` produces `HazelcastScopeBudget`
over a `PrivacyCluster`; `single-node` produces `InMemoryScopeBudget`, with
the documentation now stating in plain words that the budget is then
enforced per process and multiplied by the number of processes. `embedded`
with `data-prism-hazelcast` absent from the classpath refuses with
`MISSING_SHARED_BUDGET`, so the absent-dependency case cannot silently fall
back to a per-process budget. The `data-prism-hazelcast` dependency of the
autoconfigure module is `optional`, verified by `dependency:tree` on
`data-prism-example` as not transitive through the starter.
`data-prism-example` declares `single-node` explicitly — not a preference but
a consequence: it is a single process, so per-process enforcement is not a
silent inconsistency for it, and adding the optional Hazelcast dependency to
its pom was outside this task's ownership, so `embedded` would refuse there
at startup.

**Cost:** the implementer proved the `MISSING_SHARED_BUDGET` test's
`FilteredClassLoader` is load-bearing rather than decorative — the counter-
example to this repository's dominant defect class of assertions that cannot
fail — by retargeting its filter to a nonexistent package, observing a real
Hazelcast member boot with multicast logging, and confirming the test then
failed on `hasFailed()`. That distinguishes a classloader that genuinely
hides a class from one that only appears to.

The implementer also edited five files outside the task's declared `Owns`:
`PrivacyExtensionPoints.java`, `FixtureDevelopmentRefusalTest.java`,
`PrivacyExtensionPointsTest.java`, `ServerSecurityBoundaryTest.java`, and
`data-prism-example`'s `StarterStartupFailureTest.java` — a rule-2 breach
(stop and report instead), proceeded past and disclosed afterward rather than
avoided. Review accepted each on its merits: `PrivacyExtensionPoints.java`
was unavoidable, since `AutoConfiguredBeanClassificationTest` demands exact
correspondence with it and the task file had named the wrong file for where
the classification rows live; the four test edits are each one line adding
`topology=single-node` to a pre-existing valid-deployment fixture, forced by
making the property required, and two of the four feed assertions that would
themselves have broken without the added line. No test was weakened. The
planning bug (a task file naming the wrong file) was real; the correct
response to hitting it — stop and report — was not the one taken. Both halves
are worth carrying forward, not just the one that resolved cleanly.

Post-merge full-reactor re-run: 400 tests, 0 failures, 0 errors (base 394;
net +6, four new in `SharedReadBudgetTest`, two new in
`DataPrismAutoConfigurationTest`).

## 2026-09-14 — Tasks 21, 22, 23 and 24: close the reviewed defects in tasks 13-17

All five defects found reviewing the tasks 13-17 work (delivered by OpenAI
Codex, outside this kit) are now closed, plus one fail-open found by the
architect during this wave's planning that no reviewer had flagged.

Task 21: `DataPrismProperties.validate()` accepted
`fixture-development=true` with `mode=HTTP`, skipping JWT issuer/audience/JWKS
validation, the HMAC key requirement, audit/metrics sink requirements and
Hazelcast checks, and short-circuiting `DataPrismContractValidator`. Only
`data-prism-server` guarded it; the Spring Boot starter inherited nothing. The
refusal now lives in the shared validator
(`DataPrismProperties.java:40`, `FIXTURE_DEVELOPMENT_STDIO_ONLY`), so every
consumer refuses fixture development outside STDIO. That made the
plaintext-`http://` JWKS relaxation unreachable, and it was removed from both
resource-server paths.

Task 22: `PrivacyPolicyResolver` and `LlmResponseValidator` were
`@ConditionalOnMissingBean`, so an application bean returning `PASS_THROUGH`
disabled scrubbing and leak detection silently. Both are now unconditional; a
competing resolver refuses startup with `FORBIDDEN_PRIVACY_OVERRIDE`
(`DataPrismAutoConfiguration.java:149`), and the orchestrator takes
`List<LlmResponseValidator>` so an application validator is additive, never a
replacement. New `PrivacyExtensionPoints` classifies all 23 `@Bean` methods,
swept by a test that fails on any unclassified one.

Task 23: the architecture rules had stopped enforcing anything outside
`data-prism-example`, because that module's pom no longer pulled in the
modules the rules were meant to police. A new `data-prism-architecture` module
now imports every module's `target/classes` directly — necessary because
`data-prism-server`'s Spring Boot repackage hides its classes under
`BOOT-INF/classes/` from any ordinary classpath scan. At merge time this was
14 modules, 182 classes, verified identical across all three attempts — a
count that moves as later tasks add classes, not a fixed total.
`ArchitectureCoverageTest` is the guard-of-the-guard: it fails if a module
stops contributing classes, which is what makes a green run mean something.

Task 24: `ServerPackagingIT`'s secret scan could not fail — it read only
`BOOT-INF/classes/` for literals that existed solely in test code. It now
scans every jar entry plus nested `BOOT-INF/lib/*.jar`, and a
`JarInputStream`/`ZipInputStream` defect found in review had been hiding 62 of
65 nested manifests. Both branches of the scan have positive controls proven
by mutation.

Two decisions the repository owner took on 2026-09-14, both landed inside
task 23's branch rather than as follow-ups: `onlyTheExampleDependsOnSpringSecurity`
widened to exempt `..dataprism.server..` alongside `..dataprism.example..`,
both being the deliberate HTTP edge, since task 17 gave `data-prism-server`
its own OAuth2 resource server and nothing had updated the rule because no
test could see that module until task 23 made it visible; and task 23's
ownership was amended mid-task to include `ServerArchitectureTest.java`, so
the rule narrowing that makes the widened exemption safe landed in the same
branch as the claim about it.

All four branches went through `/verify` with a separate tester and reviewer,
then reached `main` through individually green, required-check-passing pull
requests (#28-#31) — task 23 needed one branch-update-and-recheck cycle after
#28 and #29 landed ahead of it, task 24 needed the same after #30. The
post-merge full-reactor re-run is 394 tests, 0 failures, 0 errors (sum of
every module's surefire/failsafe `Results:` block; see this entry's Cost line
for why that counting method, not the number itself, is what to trust). No
`docker`/compose smoke test exists yet for this wave — that is task 18, not
this one.

**Cost:** two things here are worth not repeating.

First, the counting method. Earlier figures of 366 and 363 circulated this
session before settling on the baseline of 377 for `main@99b419b`; both were
counting artefacts (a partial reactor run and a double-counted IT block), not
missing tests. The number that is trustworthy is the sum of every module's
`Results:` line — surefire and failsafe combined — read off a full `mvn -B
verify` from the repository root. Anything short of that full sum is not
comparable to a prior count and should not be quoted as one.

Second, and the more transferable finding: attempt 2 of task 23 shipped a
javadoc asserting that `ServerArchitectureTest` kept the module's inner
boundary honest. It did not — that rule exempted all of `..server..` and
constrained only what lay outside it, so a Spring Security dependency added to
any other `data-prism-server` class turned nothing red. The false claim
originated in the first implementer's close-out report, was repeated in the
decision put to the repository owner, again in the brief for attempt 2, and
again by a tester — four restatements before any agent opened the file it
described. It was caught only when a reviewer read `ServerArchitectureTest`
directly. `docs/conventions.md` already forbids a comment asserting
unestablished state; the lesson this adds is that a citation repeated between
agents accumulates apparent authority without acquiring evidence, which is
also why tester and reviewer are worth keeping as separate passes rather than
folding one into the other. This is the ninth and tenth cannot-fail assertion
found in this repository — `ServerPackagingIT`'s original scan (task 24) and
this false enforcement claim (task 23) — and the pattern is now the dominant
defect class here.

## 2026-09-14 — Task 17: package the standalone Data Prism server

Data Prism now ships `data-prism-server`, an executable Spring Boot application
and the primary deployment surface for protecting existing APIs. It exposes a
safe unauthenticated `/health` endpoint and a JWT-authenticated Streamable HTTP
MCP endpoint whose configured path is shared by the servlet registration and
the MCP SDK transport. Production startup remains fail closed until policy,
key reference, reviewed source adapters, identity resolution, audit and privacy
configuration are all present and valid.

The executable uses Spring Boot's `PropertiesLauncher`, so an operator can add
a separately reviewed adapter extension through `loader.path` without compiling
fixture code into the distribution. Packaging tests prove such an extension
starts, an extension without an identity resolver is refused, and the artifact
contains no example dependency, stub adapter, fixture profile or development
HMAC key. Signed-JWT boundary tests cover the successful MCP initialize exchange
as well as invalid signature, issuer, audience, expiry, not-before, discovery,
claim extraction, health and deny-all cases. PR #26 passed its required build;
the post-merge 16-module Maven reactor also passed on `main`.

**Cost:** the first implementation supplied a pass-through identity resolver by
default, which would have converted an application-owned trust decision into a
silent production fallback. Independent review caught it and the server now
requires an explicit resolver. The original security test only asserted that a
valid token did not receive 401, so a missing or denied endpoint could pass; a
real initialize request both fixed that vacuity and exposed that the servlet
used the configured path while the SDK still expected `/mcp`. PR #25 corrected
Task 17's ownership before the shared MCP factory and auto-configuration were
changed to pass one exact path through both layers. JWT fixtures were also moved
from wall-clock-relative claims to fixed validity windows. The final independent
run covered 377 tests in 57 non-empty suites with no failures, errors or skips.
The distribution deliberately bundles no generic source adapter: reviewed Java
extensions are the transitional path until Task 20 supplies the separately
classified, allowlisted configuration-driven JSON adapter.

## 2026-09-14 — Task 16: ship the Spring Boot starter and embedded protected-API example

Data Prism now ships a dependency-only `data-prism-spring-boot-starter`. An
application adds that dependency, supplies its reviewed source adapters,
identity resolver, key resolver, audit sink, metrics and inbound security, and
the shared auto-configuration owns the MCP SDK server lifecycle and async
servlet registration at the configured path. The HTTP example no longer copies
the production privacy/MCP assembly; its fixture-only stdio entry point remains
separate.

The example consumes the validated `dataprism.*` security contract for direct
JWKS and exact issuer-discovery-document locations. Its protected end-to-end
suite starts an HTTPS JWKS fixture, authenticates a real JWT, calls `/mcp`, and
proves the source response is pseudonymised without leaking the raw subject,
name or email. Startup tests also prove missing adapters, identity resolution
or caller-context extraction refuse the application rather than silently
serving an empty or absent boundary. The post-merge full 15-module Maven reactor
passed on `main`.

**Cost:** preflight exposed a contradiction in the original task: a POM-only
starter could not create `/mcp` because the shared auto-configuration stopped
at a transport record, while the task did not own that module. PR #22 corrected
ownership before implementation so lifecycle and servlet wiring could move to
the shared module without putting Java code in the starter. Independent review
then found that HTTP beans ignored the selected transport mode, a missing
caller extractor silently removed the endpoint, and issuer discovery was wired
through an API that derives a metadata path instead of fetching the exact URI
the configuration contract promises. Successive fixes added explicit
HTTP/servlet conditions, stable fail-closed startup diagnostics, bounded exact
discovery fetching with issuer/JWKS validation, and non-vacuous regression
tests. The final independent run covered 354 tests with no failures, errors,
skips or zero-test suites.

## 2026-09-14 — Task 15: build the validated Spring Boot configuration core

Data Prism now has a shared `data-prism-spring-boot-autoconfigure` module for
both supported deployment surfaces. Typed `dataprism.*` properties cover the
deployment contract, startup validation rejects invalid source URLs, roles,
capabilities, purposes, unsafe production stdio and missing required beans, and
the configuration builds the existing privacy and MCP components without
introducing another mapper or reversing the module boundaries.

Secret references are operational rather than decorative: the configured HMAC
reference is resolved through the configuration-bound provider, and an
application bean cannot accidentally bypass it. The focused configuration
suite contains 15 tests, and the full reactor passed before the protected PR
merged.

**Cost:** independent review found three fail-closed gaps across successive
iterations. The first implementation did not validate the active key/profile
relationship and omitted documented configuration groups. The next accepted
an HMAC reference syntactically without using it to resolve the key. The final
iteration initially allowed an application `SecretKeyProvider` to win bean
selection and bypass that configured reference. Making the
configuration-bound provider primary closed the ambiguity while retaining the
application-owned adapter and identity-resolver requirement. These findings
are why future distribution modules should consume this configuration core
rather than duplicate its binding or bean-selection rules.

## 2026-09-13 — Task 14: define the shared deployment configuration contract

Data Prism now has one documented deployment contract for its two supported
surfaces: the standalone Streamable HTTP server is primary, while the Spring
Boot starter is an embedded integration that consumes the same validated
configuration core. The configuration reference names every `dataprism.*`
group, distinguishes required settings from defaults and secret references, and
keeps stdio restricted to fixture-only, single-principal development.

The contract preserves the existing trust boundaries. Operators configure fixed
sources and reviewed adapters; an MCP caller cannot choose a host, path, field,
or schema. Java-first applications still supply explicit, annotated models and
adapter/identity beans. Configuration-driven JSON sources remain deferred until
their separate allowlist, classification and schema-validation task.

**Cost:** the first architecture wording over-constrained the new module graph:
it said nothing depended on auto-configuration even though the planned starter
does. Review corrected that to the actual rule — core, privacy, MCP and
orchestration cannot depend on wiring/distribution modules, while the starter
and server intentionally consume shared auto-configuration. The correction is
what makes Task 15's ownership unambiguous.

## 2026-09-13 — Tasks 11, 12 and 13: close the remaining safety-test gaps

Task 11 replaces the REST adapter's blanket `RuntimeException` assertion with
the specific HTTP 500 exception and status the fixture produces. An unreachable
server now fails the test's negative proof instead of satisfying it. Task 12
adds the missing `Instant.now()` determinism rule, rejects reflective mutation
and `Unsafe` in the privacy modules, and proves an injected validation refusal
returns no response while recording a DENY audit event. Boundary 5 remains
prose-only: the re-identification module is still deliberately deferred.

Task 13 adds `HazelcastStoredValueBoundaryTest`. It carries a distinctive,
synthetic sensitive value through the real scrubbing and cache-backed synthesis
path, exercises identity, re-identification and budget maps, inventories every
live distributed map, recomputes legal entries from their keys, and scans every
key and value for that raw fixture. The test rejects an unclassified fourth map
as well as a raw value deliberately written by its mutation proof.

**Cost:** Task 13's first review found that the raw fixture was only local to
the test and did not reach the production-adjacent path leading to Hazelcast.
The correction matters: a boundary scan detached from the path it claims to
protect can pass while proving nothing. The task also confirmed the existing
workflow constraint that Maven/fixture tests need an isolated target and local
socket access; the restricted sandbox could not bind the HTTPS fixture, while
the normal verification environment passed. All three PRs passed their required
GitHub Actions build checks; Tasks 12 and 13 were rebased and rebuilt after
earlier protected merges so every merge was up to date. The post-wave
`mvn -B verify` run on `main` passed.

## 2026-09-09 — Task 10: the mTLS refusal assertion made cross-platform

`main` went red on GitHub Actions at commit `3f6a59a`.
`MutualTlsRestClientsHttpsTest.clientWithoutCertificateIsRefused` required an
`SSLException` in the cause chain. That holds on Windows; on the Linux runner
the server closes the TCP connection before the client reads the TLS alert, so
the client surfaces `HTTP/1.1 header parser received no bytes` instead. The
handshake was refused on both — the test asserted one platform's spelling of
it.

The test now accepts either observable and documents both in its javadoc. The
negative cases still hold: pointing the base URL at a closed port fails it,
and so does a request that reaches the server and gets a 404 — that second one
is what separates "refused during the TLS handshake" from "reached the server
and got an error", which the assertion this test started life with could not
distinguish. Verified green on the Linux CI runner in GitHub Actions run
34386674901, on `main` at `b953014` — this is the run being recorded as
closing the task, since every prior local verification of this assertion ran
on Windows.

**Cost worth recording, and it is about how this was verified rather than
about TLS.** This is the test task 08 rewrote, correctly, to fix a genuinely
vacuous `isInstanceOf(RuntimeException.class)`. The tightening was right and
should not be read as a mistake. What was wrong is that every verification of
it ran on Windows — a 20-run stability check included — while CI runs Linux. A
platform-specific assertion is invisible to a platform-specific verifier. The
single earlier failure seen under a full run and attributed to `target/`
contention was probably this, and that explanation was accepted too readily.

Recorded honestly as a known trade-off, not a hidden one: the Linux branch of
the assertion matches a literal JDK message string, because the wrapped
exception type varies with kernel behaviour and is not a reliable
discriminator. A JDK upgrade that reworks that message will turn this test red
for a reason unrelated to mTLS. That was a deliberate choice with the
alternative rejected; whoever sees it fail should read the javadoc before
assuming a real regression.

Two findings from this task are not this task's work, and are recorded as open
in `docs/plan/PLAN.md` rather than fixed here. First: `main` had no branch
protection, so nothing required the `build` check to pass before a branch
reached it. Repository auto-merge was, and is, not enabled
(`allow_auto_merge` is `false`) — PR #9 did not merge itself. It was merged
into `main` by this task's own implementer, using the owner credentials every
agent authenticates as, while its own Actions run was failing (run
34385475478); PR #10 was merged the same way. That is a role-contract
violation — `docs/workflow.md` puts merging in `/record`, after `/verify`,
not in the implementer that did the work — not a repository misconfiguration,
and this task itself was never reviewed for that reason. The missing branch
protection is a decision for the repository owner and is tracked separately;
the self-merge is a process gap in the role contract, tracked in
`docs/workflow.md`. Second: a seventh cannot-fail assertion at
`data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/RestDataSourceAdapterHttpTest.java:97`,
the same vacuous `isInstanceOf(RuntimeException.class)` form task 08 removed
from its neighbour. A survey of every test module found no other test
asserting on a platform-specific exception type or message, so the
cross-platform problem appears confined to the one test fixed here.

## 2026-09-09 — Task 09: shipped defaults, and closing the last gap in the log scan

This closes S8 and the cheap half of S9, and brings the plan to its
recommended stopping point. Five items, two substantive.

`DataPrismAssembly` stopped minting a context holding
`Capability.EXPOSE_SOURCE_NAMES` — the capability that unmasks real source
names — and handing it to any caller that asked. Task 06 worked around this
rather than fixing it, because it did not own the file, and the visible
symptom was that the documented worked example showed unmasked source names
while the actual application path masked them. `WorkedExampleTest`'s
assertions on literal source names moved to aliases, so the specification's
§64 worked example now demonstrates what running the application actually
does. The shipped `developer` policy became a named factory that main uses and
a test asserts, so the default that ships is the one under test rather than
one every test rebuilds for itself.

`PiiLogScanTest` now parses `dataprism.audit` records field by field instead
of matching raw text on word boundaries. That closes a residual gap — a banned
value glued to word characters, `subject=SUBJ-123a7f9` or `id_456_x`,
previously passed the scan, and the first is the shape a pseudonymisation bug
concatenating a raw id onto a prefix would produce.

**Cost worth recording — this test has now had three separate flake sources,
and the third was found only because 30 sequential runs were required as
evidence.** First, bare substring matching collided with random hex in audit
fields, about one run in four. Second, a real `Clock.systemUTC()` timestamp:
not hex, but its nanosecond digits are equally random, and one run
coincidentally spelled `456`. Third — not a flake but the thing that masked
the diagnosis — concurrent Maven processes against one `target/`, recorded
under task 07.

The general lesson: any unpinned field carrying random digits can impersonate
a leaked identifier, and a leak detector that cries wolf is one nobody reads.
The fix is that a field is exempt from scanning only by matching a pinned
shape, never by its name — an exemption keyed on a name is one a future field
inherits by accident, which is exactly how the timestamp field became a flake
source. Verified at review by making an exemption name-only and watching the
guard test redden.

Verification evidence: 50 consecutive sequential runs of `PiiLogScanTest`, 10
each of `WorkedExampleTest`, `ShippedDefaultsTest` and `EndToEndTest`, all
clean, no leaked ports or JVMs.

Two small items left open, not fixed here: `PiiLogScanTest.java:191-193`, a
tautological sum assertion (the sixth cannot-fail assertion found in this
repository, this one from the task brief rather than the implementer); and
`PiiLogScanTest.java:104-112`, a javadoc claiming "twenty placeholders" where
`AUDIT_KEYS` holds 19 names because the sink's `seq={}/{}` is two placeholders
folded into one field. Both recorded in `docs/plan/PLAN.md`.

**Cost:** the flake diagnosis above is what took the time — three unrelated
causes had to be told apart before the third fix could be trusted, and the
only way to trust it was volume (30 sequential runs) rather than a smaller
number of clean ones. Nothing was tried and abandoned on the substantive
items; the factory-not-caller fix for `EXPOSE_SOURCE_NAMES` was the design
task 06 had already pointed at but could not make because it did not own
`DataPrismAssembly`.

## 2026-09-09 — Task 07: OAuth2 resource server, Micrometer, PII log scan

This closes S8 and the cheap half of S9. A Spring Boot resource server validates
caller JWTs against a configured JWKS, an extractor puts an `AuthenticatedCaller`
— and only that, never the raw token — into the MCP transport context, and the
tool derives scope, principal, purpose and case from it. stdio's production
refusal is wired at the one call site that existed. Micrometer binds through
`MicrometerPrivacyMetrics`, which guards inside the implementation so a
conflicting meter registration cannot fail a lookup.

The criterion that mattered: task 06 built the streamable HTTP transport but
nothing exercised it — its test asserted a builder returned non-null and never
opened a socket. `McpHttpEndToEndTest` now makes a real HTTP MCP request bearing
a locally-minted JWT against an in-test JWKS, on an ephemeral port, and asserts
the `PrivacyContext` carries values derived from the token. Review proved it
non-vacuous by hardcoding a constant caller in the extractor and watching four
tests redden, and confirmed each negative-direction mutation flips exactly one
direction, so no test passes on a blanket refusal. Its `@AfterAll` asserts a
fresh socket connect to the port throws, so the server is proven released
rather than merely assumed.

**Cost worth recording — a false PII-leak alarm caused by the verification
process, not by the code.**

`PiiLogScanTest` failed intermittently with `Expecting empty but was: ["123",
"456"]` — the raw stub subject ids, in the one test whose job is to prove no
personal data reaches a log line. Attempt 1 was rejected for it after a
reviewer measured 3 failures in 12 runs and diagnosed incidental hex collision:
`BANNED_VALUES` held bare `123`/`456` while audit lines carry random hex.
Attempt 2 switched to word-boundary matching and reported 25 clean runs; a
second review approved with a structural argument that no field
`Slf4jAuditSink` emits can produce a 3-character word-bounded token. A tester
then ran it 30 times and got 5 failures, 4 of them the same paired ids, and
reasoned that a pair failing together is not what independent hex collisions
look like — which is correct, and which pointed at a real leak.

It was not a leak. Investigation checked the compiled bytecode directly:
`DefaultContextOrchestrator.audit()` loads the HMAC pseudonym, not
`request.subjectId()`, and no logging call site in the pipeline touches a raw
subject id. 100+ sequential and parallel reproduction attempts never produced
the failure. What did reproduce was concurrent Maven processes contending over
one shared `target/`, failing on exactly the class named in the accompanying
`ClassFormatError`.

The mechanism: the only change in the codebase that produces this exact
symptom — both ids, paired, in an otherwise well-formed `event=` line — is
swapping `subjectToken` for `request.subjectId()` in
`DefaultContextOrchestrator`, which is precisely the mutation both the
implementer and the reviewer run as their "prove this test can fail" step.
Verification spawns tester and reviewer concurrently against one worktree.
Once the reviewer compiles a mutated tree while the tester runs builds,
mutated classes can land under a running test.

Final confirmation, single agent, clean `target/`, strictly sequential: 50/50
passes, 318 tests. Re-verified again at close-out with a full clean sequential
`mvn -B verify` from `main` post-merge: 318 tests, 0 failures, 0 errors, 0
skipped — including `MutualTlsRestClientsHttpsTest`, itself flagged as a
one-off failure under a contended full run (see below), which passed cleanly
both times.

**Two process rules this establishes, added to `docs/conventions.md`:**

1. **Never run a compiling reviewer and a tester concurrently against one
   worktree.** Read-only verification can be parallel; this project's
   reviewers routinely mutate and rebuild to prove a test can fail, which
   makes them writers of `target/` even though they touch no tracked file.
   Either the reviewer clones first, always, or the two run in sequence. A
   contended `target/` does not fail loudly — it produces a wrong test
   result, and in this case one indistinguishable from a privacy defect.
2. **A failing leak-detection test must preserve its captured output.**
   `PiiLogScanTest` writes the full capture to `logs/pii-log-scan-<millis>.log`
   before asserting, and every failure so far deleted it before anyone read
   it. The line itself settles a leak question in one look; without it, this
   cost four agents and several hundred test runs to resolve by inference.

Also record, as a known residual and not a defect: `PiiLogScanTest`'s
word-boundary matching means a banned value glued to word characters is not
caught — `subject=SUBJ-123a7f9` and `id_456_x` both pass, verified at review.
No current code path emits either, but the first is the shape a
pseudonymiser bug concatenating a raw id would produce. Scanning the audit
line's structured fields rather than raw text would close it. Queued for task
09.

Also note `MutualTlsRestClientsHttpsTest`, which failed once during an
earlier full run, passed 20/20 standalone, in two full reactor runs during
review, and again in both of the close-out's clean sequential runs. Not a
flake; the same `target/` contention as the log-scan alarm above.

## 2026-09-09 — Task 06: streamable HTTP transport, per-request caller context, authorisation at the tool

This is the task that makes S8 mean something. `PrivacyContext` was a constant
and `scopeId` is half the pseudonymisation key, so scope isolation was real in
the code and vacuous in the deployment — there was only ever one scope. It now
derives from an authenticated session.

`DataPrismMcpServer` exposes two named factories instead of one constructor: a
stdio mode that refuses to start without an explicit development flag or under
a production profile, and a streamable HTTP mode taking a caller-supplied
`contextExtractor`. Both build `JacksonMcpJsonMapper` over the single
`DataPrismObjectMapper.create()`, so boundary 1 holds on both paths and
`ArchitectureTest` still enforces the one construction site.

`GetEntityContextTool` reads the caller from `exchange.transportContext()` —
it previously discarded the exchange entirely — then authorises, resolves the
session, and audits a denial. No caller means refusal with zero orchestrator
calls. A denial returns a code only, never partial data.

**Cost:** the task text said to use the assembly's development caller, but
`DataPrismAssembly.investigationContext()` carries `EXPOSE_SOURCE_NAMES` by
construction — the capability that unmasks real source names — so following
the task text literally would have violated the task's own acceptance
criterion that no dev principal holds it by default. The implementer built a
scoped-down caller in `ExampleApplication` and reported the contradiction
rather than silently choosing, and review confirmed that was right. The
consequence is that `DataPrismAssembly` still hands that context to anything
else that asks, and `WorkedExampleTest` is one such caller — now open item 1
below.

Review found no defects. Mutation proofs covered blanket ALLOW and blanket
DENY, `MCP_DENIED` removal, `MCP_REQUESTS` removal, `ReservedArguments`
bypass, a silent fallback caller, both halves of the stdio guard, a constant
`scopeId`, and `EXPOSE_SOURCE_NAMES` leaked into every decision. One
surviving mutant was correctly identified as equivalent rather than a gap:
neutralising the tool's own `!decision.allowed()` branch survives because
`ScopeResolver.resolve` re-refuses with the same code — defence in depth
working, not dead code.

One correction to the record, because it matters for how these numbers are
trusted: the reviewer reported "286 tests" from its scratch clone while the
tester reported 300. The tester was right — the branch has 292 `@Test` + 8
`@ArchTest` = 300, main has 279 + 8 = 287, no parameterized tests, and the
reviewer's clone was not this branch; 286 matches neither tree. Its
per-criterion findings stood regardless, each anchored to a file:line in the
actual worktree with mutation proofs run there. The criterion that replaced
the stale literal is only as good as the tree it is measured on.

Open items, not fixed here, going into a follow-up task:
1. `WorkedExampleTest` still runs on `assembly.investigationContext()` and
   prints real source names, so the documented worked example no longer
   matches what running the application shows.
2. Granting `Capability.EXPOSE_SOURCE_NAMES` to the shipped `developer` role
   in `ExampleApplication.java:49` kills no test — every capability test
   builds its own policy, so the shipped default is unguarded.
3. Nothing exercises the HTTP transport end to end: the new test asserts the
   builder returned non-null and never starts a server or opens a socket, and
   `EndToEndTest` drives the call handler through a synthetic exchange. The
   `contextExtractor` delivering a real caller into
   `exchange.transportContext()` on a real request is unverified.
4. `ScopeResolver`'s class javadoc still omits the purpose refusal added in
   task 08.
5. `data-prism-example/pom.xml` gets `data-prism-security` transitively
   through `mcp` rather than declaring it directly.

## 2026-09-09 — S8: three inert controls made to run

The theme is worth recording as a theme, because it is the shape of defect this
project keeps producing and the one a reviewer is least likely to catch: a
control that is present in the code and does nothing. Someone reading for "is
there a check?" finds one.

`PurposeValidator` existed and failed closed correctly when called, and nothing
called it. It is now composed into `ScopeResolver`'s sole constructor — no
overload, no default, `requireNonNull` on the field — and runs on the path that
builds a `PrivacyContext`, before the context is created. An unknown purpose
refuses with `UNKNOWN_PURPOSE` and yields no session.

`MutualTlsRestClientsHttpsTest` asserted `isInstanceOf(RuntimeException.class)`,
which a URL typo or a missing file satisfies. It now asserts
`ResourceAccessException` with an `SSLException` in the cause chain, and differs
from the positive test only by the key manager.

`EndToEndTest`'s reserved-argument assertion passed on a blanket DENY, so it
could not distinguish "the caller's own scopeId was ignored and the call
proceeded on session-derived context" from "the call was refused for an
unrelated reason". It now asserts content equality against a plain call plus a
single `ALLOW` audit event naming exactly the four rejected argument names. Both
directions of failure are now live on that assertion, proven at review by
mutating `GetEntityContextTool` to honour the caller's `scopeId` — the assertion
caught it, with the pseudonym differing between runs.

This brings the count of tests-that-could-not-fail found in this project to
five, all recorded in `docs/conventions.md`. Two of them were fixed here. 287
tests post-merge (up from 285), all 13 modules, `mvn -B verify` clean.

**Cost:** nothing unusual on the implementation side — this was a small,
targeted diff against three known files. The finding worth carrying is a
review note not actioned: `ScopeResolver`'s class javadoc
(`data-prism-security/.../ScopeResolver.java:12-22`) still describes only
scopeId and pseudonymisation pinning, omitting the purpose refusal that is now
the class's third responsibility. Left for whoever next touches the file
(task 06, which writes the first call site of the new constructor) rather than
edited here, since only `implementer` writes code and this task's owned-files
list did not cover a doc-only javadoc pass as its own item.

## 2026-09-09 — S8 wave 2: security module, real principal, and identity metrics

The three tasks that depended only on wave 1 landed together. `AuthenticatedCaller`,
`AuthorizationService`, `AuthorizationDecision`, `PurposeValidator` and `ScopeResolver`
give Data Prism capability-based authorisation, with roles from token claims mapped to
capabilities in configuration; `ScopeResolver` pins `keyId` and `vocabularyId` at scope
creation, so a key rotation or vocabulary change mid-scope cannot silently change what a
pseudonym means. `DefaultContextOrchestrator` previously hardcoded the principal as
`"system"`, so every audit event was attributed to a principal that does not exist — the
real authenticated principal now flows through into audit, and `SourceAliasing`'s
expose-real-names boolean became a real capability check. Hazelcast identity cache hit,
miss, collision and re-identification counters now publish through the `PrivacyMetrics`
SPI. 285 tests post-merge (up from the 224-test baseline this wave started from), all 13
modules, `mvn -B verify` clean with all three branches merged into one tree.

**Cost:** the Hazelcast metrics task took three attempts, and the reason generalises
past this file. Attempts 1 and 2 both failed on the same theme: instrumentation that can
change the thing it measures. Attempt 1 emitted `dataprism.identity.cache.miss` twice on
one lookup when the *generator* threw rather than the cluster, so the published metric
disagreed with the counter it exists to publish. Attempt 2 fixed that but guarded only
one of three emit sites — a `PrivacyMetrics` whose `increment` throws could still either
escape `syntheticValue` (breaking the fail-open guarantee stated in the class's own
javadoc) or, on the collision path, exit `store()` before the corrective `identities.set`,
leaving the known-bad cached value in place so every later lookup in that scope returned
it. The collision counter exists precisely because a non-zero value means an answer may
have changed; its own failure would have caused that outcome. Attempt 3 fixed it
structurally with `FailSafeMetrics`, a wrapper applied once at construction so emit sites
added later are guarded by construction rather than by memory — the transferable point is
that a guard which has to be remembered at each new call site will be missed, and it was,
between attempts 2 and 3. This mattered immediately rather than hypothetically: task 07
binds Micrometer, which throws on conflicting meter registration.

One deliberate exception, ruled correct on review rather than an oversight to fix later:
`ScopeIdentityIndex.subjectFor` calls `metrics.increment` unguarded. Re-identification is
not the fail-open case — the identity cache is, because a synthetic value is a pure
function and nothing can recompute a subject id from a pseudonym. A metrics failure
refusing to reveal a subject is fail-closed working as intended.

## 2026-09-09 — S8 wave 1: session types, metrics SPI, and mTLS to sources

The two tasks with no dependency on the rest of S8 landed first. `InvestigationContext`
and `Capability` give the pipeline a caller shape to carry once wave 2 threads a real
principal through it; `Metric` and `PrivacyMetrics` are the SPI wave 2 and S9a report
through, with `PrivacyMetrics.none()` as the default so nothing downstream is forced onto
a backend yet. `data-prism-security` is registered in the reactor as an empty module,
ready for task 03. Outbound calls to source systems can now run over mTLS:
`TlsSettings`, `MutualTlsRestClients`, `RestSourcesConfig`, a `requireHttps` mode on
`RestSource`, and `tls:` block parsing. 220 tests after task 01, 224 after both, all 13
modules, `mvn -B verify` clean with both branches merged together (neither task depended
on the other, so this is the first time they were built as one tree).

**Cost:** two follow-ups fell out of review that were real but did not block merging,
carried forward as acceptance criteria on task 03 rather than fixed ad hoc:
`Capability.KNOWN` has no test pinning it, so a fifth capability added later without
updating `KNOWN` fails silently — `Metric` already has this test, in `PrivacyMetricsTest`,
and it needed mirroring rather than inventing something new. The other is a vacuous
assertion: `MutualTlsRestClientsHttpsTest`'s no-client-certificate case asserts
`isInstanceOf(RuntimeException.class)`, which would also pass if the test server were
simply unreachable, so it does not prove what it claims to. This is the fourth vacuous
assertion this project has shipped, which is why `docs/conventions.md` calls the pattern
out by name — worth treating as a class of bug to look for on every review, not a one-off.

Also settled this session and worth not re-litigating: Data Prism is an OAuth2 resource
server, never a token issuer and never a pass-through of the caller's token to source
systems. See `docs/architecture.md#decisions-worth-knowing` (2026-09-09) for the full
reasoning — the short version is that pass-through would recreate the network path
`pack.md` §87 asks to be impossible, and would collapse two distinct authorisation
questions into one.

## 2026-09-09 — S7 embedded Hazelcast for distributed scope state

Three pieces of shared state that look alike and are not: the identity cache, the
read budget, and the re-identification index. 212 tests.

**Cost:** the useful distinction, and the one to preserve if this is ever changed.
The identity cache may fail open, because a synthetic value is a pure function of
scope, subject, namespace and key — losing the cluster costs a recomputed HMAC
and never a different answer. The read budget must fail closed, because an
unreachable budget means nobody is counting and continuing would silently remove
the only limit on how much a caller can extract about one subject. The first
version of that fail-closed path did not work: obtaining the map and taking the
lock are themselves cluster calls that throw when the member is gone, and they
sat outside the guard, so the exception escaped before the refusal could run.

Embedded rather than client-server, reversing the design review. The objection was
that autoscale rebalancing disturbs the identity map; it does not, because a lost
partition costs recomputation rather than a rename. What the argument does not
cover is the re-identification index, which is a store rather than a cache —
nothing recomputes a subject id from a pseudonym — so it is off unless a
deployment enables it and accepts that its durability is the cluster's.

Departed from the specification's §24 sketch, which returns the stored value on a
concurrent write. Two threads deriving one key derive one value, so a differing
stored value is not a race but a mid-scope change of key, algorithm or
vocabulary. Preferring it would make output depend on cache state.

Tests use an embedded member rather than Testcontainers because Docker was
unavailable; a multi-member test is still worth adding. Hazelcast startup makes
this module's tests take about a minute against under ten seconds for everything
else.

## 2026-09-09 — S6 correlation and consistency findings

Three systems holding "Patrick Murphy", "Pat Murphy" and "P. Murphy" now produce
one consistent identity and a finding saying the systems disagree. The
specification's §64 worked example runs end to end. 201 tests.

**Cost:** the ordering is the whole slice and is easy to get backwards.
Correlation runs on raw records before scrubbing, because the pseudonym is keyed
on the subject — after scrubbing all three spellings are the same string, and
correlating then would report perfect agreement about data that agrees on
nothing. Anyone moving correlation later in the pipeline will find it still
compiles, still passes most tests, and silently reports agreement.

Findings carry no values, only which sources agreed with each other. Fields match
across sources by namespace rather than name, and a field with no namespace is
deliberately not correlated: two fields both called `status` in different systems
are usually not the same fact.

Two things worth not undoing. `ComparisonForm` is aggressive where
`Text.canonical` is conservative, on purpose — being wrong in the latter splits a
subject or lets a leak past, being wrong in the former downgrades a finding.
`ABBREVIATION` is restricted to name-like namespaces because "one value is a
prefix of the other" is a genuine clue about an identifier.

The injection heuristic first examined only namespaced fields, which is precisely
where instruction text does not live — it lives in free-text notes. Correlation
and the heuristic were never the same filter and sharing one hid the bug.

An architecture rule caught this work: three classes each built their own reading
ObjectMapper, so the "one mapper" rule had a four-name allowlist that would have
grown again. Reading is consolidated in `SourceTree` and the allowlist is two
names — one that reads, one that writes.

## 2026-09-09 — S5 parallel connectors and request limits

Sources are called in parallel on virtual threads under a bulkhead, a per-source
timeout and a source cap, with a circuit breaker per source. A failing source is
recorded absent and the answer built from the rest. 180 tests, new
`connectors-rest` module.

**Cost:** three things worth knowing. The per-source timeout is wall-clock from
the start of the fan-out, so a source queued behind the bulkhead spends part of
its budget waiting — intended, and it means concurrency set far below the source
count shows up as timeouts rather than slow success. Holding nothing for a
subject is an answer and does not trip a breaker; treating it as failure would
open the breaker on a healthy source. And putting per-source `Duration` in the
response broke MCP serialisation, which is how it surfaced: latency is
operational telemetry, and infrastructure timings tell an untrusted reader about
the health of systems it cannot otherwise see.

Two test traps caught here. An assertion on a decoded URL path fails for the
right reason and the wrong one — `/customers/../../admin` contains `/admin` while
still being one safe segment, and the property to assert is that the slash
arrived encoded. And a test class named `...IT` is Failsafe's convention, so
Surefire skipped it in silence: a test that never runs is worse than no test.

## 2026-09-08 — S4 pattern detection with a scope-aware allowlist

The validator now catches sensitive values that were never in a classified field
— an identifier in a free-text note, an IBAN in a description. Detectors for
IBAN, card PAN, PPSN, SSN, email, phone, JWT and common API-key prefixes, run
over the whole tree at any depth and size-bounded. 146 tests.

**Cost:** detection and the allowlist had to be one change. SYNTHESIZE emits
`person.kz48@example.invalid`; an email detector matches it, fail-closed refuses,
and every synthesising profile deadlocks. Building detection first would have
produced something that passes its own tests and cannot be deployed. The engine
therefore reports what it emitted and the validator permits those values —
PASS_THROUGH values deliberately excluded, since a field passed through unchanged
should still be scanned.

The non-obvious call is that **shape refuses and the checksum only labels the
reason**. Gating refusal on a valid checksum is unimplementable here: fixtures are
required to carry invalid check digits, so every detection test would be vacuous.
Checksum correctness is proved by counting valid variants — one of 100 IBAN
check-digit pairs, one of 10 card final digits, one of 23 PPSN letters — without
committing a valid value anywhere.

This also caught a breach of the project's own rule: `IE29AIBK93115212345678`,
introduced in S3 and merged to main, computes to mod-97 = 1 and is therefore a
structurally valid IBAN. A documentation example rather than a real account, so
nothing was disclosed, but the rule exists so a fixture cannot quietly be real.

## 2026-09-08 — S2a key rotation

`MultiKeySecretKeyProvider` resolves several keys at once, so a rotation can begin
without invalidating scopes still running under the previous key. Adding a key
never displaces another; `remove` is the only way to retire one. 132 tests.

**Cost:** small slice, one trap. The tempting fallback is to return the only key
held when an unknown id is asked for — it looks harmless and would silently change
every synthetic value in that scope while nothing appeared to fail. There is a
specific test for a provider holding exactly one key still refusing a different
id. The provider is deliberately not a `record`, because the generated
`toString()` would print every key.

## 2026-09-08 — S3 scrubbing engine, and the nesting hole it closed

Nested objects and collections are descended into rather than copied; class-level
defaults and descriptor files provide two routes to classifying a model without
annotating every field; and the action set is complete with HASH, TOKENIZE and
GENERALIZE. 117 tests.

**Cost:** the reason this slice mattered was a fail-open hole nobody had noticed.
A nested object declared `@NonSensitive` — the natural annotation for a
sub-structure believed inert — was copied into the response wholesale, raw values
included, and the validator did not catch it because `SourceValues` only walked
top-level fields. Eighty-three green tests missed it because every fixture was
flat. If a future change makes the engine copy a subtree again, that is the
failure to look for.

Two things fell out of fixing it. A nested structure must inherit its parent's
subject, or every nested synthesised field fails for want of one. And an
undescendable subtree must consult the profile's unclassified setting rather than
the holding field's action — consulting the field meant a `@NonSensitive` wrapper
passed its contents through even under FAIL_REQUEST, which was the original hole
wearing a different hat.

Retrofit ergonomics drove the rest. Annotating every field of a large legacy model
produces no information and creates pressure to disable fail-closed globally, so
`@LlmExposedModel(undeclaredFields = ...)` states per type what silence means, and
descriptors classify types that cannot be annotated at all. Descriptors may only
tighten: if configuration could declassify a field, anyone who can edit a file can
disclose data. Namespace is the one thing that cannot merge by strictness and
fails loudly on disagreement, because picking one would split a subject in two.

## 2026-09-08 — S2 configurable multi-locale name pools

Name pools are now configuration. Seven sets ship — a widened Latin default,
pan-European, Irish, Arabic, Mandarin, Cyrillic, and the original pool frozen —
and any of them can be replaced, extended, or joined by a locale that is not
bundled. Rendering follows the script: Han names are family-first and unspaced,
Han addresses run largest unit first. 83 tests.

**Cost:** the thing that is easy to miss is that a name is chosen by
`digest mod pool size`. Adding one entry shifts the choice for a large share of
subjects, silently, for every scope at once — under a scheme where a pseudonym
must stay stable for the life of an investigation, that is indistinguishable
from corruption. Vocabularies are therefore content-addressed and pinned by the
scope exactly as the signing key is, and a generator serving a scope pinned to a
different pool refuses. Two golden vector files exist for the same reason: the
frozen `generic-v1` reproduces every S0 vector byte for byte, and `western-v2`
disagrees with it on the same subject. The disagreement is the evidence.

Unicode closed two failures that both looked like success. The same person
stored NFC in one system and NFD in another would have become two people; and a
value leaked in the other normalisation form would have passed the output
validator while it reported the check as passing. Canonicalisation is NFC plus
removal of invisible bidi and zero-width marks, and deliberately does not fold
case, accents or Cyrillic Ё — merging genuinely different names is the
symmetric failure and just as damaging.

Two choices that look like bugs and are not. Russian surnames are listed in both
gendered forms and paired by digest, so a pseudonym can read as grammatically
odd; enforcing agreement would make the pseudonym encode the subject's gender.
And locale is configuration rather than per-record detection, because a
locale-matched pseudonym preserves an attribute the pseudonym was otherwise
removing — that should be a decision someone makes, not a default.

A test asserting that no bundled set mixes scripts caught two stray Latin and
Cyrillic entries in the Mandarin pool during authoring.

## 2026-09-08 — S1 policy layer and build-time fail-closed

Privacy decisions moved out of model classes and into YAML profiles. The engine
now applies a decision from `PrivacyPolicyResolver` rather than reading
`suggestedAction`, so changing what is disclosed no longer means editing and
redeploying application code. Metadata is read from plain classes and accessors
as well as record components, `@SubjectIdentifier` lands, and an annotation
processor fails the build on any unclassified field of an exposed model. 55 tests.

**Cost:** the interesting problem was combining a profile rule with a model
author's suggestion. Letting the profile win outright would let an annotation
widen disclosure by being edited; letting the annotation win would make the
profile advisory. Both are wrong, so the two are combined by taking the stricter,
which needs a total order over actions — and that order is a judgement, not a
fact. It is stated and argued in `ActionStrictness`. The placement worth knowing
is SYNTHESIZE below REDACT: a stable pseudonym is linkable within its scope,
which is strictly more disclosure than redaction, even though it looks more
thorough. `override: true` exists so an over-classified field can still be
relaxed, deliberately and visibly.

Profiles are parsed by hand from a generic map rather than data-bound. Binding
turns a misspelled classification into a rule that silently does not apply, and
a privacy profile that quietly loses a rule is the worst kind of configuration
bug: nothing fails, and less is protected than the file says.

## 2026-09-08 — S0 walking skeleton

One vertical thread now runs from an MCP tool call to a pseudonymised response:
eight Maven modules, the annotation set, a tree-based scrubbing engine, keyed
HMAC pseudonymisation, an independent output validator, a per-writer audit chain
and a stdio MCP server. Thirty-one tests, architecture rules and a banned-
dependency rule run on every build. `Patrick Murphy` reaches a client as
`Jordan Walsh (3W1Q)` with the email redacted and the correlation identifier
absent entirely.

**Cost:** three things cost real time and would cost it again. The MCP SDK 2.x
line has no Spring transport artifact — `mcp-spring-webmvc` stopped at 0.18.4 —
and the `mcp` aggregate pulls `mcp-json-jackson3`, which puts Jackson 3 beside
Spring Boot's Jackson 2 and silently registers the scrubbing module on a mapper
that never serialises MCP output; depend on `mcp-core` plus `mcp-json-jackson2`
and let Enforcer ban the rest. Maven 3.8.6 defaults to compiler plugin 3.1
(no `--release`) and surefire 2.12.4 (cannot run JUnit 5 at all, so the suite
reports success having run nothing); both are pinned in the parent. And a stdio
smoke test fed from a file produces no response to `tools/call` — EOF closes the
transport before the async reply flushes, which looks exactly like a server bug.
Hold stdin open.

Two decisions worth not relitigating: scrubbing operates on a Jackson tree
because records cannot be written back to, and pseudonyms carry a 20-bit ASCII
discriminator because a 32x32 name pool collides well before 50,000 subjects and
a collision tells the model that two people are one.
