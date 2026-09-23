# Plan

What is open, in priority order. Only `scribe` edits this file.

Each item: one line of what, one line of why it matters, and its blocker if it
has one. When an item is in flight, mark it with its task ids. Delete items you
no longer want rather than letting them rot — a plan nobody trusts is worse than
no plan.

Slice numbering (`S0`–`S12`) matches `docs/development-plan.md`, which carries
the sizing and the exit criteria. This file carries the order and the state.

---

## Decided

The five choices that blocked S0 were settled on 2026-09-08. Recorded here in
brief; the reasoning and the rejected alternatives are in
`docs/architecture.md#decisions-worth-knowing`.

| | Decision |
|---|---|
| Coordinates | Group `io.github.aindriub`, artifacts `data-prism-*`, package root `io.github.aindriub.dataprism` |
| MCP | Official MCP Java SDK directly, with Spring wiring written here. stdio in development, streamable HTTP in production |
| HMAC key | Local key supplied at startup through a `SecretKeyProvider` SPI. No vendor client in core |
| Audit sink | `AuditSink` SPI with a file/SLF4J implementation. No vendor client in core |
| Re-identification | Reverse map built in S7. The operator surface (S10) is deferred past V1 |

## Now

### S8 and S9a — done

S8 (security: OAuth2 resource server, scope/principal/purpose/case derived from
an authenticated caller, streamable HTTP transport) and S9a (the cheap half of
observability: real principal in audit, Micrometer metrics, a PII log scan that
can actually fail) are both complete, closed by task 07 merging 2026-09-09. See
`docs/plan/HISTORY.md` — grep `Task 07` — for what landed and what it cost.

### Task 09 — done

Closed the last two real defects and cleared four pieces of debt. Task 09
merged 2026-09-09. See `docs/plan/HISTORY.md` — grep `Task 09` — for what
landed and what it cost.

### Task 10 — done

Made `main`'s red-on-Linux mTLS refusal assertion hold on both platforms it
runs on, without weakening it. Task 10 merged 2026-09-09, verified green on
Actions run 34386674901. See `docs/plan/HISTORY.md` — grep `Task 10` — for
what landed and what it cost.

### Tasks 11, 12, 13 — done

All three safety tasks merged through protected, green pull requests on
2026-09-13. Task 11 makes the REST 500 assertion specific; Task 12 enforces the
missing determinism and validation boundaries; Task 13 proves every live
Hazelcast map contains only recomputable state and no raw sensitive fixture.
See `docs/plan/HISTORY.md` — grep `Tasks 11, 12 and 13` — for verification and
the cost of closing them.

### Tasks 14, 15, 16, 17 — done

The adoption slice: a shared standalone-server/Spring-starter configuration
contract (14), the validated Spring Boot configuration core (15), the Spring
Boot starter and embedded example (16), and the packaged, fail-closed
standalone server (17). All merged through protected, green pull requests
2026-09-13 to 2026-09-14. See `docs/plan/HISTORY.md` — grep `Task 14: define`,
`Task 15: build`, `Task 16: ship`, `Task 17: package` — for what landed and
what each cost.

### Tasks 21, 22, 23, 24 — done

Closed the five defects found reviewing tasks 13-17's delivery (which was done
by OpenAI Codex, outside this kit), plus one fail-open found by the architect
that no reviewer had flagged: fixture-development refusal moved into the
shared validator so every consumer inherits it (21); `PrivacyPolicyResolver`
and `LlmResponseValidator` made non-replaceable, with a classified sweep of
every extension-point bean (22); the architecture rules given back the whole
module graph through a new scanning module (23); the packaged-server secret
scan given a search space that can actually fail (24). Merged through
protected, green pull requests 2026-09-14 (#28-#31). Post-merge full-reactor
re-run: 394 tests, 0 failures, 0 errors. See `docs/plan/HISTORY.md` — grep
`Tasks 21, 22, 23 and 24` — for what landed and what it cost, including the
counting-method settlement and the false-claim-propagation lesson.

### Task 25 — done

Wired the shared read budget through the auto-configuration and made the
Hazelcast topology an explicit configuration choice instead of an implicit
default: `embedded` now produces a real `HazelcastScopeBudget`, and
`dataprism.hazelcast.topology` is required rather than defaulted. Merged
through a protected, green pull request 2026-09-14 (#33). Post-merge
full-reactor re-run: 400 tests, 0 failures, 0 errors. See
`docs/plan/HISTORY.md` — grep `Task 25` — for what landed and what it cost,
including the ownership-breach precedent.

### Task 18 — done

Replaced source-reading as onboarding with one reproducible local Compose
journey: standalone server, synthetic fixture APIs and a local JWT issuer,
proving an agent-compatible MCP request succeeds end to end. Delivered three
new runnable modules (`data-prism-quickstart-fixtures`,
`data-prism-quickstart-issuer`, `data-prism-quickstart-extension`), since none
of the three runnable pieces it needed existed yet and the server refuses the
fixture-development shortcut the original brief assumed. Merged through a
protected, green pull request 2026-09-14 (#35). Post-merge reactor: 403
tests, 0 failures, 0 errors. See `docs/plan/HISTORY.md` — grep `Task 18` —
for what landed and what it cost, including the `.env.example` hand-off this
environment forces on any future dotfile task.

### Tasks 19, 20 — done. The plan is done.

Task 19 published tested MCP agent connection guides — verified against a
real client (Claude Code CLI 2.1.271), not reasoned about, with GUI clients
explicitly excluded rather than listed unverified. Task 20 wired
`data-prism-connectors-rest`, dormant and unconsumed since it was built, into
a configuration-driven JSON REST mode with the same fail-closed guarantees as
the Java-first path, proven by an end-to-end parity test. Both merged through
protected, green pull requests 2026-09-15 (#37, #38). Post-merge full-reactor
re-run: 441 tests, 0 failures, 0 errors. See `docs/plan/HISTORY.md` — grep
`Tasks 19 and 20` — for what landed and what it cost.

With 19 and 20 closed, every task this plan named is done: the five defects
found reviewing the tasks 13-17 Codex work are closed (tasks 21-24), the
deployment slices shipped (14-18), the quickstart demonstrates the whole
system end to end (18), and the configuration-driven JSON mode was the last
piece (20). There is no task open and none in flight. The small open items
below are debt found along the way, not scheduled work — pick one up only if
a future task already owns the file it names.

**Nothing is scheduled past this point.** S5, S6, S7, S10, S11 and S12 remain
in "Someday" below, exactly as the 2026-09-09 stopping-point decision left
them (see "Decided" above and `docs/architecture.md#decisions-worth-knowing`).
Picking any of them up is a new planning decision, not a continuation of this
plan — do not treat this record as opening a next wave.

### Simplification plan, wave 1 (tasks 26-30) — done

A separate planning cycle from the slice plan above: opened 2026-09-15 to
close small duplication and readability debt accumulated across the earlier
waves. Task 26 collapsed the duplicated JWT/OIDC discovery, SSRF guards and
caller-context extractor in the server and the example into one shared
`JwtDecoderSupport`/`JwtCallerContextExtractor` pair in
`data-prism-spring-boot-autoconfigure`. Task 27 reduced
`DefaultContextOrchestrator` to its two used constructors and lifted the
fetch/scrub/merge loop into its own method. Task 28 extracted the duplicated
YAML `enumValue` logic into a new core `StrictYaml` helper. Task 29 bundled
`JsonTreeScrubbingEngine`'s parent/siblings/owner parameters into a private
`OwnerScope` record. Task 30 reformatted `DataPrismProperties` to one
statement per line. All five merged locally 2026-09-15, no conflicts. A
single serialized full-reactor `mvn clean verify` from the main checkout
afterwards — not the five testers' parallel runs, three of which hit
transient classpath failures caused by five concurrent Maven builds sharing
one local repository — gives 440 tests, 0 failures, 0 errors. See
`docs/plan/HISTORY.md` — grep `Simplification wave 1` — for what landed and
what it cost, including why a task's Owns list needs deriving from callers
rather than same-package usage.

### Simplification plan, wave 2 (tasks 31-32) — done. The simplification plan is done.

Task 31 gave `core/descriptor` — the ~320 lines of `DescriptorFieldMetadataResolver`,
`ModelDescriptors` and `ModelDescriptor` that had tests but no production
caller — a real one: an optional `dataprism.privacy.descriptor-file`
property that, when set, loads and validates a model-descriptor YAML file
and wraps the default `FieldMetadataResolver` in
`DescriptorFieldMetadataResolver`. Four distinct refusal codes, all fail
closed with no fallback to the undecorated resolver and no file content in
any message. Task 32 deleted `data-prism-audit` and moved its four classes
and test into `data-prism-core` with the package unchanged, so no consumer's
imports moved; five poms and the architecture module table were updated.
Both merged locally 2026-09-15, no conflicts. A single serialized
full-reactor `mvn clean verify` from the main checkout afterwards gives 460
tests, 0 failures, 0 errors. See `docs/plan/HISTORY.md` — grep
`Simplification wave 2` — for what landed and what it cost, including the
now twice-confirmed unreliability of concurrent Maven builds against this
repo's shared local repository.

With 31 and 32 closed, the simplification plan opened 2026-09-15 has no task
left: waves 1 and 2 closed every item it named. Superseded by the release plan
below, opened the next day.

### Release plan (tasks 33-39) — done. The release plan is done.

Opened 2026-09-16: cutting a publishable 0.1.0. Tasks 33, 34 and 35 merged
through protected, green pull requests (#41, #42, #43) the same day — 0.1.0
version cut plus a tag-triggered release workflow (33), release hygiene files
(34), and `dataprism.transport.mode=stdio` refusing unconditionally instead of
serving nothing (35). Tasks 36, 37 and 39 merged through protected, green pull
requests (#57, #58, #59) the same day — Maven Central publishing for the 12
deployable modules (36), the fixture-free distributable server image behind
gated, unpublished workflows (37), and the default-`mode=HTTP` non-web
transport fail-open closed with a new `MCP_TRANSPORT_UNAVAILABLE` code (39).
Task 38 merged through a protected, green pull request (#61) the next day —
the MCP registry entry (`server.json`, `mcp-name` marker, `publish-mcp.yml`),
which closed a defect that would have shipped a broken public onboarding
contract: a required environment variable that does not bind under Spring
Boot's relaxed map-key rules for a hyphenated prefix. See `docs/plan/HISTORY.md`
— grep `Tasks 33, 34, 35`, `Tasks 36, 37, 39`, and `Task 38` — for what
landed and what each cost, including the corrected test counts, the
fail-open's dependence on three modules' tests, the amended task 37
refusal-code criterion, and the env-var spelling defect.

With 38 closed, every task the release plan named is done.

### Task 40 — done. No task file remains under `docs/plan/tasks/`.

Opened 2026-09-16 after Maven Central 0.1.0 published: the GHCR server image
was still amd64-only, and the (unpublished) MCP registry entry points strangers
at it, a large share on Apple Silicon. Task 40 rebuilt `publish-image.yml` as a
native per-architecture matrix — `ubuntu-latest` and `ubuntu-24.04-arm`, each
verifying its own image on its own hardware before pushing by digest — with a
final job assembling the two digests into a `linux/amd64` + `linux/arm64`
manifest list. Merged through a protected, green pull request 2026-09-16 (#64).
See `docs/plan/HISTORY.md` — grep `Task 40` — for what landed, the buildx
driver bug only a real run caught, and the honest limits on what has and has
not actually been exercised yet.

**The publish sequence below is superseded by task 41's, immediately after.**
Task 40 planned to re-push the image as `:0.1.0`; task 41 reversed that
decision the next day. Left unedited above as the record of what task 40
actually closed with — do not follow it.

### Task 41 — done. No task file remains under `docs/plan/tasks/`.

Opened and closed 2026-09-17: `publish-image.yml`'s own comment (written by
task 40) still planned to re-push the GHCR image as `:0.1.0` once multi-arch
landed, on the reasoning that `main` and the `v0.1.0` tag were the same tree.
They no longer are, and for a sharper reason than drift: `git show
v0.1.0:.github/workflows/publish-image.yml` shows the tag predates the
multi-architecture pipeline entirely, so dispatching `workflow_dispatch`
against `v0.1.0` would silently re-run the old single-architecture workflow,
not task 40's matrix — `workflow_dispatch` reads the workflow file from the
ref it targets. Separately, `git diff --stat v0.1.0..main` confirms `main`
has moved: CI workflows, documentation, and `data-prism-quickstart-issuer/pom.xml`
only, no production source and no other module pom. Force-moving a tag a
published GitHub Release already points at is possible but dishonest; cutting
a patch version is cheaper. Task 41 moved the whole tree from 0.1.0 to 0.1.1
— 18 module poms plus root, both `server.json` version fields, the MCP
`serverInfo` handshake literals in `DataPrismMcpServer.java`, all four
Dockerfiles, and the stale `publish-image.yml` comment — with a `CHANGELOG.md`
entry and nothing else. Merged through a protected, green pull request
2026-09-17 (#66), which also carried the previously-unpushed `plan: task 41`
commit `main` was already sitting on. `mvn -B clean verify` green at 466
tests, the same count as before.

**Planned on a false premise, caught only at review.** The brief handed to
this task claimed `main` carried a nimbus-jose-jwt security patch reaching
the shipped server image, so an image tagged `:0.1.0` from current `main`
would ship a different JWT library than the Central 0.1.0 artifacts. That is
false: PR #55's only change is a version pin in
`data-prism-quickstart-issuer/pom.xml`; `data-prism-server` gets nimbus
transitively through `spring-security-oauth2-jose`, untouched by that PR, and
neither published image builds the issuer module. The task file (now
deleted) asserted this at its old lines 33-40 and 97-102, and required the
CHANGELOG to repeat it. The implementer wrote the false claim in faithfully,
exactly as briefed; the reviewer caught it and required a correction commit
before approving. The branch was right to contradict its own task file. This
is the second time this release cycle a reviewer has caught a false premise
supplied by planning rather than a defect introduced by an implementer — both
times the agents downstream had no way to challenge a factual claim handed to
them as context, and the verification chain is what caught it, only at the
last step. See `docs/plan/HISTORY.md` — grep `Task 41` — for the full
account.

**Superseded by the 0.2.0 publish sequence in the "Tasks 42-47" section
below.** Left unedited here as the record of what task 41 actually closed
with — do not follow it. `v0.1.1` was tagged and released, and
`publish-mcp.yml` was dispatched against it but returned 403 (see task 44
below); do not re-dispatch any `v0.1.1` step now that 0.2.0 is the version to
publish.

**Nothing left is development work** — the owner-driven publish sequence:

1. Tag `v0.1.1`. `release.yml` creates the GitHub Release from it.
2. Manually dispatch `publish-image.yml` on `v0.1.1` for the multi-arch
   manifest — the first real execution of task 40's digest-push and
   manifest-assembly steps against a ref that actually carries them.
3. Manually dispatch `publish-central.yml` on `v0.1.1` and approve the Portal
   bundle. Maven Central 0.1.0 is already published and immutable; 0.1.1 is a
   second, additional release, not a replacement.
4. Verify the manifest resolves per platform on real hardware: an x86_64 host
   should pull `amd64`, an Apple Silicon Mac should pull `arm64`.
5. Manually dispatch `publish-mcp.yml` on `v0.1.1` for the registry entry,
   which requires the image to be pullable. The reviewer confirmed
   `publish-mcp.yml`'s `docker manifest inspect` pullability guard is
   satisfied by a manifest list, so no successor task is needed there.

Owner actions outstanding, unchanged by task 41, none of which any agent can
perform:

- The `central` GitHub Environment exists but has no required reviewers
  ticked, so it currently gates nothing.
- `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD` should move from
  repository secrets to the `central` environment's scope, now that
  `publish-central.yml`'s stage job no longer references them.
- The MCP registry namespace claim (`io.github.aindriub`) happens via GitHub
  OIDC at `publish-mcp.yml` dispatch time — no separate owner action, but the
  dispatching identity must be the repository owner's.

### Dependabot PRs — closed

All twelve PRs opened since task 34's `dependabot.yml` landed (#44-#55) are
resolved; zero remain open. Eight closed without merging, each with recorded
reasoning:

- #54 (`spring-boot.version` 3.5.16 to 4.1.1) — a major version bringing
  Jackson 3 transitively; two Jackson majors on one classpath would open a
  path around the scrubbing engine, breaching rule 5.
- #44, #46, #50, #52 (Docker base image JRE 21 to 25) and #48, #49, #53
  (build image 21 to 26) — every pom deliberately targets `--release 21`,
  and separately `build.yml` never builds a Docker image, so these PRs' green
  checks were vacuous regardless.

Four merged: #55 (`nimbus-jose-jwt` patch — the dependency task 41's own task
file mistakenly credited with reaching the server image, see above) and #45,
#47, #51 (`actions/checkout`, `actions/upload-artifact`, `actions/setup-java`
version bumps).

### Tasks 42-47 — done. Second MCP tool, registry casing, a scan hardening found along the way, and 0.2.0 cut

Opened 2026-09-17. All six tasks are done, merged through protected, green
pull requests the same day: 42 (#69), 44 (#68), 43 (#71), 46 (#73), 45 (#75),
47 (#76, landed on top of 45 after main advanced under it — the expected
sequential-merge race, resolved by merging main into 47's branch before
opening its PR). No task file remains under `docs/plan/tasks/`.

**Task 42 — done. No task file remains under `docs/plan/tasks/`.**

Shipped `compare_entity_sources`, the second MCP tool: per-field agreement,
disagreement (`INCONSISTENT`/`FORMATTING_ONLY`/`ABBREVIATION`) and
`MISSING_IN_SOME_SOURCES` findings over the same correlated, scrubbed
`ContextResponse` that `get_entity_context` already builds. Argument and
response field are `subjectId`, not the spec's `idInternal` — consistency with
the shipped tool an MCP client sees alongside it. `identity` carries
pseudonymised values copied verbatim from the scrubbed tree, never raw.
Agreement is computed pre-scrub, in `NamespaceCorrelationService`, because two
genuinely different values collapse to the same pseudonym after scrubbing and
become indistinguishable from two identical ones — recorded as an amendment to
§42 in `docs/design-review.md`. Both `ContextRequest` and `ContextResponse`
gained record components on a record type already published to Maven Central
(0.1.0 and 0.1.1, immutable); both got explicit constructors preserving the
old canonical-constructor descriptor, checked twice with `javap` against the
published jar rather than assumed safe. Full reactor `mvn -B clean verify`
green, 483 tests (466 baseline, +17). Merged 2026-09-17 (#69). See
`docs/plan/HISTORY.md` — grep `Task 42` — for what landed and what it cost,
including a defect three of the task's own tests missed and why.

**Task 44 — done. No task file remains under `docs/plan/tasks/`.**

Corrected the MCP registry namespace to `io.github.AindriuB/data-prism`,
matching the casing the registry actually grants for the GitHub login — the
server declared the lowercase `io.github.aindriub/data-prism` and
`mcp-publisher publish` returned 403 against it. Fixed in the three strings
that carry the name (`server.json`, `README.md`'s marker,
`docker/distribution/Dockerfile`'s label) and nowhere else; Maven Central's
`io.github.aindriub` coordinates are a different, correctly-lowercase
identifier and are untouched. Added a cross-file guard to `publish-mcp.yml`
that fails the workflow if the three strings drift apart again, proven
non-vacuous by the reviewer independently. Merged 2026-09-17 (#68). See
`docs/plan/HISTORY.md` — grep `Task 44` — for what landed and what it cost.

**Does not clear the 403 on its own.** The MCP label is baked into the GHCR
image at build time, so the corrected namespace takes effect only once
`publish-image.yml` rebuilds and re-pushes the image under 0.2.0 (task 45,
then an owner-dispatched `publish-mcp.yml` run). `mcp-publisher publish`
failed 403 against `v0.1.1` during this wave and nothing was published.

**Task 43 — done. No task file remains under `docs/plan/tasks/`.**

Put `compare_entity_sources` on the real assembly — real stub adapters, the
real `JsonTreeScrubbingEngine`, real audit — and proved no raw fixture value
reaches its output. Settled the assumption task 42's tests could only assert,
never test: the real scrubbed tree is keyed by
`FieldMetadata.fieldName()` (e.g. `customerName`), not by the namespace
constant (`PERSON_NAME`), proven against the real engine via
`DataPrismAssembly.standard()`, not a stub — and confirmed by mutation, not
argument: flipping `JsonTreeScrubbingEngine`'s SYNTHESIZE case to pass raw
values through reddened two of three end-to-end tests, reverted
byte-identical. Pinned the shipped example role in all three places it is
granted (`ExampleApplication`, `DataPrismAssembly`, `application.yaml`), where
previously only the two Java factories were pinned. Merged 2026-09-17 (#71).
Full reactor `mvn -B clean verify` green, 487 tests. See `docs/plan/HISTORY.md`
— grep `Task 43` — for what landed and what it cost, including the PII-scan
weakness it surfaced (now task 46) and a third instance this cycle of a
downstream agent correcting a coordinator's factual premise.

**Task 45 — done.** Cut version 0.2.0 across the reactor — 19 module poms plus
root, `server.json`, the MCP `serverInfo` literals, four Dockerfiles,
`README.md`, three PackagingIT/SmokeIT tests — plus a `CHANGELOG.md` entry
checked claim-by-claim against tasks 42-44's diffs, and a coordinator-
authorized two-line fix to two stale `0.1.1` literals in
`publish-image.yml` that a `--hidden` sweep found and a default `rg` sweep
never could have. Merged #75, 488 tests unchanged. See `docs/plan/HISTORY.md`
— grep `Task 45` — for what landed and what it cost, including the sweep
blind spot every prior version-literal sweep in this repository shared.

**Task 46 — done.** `PiiLogScanTest`'s banned values derived from the stub
adapters' own fixtures instead of a hand-maintained list. Merged #73, 488
tests. See `docs/plan/HISTORY.md` — grep `Task 46`.

**Task 47 — done.** Closed the two holes a reviewer found in the control task
46 fixed: the banned-value derivation now recurses through `Record`
components and `Collection` elements to arbitrary depth instead of stopping
one level deep, and `findLeaked`'s bounds moved from `\b` to `(?<!\w)`/`(?!\w)`
lookarounds so a banned value ending in punctuation (both order notes) can no
longer pass unmatched on an ordinary log line. `OrderDto` gained one nested
`DeliveryDto` component so the recursion is falsifiable against a real
fixture. Both holes proven by mutation, and the hex-collision defence the old
bound existed for was pinned before it was replaced. Merged #76, 492 tests.
See `docs/plan/HISTORY.md` — grep `Task 47`.

With 42-47 all closed, no task file remains under `docs/plan/tasks/`.

**Task 48 — done. No task file remains under `docs/plan/tasks/`.** The
0.2.0 publish sequence's registry-dispatch step (step 5 below, as it read
before this update) returned 400: OCI packages must not carry
`registryBaseUrl`, and `identifier` must be a canonical reference. Task 48
corrected `server.json`'s package block and extended `publish-mcp.yml`'s
version-agreement guard to a moved `v0.2.0` tag. Merged 2026-09-17 (#78).
See `docs/plan/HISTORY.md` — grep `Task 48` — for what landed, including why
the fix was found by reading the registry's server-side validator rather than
the error text, and why the tag had to move.

### 0.2.0 release — done. Every artifact is live.

Closed 2026-09-17. GitHub Release v0.2.0, Maven Central 0.2.0 (all modules),
the GHCR multi-arch image (verified `arm64` and `amd64` from the same tag on
real hardware), and the MCP registry entry
(`io.github.AindriuB/data-prism@0.2.0`, status `active`, published
2026-09-17T18:59:49Z) are all published. The registry entry is the first
successful publish after two failed attempts (403 namespace casing, fixed by
task 44; 400 forbidden OCI fields, fixed by task 48). See
`docs/plan/HISTORY.md` — grep `0.2.0 release complete` — for the full account
of both failures, why a green local `mcp-publisher validate` did not predict
either, and why the `v0.2.0` tag was force-moved to the PR #78 merge commit
without misrepresenting any already-published artifact.

Owner actions outstanding, unchanged by this wave: the `central` GitHub
Environment still has no required reviewers ticked, and
`CENTRAL_TOKEN_USERNAME`/`CENTRAL_TOKEN_PASSWORD` still live as repository
secrets rather than the `central` environment's scope — see task 41's section
above for the original recording of both.

### Tasks 49, 50, 51 — done

Opened 2026-09-17 after an architect established that `data-prism-example`
was never an example — it is the reactor's integration test suite, hosting
`PiiLogScanTest`, the sole enforcement of privacy rule 7, and a name that
invited deletion. Task 49 renamed it to `data-prism-integration-tests`
(`git mv`, nothing removed), proving by mutation and instrumented printing —
not by a green run alone — that `ArchitectureCoverageTest`'s dynamically
computed module list still saw the renamed module, since that class's own
javadoc records a prior silent narrowing from exactly this kind of miss.
Six `data-prism-example` strings (the JWT issuer and audit writer-id config
values, and the tests asserting on them) were left alone deliberately:
changing them changes observable audit output. Tasks 50 and 51 shipped the
first consumer guides this repository has had, `docs/extending.md` and
`docs/tools.md`, built entirely from real captures rather than transcribed
examples. Merged through protected, green pull requests 2026-09-17 (#80,
#82, #81 — the sequential-merge race hit twice, resolved each time by a
clean `git merge origin/main`). Post-merge full-reactor re-run:
`BUILD SUCCESS`, 0 failures, 0 errors. See `docs/plan/HISTORY.md` — grep
`Tasks 49, 50, 51` — for what landed and what it cost, including the
copyable-snippet lesson from task 50's pom block.

### Task 52 — done. No task file remains under `docs/plan/tasks/`.

Reconciled the documentation this wave left stale or newly-relevant.
The most consequential fix: `README.md` had said "Until Task 20
delivers…" the configuration-driven JSON REST mode since before that task
shipped — a stranger reading it would be told to write a Java adapter when
a configuration-only path may serve them. Corrected from the code, not the
old claim: `data-prism-connectors-rest` carries no `maven.deploy.skip`, so
it is a normally published, self-registering artifact needing no Java from
an operator, and `ConfiguredJsonFieldMetadataResolver.descendable()`
returns `false` unconditionally, so it covers only flat JSON — scalar
fields and arrays of them, never nested objects. `README.md` and
`docs/extending.md` now agree, both written from the code independently.

The task was planned against four stale "only one tool ships" claims; the
count grew twice under examination. A reviewer found a fifth during task
51 (`docs/agents/stdio.md` claiming the fixture caller holds only
`GET_ENTITY_CONTEXT`), and the executing scribe found a sixth
independently — `docs/architecture.md`'s Deployment paragraph calling
configuration-driven JSON sources "deferred", the same defect as the
README line. All six are corrected, using tool names taken from a real
`tools/list` response captured by driving the compiled fixture over a
FIFO stdio session, not inferred from `Capability.java`. The task file's
own cited line numbers were stale in three places, including
`docs/quickstart.md:81`, which named a section header rather than the
claim — the real one was at `:113`; re-derived with `rg -n` rather than
trusted.

A first pass wrote that both tools appear in `tools/list` "because this
fixture's caller holds both capabilities", implying the tool list is
capability-filtered. It is not: both tool specs register
unconditionally, which is exactly why the Compose quickstart's caller can
list `compare_entity_sources` and not call it. Caught at review before
merge, since the false causal link contradicted both `docs/quickstart.md`
and the grant-before-call rule `docs/tools.md` teaches. That grant-before-
call behaviour is now stated rather than left to surprise a reader:
`docker/server/application.yaml` grants the quickstart's `investigator`
role only `GET_ENTITY_CONTEXT`, so a new user following the Compose
quickstart sees both tools listed and gets `TOOL_NOT_PERMITTED` calling
the second.

Also carried: every remaining `data-prism-example` reference across the
owned files renamed and re-characterised as the reactor's integration
test suite; `ArchitectureTest`'s ownership re-pointed to
`data-prism-architecture`; `docs/extending.md` and `docs/tools.md` linked
from `README.md`, `docs/quickstart.md` and `docs/agents/README.md`. Merged
2026-09-17 (#84). See `docs/plan/HISTORY.md` — grep `Task 52` — for what
landed and what it cost, including the line-number-staleness lesson.

### Known open items at release, none scheduled

Recorded 2026-09-17 alongside the 0.2.0 release close-out, so they are found
in one place rather than scattered across dated `/verify` findings below.
None blocks anything already shipped; pick any up only as its own planned
work.

- The two latent `PiiLogScanTest` traps from task 47's `/verify` — see
  "Small open items, unscheduled" below (dated 2026-09-17, found on task 47)
  for both.
- Nothing asserts on the MCP `serverInfo` handshake version string — see the
  same "Small open items, unscheduled" section below (dated 2026-09-17, found
  on task 41) for the detail.
- Three MCP tools named across this plan and never built.
  `compare_entity_sources` and `get_entity_context` are the only two shipped.
  `search_entity_data` has a full spec, `pack.md:1475-1499` (§43): signature
  `search_entity_data(entityType, idInternal, query)`, with the explicit
  constraint that server-side policy must translate the query rather than
  passing any backend query language (Elasticsearch DSL, SQL, JPQL, Mongo)
  through to the LLM. `describe_entity_model` is named in this file's Someday
  list (S11) and in `docs/design-review.md:175-179` (§B5), but neither gives
  more than intent — a one-paragraph amendment saying it should return field
  names, namespaces and which fields are pseudonymised or redacted, with no
  parameter list, response shape or error codes. `describe_entity_model`
  needs a spec written before a task file for it would be buildable;
  `search_entity_data` does not.
- The append-only audit sink and its hash-chain verifier are still missing.
  The hash chain itself exists and is exercised (`AuditRecorder`,
  `data-prism-core/src/main/java/.../audit/`), but the only shipped
  `AuditSink` is `Slf4jAuditSink` — there is no durable sink and nothing
  verifies a stored chain for tampering. S9 as scoped in "Someday" below
  named both the chain and "append-only sink"; only the cheap half (real
  principal in audit, metrics, a PII log scan able to fail) was ever picked
  up, by task 07 on 2026-09-09. The durable sink and its verifier were never
  scheduled as their own task and remain outstanding. Listed here because the
  release makes it visible to anyone integrating this as a dependency, not
  because it is newly found.

Added 2026-09-17, out of task 52's wave. Neither blocks anything; pick either
up only as its own planned work.

- The external consumer demo at
  `/Users/Andrew/workspace/data-prism-github-demo` is not a public
  repository — `github.com/AindriuB/data-prism-github-demo` returns 404 —
  so `docs/extending.md` deliberately references no worked external
  example. If the owner publishes it, the adapter guide would be improved
  by linking a complete example against a real public API.
- `docs/agents/**` documents only one verified MCP client, the Claude Code
  CLI. Now that two consumer guides exist (`docs/extending.md`,
  `docs/tools.md`) and the server is listed on the MCP registry, other
  clients (any other MCP-capable agent) are unverified territory — nothing
  claims they work, but nothing has checked either.

### Wave 1 (tasks 53, 54, 56, 57) — done. Task 55 verified but held.

Opened from a measured UX review 2026-09-21: the Compose quickstart demos
well but onboards nobody, since `QuickstartCustomerAdapter` hardcodes
`SOURCE_NAME="customer"` and `CustomerModel.class` into the image, and the
configuration-driven REST connector (task 20) had no `IdentityResolver`
supplied anywhere, so it was not actually no-code. Task 53 adds opt-in
`dataprism.identity.resolver: pass-through`; task 54 collapses the
duplicate `dataprism.sources` base-url check; task 56 extracts a
one-command demo out of `smoke-test.sh` (its first attempt failed
verification — see `docs/plan/HISTORY.md`, grep `Wave 1 (tasks 53, 54, 56,
57)`, for why); task 57 renders every configuration refusal as an
operator-facing block with no stack trace. All four merged locally
2026-09-21, no conflicts. See `docs/plan/HISTORY.md` — grep `Wave 1 (tasks
53, 54, 56, 57)` — for what landed and what it cost.

**Task 55 (publish the quickstart images) is verified PASS/APPROVE and
deliberately not merged.** Held: task 55's `compose.yaml` pulls
`ghcr.io/aindriub/data-prism-quickstart-{server,fixtures,issuer,certs-init}`,
none of which are published yet — merging it would break `docker compose
up` (the command both `README.md` and `docs/quickstart.md` tell a new user
to run) for everyone until a `v*` tag is pushed and `publish-image.yml` is
dispatched. Owner decision: publish the images first, then merge 55. Its
branch (`task/55-publish-quickstart-images`) and worktree
(`.worktrees/data-prism/55-publish-quickstart-images`) are left intact; its
task file remains under `docs/plan/tasks/`. Unblock condition: a `v*` tag
exists and `publish-image.yml` has been dispatched for the four quickstart
images, at which point 55 can merge as-is.

### Task 58 — done. No task file remains under `docs/plan/tasks/`.

Shipped `docs/protect-your-own-api.md`, a YAML-only walkthrough taking a
reader with a flat JSON REST API to a pseudonymised MCP response without
writing Java — `data-prism-connectors-rest` plus
`dataprism.identity.resolver: pass-through` (task 53) and the
single-statement transport (task 54). Publishes the former test-only
catalogue as `examples/json-sources/customer-api.yaml`, fully commented,
and repositions `docs/extending.md` from the front door to the escape
hatch for nested responses, custom fetch logic and models a flat
catalogue cannot express. Merged 2026-09-22 (`bfabcb1`). See
`docs/plan/HISTORY.md` — grep `Task 58` — for what landed and the six
verification attempts it cost, including two lessons worth carrying
forward: a documentation task needs both a literal-reader tester and a
rules-reading reviewer, since neither method alone would have caught what
the other did; and this is the second task this wave (after 56) to assume
a bare JSON body from an endpoint that actually negotiates SSE, caught
only by exercising the real server both times.

**Task 59 has not started, blocked on 55, and is now also blocked on the
v0.3.0 wave below.** Task file exists
(`docs/plan/tasks/59-quickstart-exit-ramp-and-reference.md`), unedited.
Its acceptance criteria already require documenting
`dataprism.identity.resolver` in `docs/configuration.md`, which task 58
found undocumented there despite being the property the whole no-code
path depends on. Recorded here because 59 has not started, so its
criteria are not frozen: it must gain two more items before it starts,
on top of that one — the nested-catalogue grammar reference in
`docs/configuration.md` (task 60) and the `dataprism.audit.sink:
hash-chained` property plus its file-path property (task 67). Do not
edit the task file to add these until 59 is actually picked up; this is
the planning record, not a criteria change in flight.

### v0.3.0 plan (tasks 60-71) — opened 2026-09-22, none started

Thesis: "protect a real API, without Java, and prove what happened."
Follows from the 2026-09-21/22 onboarding work (wave 1 above and task 58,
both closed) plus two architect spikes run 2026-09-22, both owner-confirmed,
whose conclusions this section records because they shape every task below.
Task files for all twelve exist under `docs/plan/tasks/` (60-71); none has
been edited to add anything beyond what is recorded here.

**Nested JSON — bounded, and why.** One level only: named sub-catalogues
declared in the same YAML file, no dotted paths, no JSONPath, no wildcard
descent, no inferring structure from the wire. `subject-json-path` is
untouched; nested objects never carry their own subject. The reasoning that
unlocked this after task 20 shipped only a flat catalogue: the
reviewed-adapter boundary is "nobody may assert a classification without a
reviewed artefact behind it", not "the artefact must be a compiled Java
class" — the YAML catalogue already is that artefact for the flat case, and
a named sub-catalogue applies the same review surface once more. Arbitrary
depth or JSONPath addressing is the arbitrary-JSON-mapping outcome
`docs/architecture.md:104-113` rejects and must not be built at any
increment size. Owner decision: the descend-key mechanism stays entirely
inside `data-prism-connectors-rest` — core's `FieldMetadataResolver` and
`FieldMetadata` do not grow a second string-keyed resolution path, because
that SPI is shared with the Java-first path that works correctly today and
must not carry a concept only one implementation uses. This is why 60 and
61 are both needed rather than one task: `ConfiguredJsonScrubbingEngine`'s
raw-value leak check (`SourceValues.prohibited`, `:93`) must walk the same
nested structure the engine now descends, or it silently stops covering
nested fields — a fail-open, with no annotation-processor backstop for YAML
the way there is for Java models — and 61 is what exercises that through
the real MCP transport rather than trusting the parser alone.

**Audit — a file sink and an offline verifier, not an endpoint.** Ship a
single-file, append-mode, fsync-per-record `FileAuditSink` with no rotation
(rotation is operational, not a correctness question) wired via the
already-reserved `hash-chained` property value, plus an offline verifier
CLI — deliberately not an Actuator endpoint and not a startup check,
because an in-process endpoint conflates "the running instance says its own
log is fine" with independent verification. Owner decision on what it
claims, binding on every task and document in this wave: the verifier
cannot detect truncation of the most recent records. Deleting the tail of
an append-only file leaves a chain that verifies perfectly end to end, and a
crash mid-write is indistinguishable from a malicious truncation from
inside the file. Detecting that needs an external checkpoint held outside
the operator's control, which v0.3.0 does not build. The verifier must
print that limitation on every run, and the documentation must state that
tamper-evidence here means intra-writer edit/delete detection, with durable
append-only-ness left an operator responsibility (`O_APPEND`, WORM, object
lock). No task in this wave may produce a doc or output string claiming
more. Do not ship a verifier over `Slf4jAuditSink` output — log
infrastructure reorders, compresses and ships lines outside this
application's control.

Two live bugs the audit spike found in existing code, now owned by 63 and
67 respectively:
- `AuditRecorder.java:59-60` advances `previousHash` before `sink.record(event)`
  succeeds, so a throwing sink leaves the in-memory chain head past an event
  never durably written; the next successful write chains against a hash for
  a record that does not exist. Harmless while the only sink is SLF4J; a
  real corruption path the moment a durable sink exists.
- `DataPrismProperties.java:167-170` validates and accepts `approved-sink`
  and `hash-chained` with no bean behind either, so an operator configuring
  `sink: hash-chained` passes config validation and only hits
  `MISSING_AUDIT_SINK` at a later startup phase — accidental fail-closed via
  a missing bean, not designed fail-closed.
- Also worth recording: no call site wraps `audit.record(...)`, so a sink
  exception aborts the response — accidentally fail-closed today, matching
  rule 2; task 63 turns that into an explicit tested guarantee rather than
  leaving it accidental. Catch-and-continue would be the actual rule 2
  violation, and must not be introduced while fixing the ordering bug.

**Owner decision, recorded 2026-09-23: the audit trail's threat model is a
user, an operator, AND any LLMs.** Recorded in `docs/architecture.md`'s
decisions list (grep 2026-09-23) with the rejected alternative (an HMAC-keyed
chain); this entry carries the consequences, which change what this wave
does and does not deliver:
- The operator is in scope, and the design as built does not resist one.
  `AuditEventHash.compute` is unkeyed SHA-256, so anyone who can write the
  audit file can delete or alter a record and recompute every hash after it
  into a chain that verifies perfectly — demonstrated by injecting a
  fabricated writer with a self-computed `eventHash`. Closing it needs
  external checkpointing or asymmetric signing with the key held outside the
  writing process; neither is a v0.3.0 increment. Not scheduled; the owner
  has not yet said when.
- Task 66's verifier (merged 2026-09-23) prints its truncation-cannot-be-
  detected disclaimer on every run; that disclaimer is load-bearing against
  a stated requirement, not a nice-to-have caveat. Task 72 (merged
  2026-09-23) touched this file and did not weaken it — the disclaimer's
  wording is unchanged; only the field-count and the backdating claim it
  makes were corrected.
- Tasks 62 and 59 write the audit-facing documentation. Neither may state or
  imply that `hash-chained` resists an operator with write access to the
  audit file, in addition to the existing path-disclosure precondition
  (follow-up item 2 above). This still holds after task 72: the operator gap
  above is unchanged by it.
- Timestamp integrity was forensically central, not cosmetic: tracing when an
  exposure happened depends on a timestamp that cannot be rewritten without
  recomputing a hash. `AuditEventHash` used to exclude `timestamp` and
  `sourceSystems` from the joined body it hashes; task 72 (merged 2026-09-23)
  closed that, widening the join to nineteen fields. This item is done.
- Open question, not yet decided by the owner: the trail records
  `subjectPseudonym`, `parameterFingerprint`, `tool`, `sourceSystems` and the
  policy decision — enough to reconstruct what would have been returned,
  deterministically, without storing the payload. That ties tracing a
  historical exposure to the pseudonymisation being reproducible, which
  depends on the HMAC key and `PseudonymisationVersion` staying stable — a
  key rotation or version bump could break the ability to trace an old
  exposure. Confirm with the owner whether that coupling is intended.

**Owner decision, recorded 2026-09-22: pseudonym discriminator widens from 20
to 40 bits; `PseudonymisationVersion.version` stays at v1.** Task 71
(`docs/plan/tasks/71-widen-pseudonym-discriminator.md`) widens the
pseudonymisation discriminator from 20 bits to 40, because at 20 bits two
subjects in one scope collide with 50% probability at roughly 1,205 subjects
for the namespaces where the discriminator tag is the whole rendered
pseudonym (`EMAIL`, `NONE`, and the default branch), and at roughly 400
subjects for `ADDRESS`, which today carries no tag at all — `ADDRESS` gains
one as part of this change. `NONE` is the scope-local subject token audit
records in place of the real identifier, so a collision there means two
different subjects share one audit identity. Widening the discriminator
changes every namespace's rendered pseudonym, so both golden-vector files —
`data-prism-pseudonymisation/src/test/resources/golden-vectors-v1.tsv` and
`golden-vectors-western-v2.tsv` — are regenerated as part of this task, which
is why this entry exists: `docs/conventions.md`'s determinism-test rule
requires either bumping `PseudonymisationVersion.version` or a recorded
decision to hold it, cited by the diff that edits the vectors.
`PseudonymisationVersion.version` deliberately stays at `v1` rather than
bumping to `v2` — the owner's reasoning is that nothing durable depends on v1
output yet, so a version bump would buy nothing a wider discriminator does
not already deliver. The explicit cost, stated per the rule: any pseudonym
issued before this change will not reproduce afterwards. Task 71's own commit
body must cite this entry rather than restate the reasoning.

Two smaller items the planner surfaced reviewing task 71, neither blocking it:
- Task 71's new `PseudonymisationVersion` compact-constructor check rejects
  an algorithm value a consumer of the published `data-prism-core` record
  could previously construct successfully. That is a breaking change to a
  public API arriving in 0.3.0 and wants a `CHANGELOG.md` line; task 70 owns
  `CHANGELOG.md` and should carry it when it cuts the version.
- Task 71's collision test runs the generator roughly 80,000 times, with the
  subject count as its tuning knob. Below roughly 5,000 subjects the mutation
  proof stops being decisive for the 2^20 case — worth knowing before anyone
  is tempted to shrink the loop count for speed.

**Waves, in order:**
- **Wave 1 — no cross-dependencies:** 60 (nested JSON catalogues), 63 (audit
  chain write ordering), 64 (file audit sink), 68 (bean classification
  escape hatch — closes the task-53-review item above: the
  `dataPrismPassThroughIdentityResolver` row plus the sweep widened to
  nested/imported configs, and the `DataPrismAutoConfiguration:117-126`
  javadoc correction), 71 (widen the pseudonym discriminator from 20 to 40
  bits, per the owner decision recorded above).

  **Wave 1 is complete — 60, 63, 64, 68 all merged.** 63, 64, 68 merged
  locally 2026-09-22; task 64's task file was retired with its attempt-1/
  attempt-2 failure records mined into `docs/plan/HISTORY.md` — grep `v0.3.0
  wave 1` — before deletion. **60 merged 2026-09-22 at attempt 3** (fixed a
  fail-open on a scalar arriving where the catalogue declared `nested:`,
  then a non-deterministic slot ordinal assigned from `Map.copyOf` iteration
  order, now a pure function of the sorted catalogue-name set) — its task
  file was retired with both failed attempts mined into
  `docs/plan/HISTORY.md`, grep `Task 60`. Post-merge full-reactor
  `mvn -B --no-transfer-progress clean verify`: BUILD SUCCESS, 19 modules,
  1018 tests, 0 failures, 0 errors.

  **Task 71 (widen the pseudonym discriminator) belongs to wave 1 and has
  not been started.** Its task file exists at
  `docs/plan/tasks/71-widen-pseudonym-discriminator.md`, merged via PR #87
  together with the `docs/conventions.md` amendment its golden-vector
  criterion cites.

- **Wave 2 — depends on wave 1, now unblocked:** 61 (nested JSON through the
  real MCP HTTP/SSE transport, deps 60 — unblocked now that 60 has merged),
  65 (file sink PII scan, deps 64 — unblocked), 66 (audit chain verifier CLI,
  deps 64 — unblocked, and see the "four tampering-shaped failure modes"
  note above and in `docs/plan/HISTORY.md`'s wave-1 entry before starting),
  67 (wire `hash-chained` to `FileAuditSink`, deps 64 and 68 — unblocked, and
  see follow-up item 6 below on the path-disclosure fix that belongs where
  the sink exception maps to an MCP response, not in the sink).

  **61 and 65 merged 2026-09-22, both PASS + APPROVE.** 61's task file was
  retired; 65 took two attempts (see `docs/plan/HISTORY.md`, grep `v0.3.0
  wave 2`, for both). **66 merged 2026-09-23 at attempt 4, PASS + APPROVE** —
  three earlier attempts each relocated the same defect (the tool asserting
  benignity it could not support); task file retired, mined into
  `docs/plan/HISTORY.md`, grep `Task 66`. **67 was PASS but REQUEST CHANGES
  for bookkeeping, not code** — its branch and worktree were held pending
  tasks 73 and 74 (the approved-sink refusal, and the sink-exception-to-MCP-
  response path disclosure) being filed and closed.

  **Tasks 73 and 74 both merged 2026-09-23, PASS + APPROVE, onto
  `v0.3.0/audit-trail-and-nested-json`.** Task 73 raises a new
  `AUDIT_SINK_BEAN_REQUIRED` code, naming
  `dataprism.audit.sink=approved-sink` and the required bean, when that
  specific configured value has no `AuditSink` bean at
  `dataPrismContractValidator` construction; `MISSING_AUDIT_SINK` stays for
  every other case reaching the same branch, deliberately not narrowed to
  assume `approved-sink` is the only value that can, since `hash-chained`
  reaches it too until 67 merges. Task 74 stops a throwing `AuditSink`'s raw
  exception message reaching the MCP client: `GetEntityContextTool` and
  `CompareEntitySourcesTool` now catch only the `audit.record(...)` failure
  and rethrow `AuditUnavailableException` (code `AUDIT_UNAVAILABLE`), with
  the caught exception attached via `addSuppressed` rather than as a cause —
  proven by mutation that restoring a normal cause lets the MCP SDK's
  `aggregateExceptionMessages` walk it back onto the wire, disclosing a real
  `FileAuditSink` path. See `docs/plan/HISTORY.md`, grep `Task 73` and
  `Task 74`.

  **Both of task 67's held-pending conditions are now closed — task 67 is
  unblocked.** Its branch and worktree (`task/67-wire-hash-chained-sink`,
  `.worktrees/data-prism/67-wire-hash-chained-sink`) can proceed to merge and
  be recorded done; no further filing is owed.

  **Task 72 (widen the audit hash to cover `timestamp` and `sourceSystems`)
  merged 2026-09-23, PASS + APPROVE, fast-forward onto
  `v0.3.0/audit-trail-and-nested-json` at `43badb8`.** It landed ahead of 67 as
  required — no durable chain existed yet to invalidate, since 67 (which wires
  `hash-chained` to `FileAuditSink`) had not merged. That ordering constraint
  is now satisfied; 67 may merge without invalidating anything 72 wrote. Task
  file retired; see `docs/plan/HISTORY.md`, grep `Task 72`.

  Task 60's durable caveats, load-bearing for 62 and 69: the fail-open is
  fenced by `ConfiguredJsonNestedLeafShapeGuard` running before
  `engine.scrub`, not structurally removed — any future path that hands a
  `ConfiguredJsonFieldMetadataResolver` to `JsonTreeScrubbingEngine` without
  calling the guard first reopens it; ordinals are per-source and assigned
  over sorted catalogue names, so task 69 must read names off
  `nestedCatalogues()` and never re-derive slots; core's `UNKNOWN_FIELD`
  message interpolates the slot class name
  (`ConfiguredJsonNestedCatalogueSlot0`), not the operator's catalogue name —
  task 62 owns documenting the slot-to-catalogue mapping.
- **Wave 3 — depends on waves 1-2, now unblocked (66 merged 2026-09-23):**
  62 (corrects
  `architecture.md`'s flat-by-design and boundary-7 claims, new
  `docs/audit.md`, nested example and walkthrough; deps 60, 64, 66), 69
  (restore the reviewed-adapter allow-list task 54's review flagged above,
  via a catalogue-names bean from the connector, without reinstating the
  exact-match duplication task 54 removed; deps 60, 67). 62's hard
  precondition — the sink-exception-to-MCP-response path disclosure — is now
  closed by task 74 (merged 2026-09-23); 62 and 59 may document
  `hash-chained`. One new item is owed instead: `docs/configuration.md` has
  no entry for `AUDIT_SINK_BEAN_REQUIRED` (task 73), so
  `DataPrismConfigurationFailureAnalyzer`'s pointer at that document is
  currently a dead end for an operator who hits the refusal. See follow-up
  item 8 below, filed for 59/62.
- **Wave 4:** 70 (cut 0.3.0 across poms, `server.json`, `serverInfo`
  literals, four Dockerfiles, `publish-image.yml`, docs, with a CHANGELOG
  built from the merged diffs; deps 61, 62, 63, 65, 66, 67, 69).

**Risks flagged by the planner, both open:**
- The per-nested-catalogue `Class` token task 60 introduces is the only
  unproven mechanism in the plan. If no route keeps `core` unchanged, 60
  stops and reports rather than widening core's SPI.
- Task 69 sits behind two dependency edges on one file (60, then 67); a slip
  in 67 delays the allow-list fix, not the release, since 69 is wave 3 and
  70 waits on both.

**Follow-ups filed from wave 2 (61, 65, 66, 67), 2026-09-22. Items 1 and 2 are
resolved below (tasks 73 and 74, merged 2026-09-23).**

~~1. **Blocks recording task 67 done.** `approved-sink` is still accepted by
   `DataPrismProperties.validate()` with no bean behind it, refusing later
   via `MISSING_AUDIT_SINK` at contract-validator construction instead of at
   config validation. ... File it as its own task owning those files.~~
   **Resolved 2026-09-23 by task 73.** `DataPrismContractValidator` now
   raises `AUDIT_SINK_BEAN_REQUIRED`, whose message names
   `dataprism.audit.sink=approved-sink` and the required bean type, for
   exactly that value with no bean; `MISSING_AUDIT_SINK` is retained for the
   absent-or-blank-property case and for any other accepted value reaching
   the bean-absent branch (still `hash-chained`, until 67 merges). All eight
   named fixtures still configure `approved-sink` and still exercise the
   path they were written for. See `docs/plan/HISTORY.md`, grep `Task 73`.
2. ~~**Hard precondition on tasks 62 and 59.** The sink-exception-to-MCP-
   response path disclosure: `FileAuditSink`'s `PoisonedException` names the
   file path by design, and `AuditSinkFailureAbortsResponseTest` pins a
   sink's raw exception message reaching the MCP client, so a server
   filesystem path can now reach a client. ... on condition that this
   is filed before either 62 or 59 tells an operator to use `hash-chained`.~~
   **Resolved 2026-09-23 by task 74.** `GetEntityContextTool` and
   `CompareEntitySourcesTool` catch only the `audit.record(...)` call sites
   and rethrow `AuditUnavailableException` (code `AUDIT_UNAVAILABLE`, no
   text derived from the caught exception), with the cause kept only via
   `addSuppressed` for the server-side log — proven by mutation that a plain
   `initCause`/constructor-cause wiring lets the MCP SDK's
   `aggregateExceptionMessages` disclose the real `FileAuditSink` path
   through the client-visible `McpError` `data` field. The abort itself is
   unchanged: every new `catch` logs and rethrows, none returns a result.
   `docs/conventions.md` records the resulting `LOG.error(msg, cause)` calls
   as a deliberate, reviewed exception to its own "no catch block logs the
   object it caught" rule. See `docs/plan/HISTORY.md`, grep `Task 74`.
3. The two PII scans have already drifted. `PiiLogScanTest` matches banned
   values at token boundaries (lookaround-bounded since task 47, so bare ids
   `123`/`456` cannot collide with hex); `AuditFilePiiScanTest`'s copy uses
   plain `String.contains`. Stricter today so it cannot miss a leak, and the
   derivations are byte-identical — but two definitions of "what counts as a
   leak" will drift further. Share one derivation and one matcher; needs
   `PiiLogScanTest`, which task 65 did not own.
4. `AuditFilePiiScanTest`'s `leaksIn` dispatches `instanceof String` /
   `instanceof Collection<?>` / else-throw, so a `null`-valued `String`
   component falls to the else branch and NPEs on `value.getClass()` rather
   than being skipped. Found independently by both 65's reviewer and
   tester. Latent — production uses `""` sentinels — but `AuditRecordFormat`
   round-trips nulls by design, so the path exists.
5. `ConfiguredJsonNestedHttpTest` (task 61) leaves a non-daemon thread
   (likely its per-test `java.net.http.HttpClient`), so
   `data-prism-integration-tests` now ends with surefire's "going to kill
   self fork JVM ... 30 seconds after System.exit(0)" — about 30 seconds and
   an ERROR line added to every build. A baseline run on `main` does not
   produce it. Reusing or closing one client fixes it.
6. `data-prism-integration-tests/pom.xml` (task 61) introduces a second TLS-
   password env var with the same literal as the existing
   `DATA_PRISM_TEST_TLS_PASSWORD`; reuse the existing name.
7. Two platform constraints worth recording for future tasks, both
   confirmed from source by 61's tester: `dataprism.transport.fixture-
   development` is refused unless `transport.mode=stdio`
   (`DataPrismProperties:123`), so an HTTP-mode configured JSON source must
   use a real TLS upstream; and `dataprism.json-sources.config-location`
   must be passed as a JVM system property, not a `--` command-line arg,
   because `DataPrismProperties` binds `ignoreUnknownFields=false` and
   Spring Boot's unbound-elements check exempts system properties but not
   command-line args — which is also how the packaged distribution wires it.
8. **Owed to tasks 59/62, filed 2026-09-23 alongside task 73.**
   `DataPrismConfigurationFailureAnalyzer` prints the refusal code and
   points an operator at `docs/configuration.md`. That file has no entry for
   `AUDIT_SINK_BEAN_REQUIRED` (task 73), so an operator who hits it today is
   sent to a document that never mentions the code they were just given.
   Whichever of 59 or 62 documents `dataprism.audit.sink` must add this
   code, not just `MISSING_AUDIT_SINK` and `UNKNOWN_AUDIT_SINK`.

**Release sequence — order is load-bearing, do not compress it:**
1. Waves 1-3 merge.
2. 70 merges (0.3.0 on `main`, CHANGELOG written from the real diffs).
3. Push the `v0.3.0` tag and dispatch `publish-image.yml` for the four
   quickstart images. Task 55 cannot merge before those images exist on
   `ghcr.io`, or `docker compose up` — the command both `README.md` and
   `docs/quickstart.md` tell a new user to run — breaks for everyone.
4. Merge 55 — unchanged from its held state above, now explicitly riding on
   the `v0.3.0` tag rather than getting its own release. The owner deferred
   the v0.2.1 tag decision for exactly this reason.
5. Run 59 against the published result (with the two extra criteria items
   recorded above) and merge it.
6. Maven Central and MCP registry publish. `server.json`'s shape follows
   task 48's history entry: no `registryBaseUrl`, no per-package version.

## Remaining slices past the adopted core

S10-S12 were deferred past V1 on 2026-09-09, and adoption work (tasks 14-25)
has continued since without revisiting that deferral — it closes packaging and
configuration gaps the deferred slices do not touch.

S10 was deferred past V1 by decision and the index it needs already exists. S11
is the Elasticsearch connector and Docker Compose — most of that surface is now
covered by task 18's quickstart instead, and the three divergent stub sources
it was going to build landed in S6. S12's mutation and load testing is for a
system with users.

### Small open items, unscheduled

Found across v0.3.0 wave 1 (tasks 63, 64, 68), 2026-09-22. None blocks 63,
64 or 68, all merged; several are load-bearing for the wave-2 tasks named.

1. `FileAuditSink`'s poison is per instance, so after a torn write an
   operator restarts, the new sink opens `APPEND` on the same path, and its
   first record lands directly after the fragment — recreating the
   concatenated mid-file line the poisoning exists to prevent. Closing it
   needs inspecting the file's last byte at open, which this release
   deliberately makes an operator responsibility instead. Not a defect;
   task 66's verifier and its documentation must say so rather than
   rediscover it.
2. Tasks 63 and 64's contracts now interlock: 63's rollback-on-throw and
   64's poisoning are jointly correct only because chains are per-writer
   with a fresh `instanceId` per process (`docs/architecture.md` §A6). If
   either changes, re-establish the interlock explicitly.
3. `AuditSink`'s own javadoc still says nothing about the all-or-nothing
   requirement `AuditRecorder`'s rollback implicitly imposes on any
   implementation — it lives only in `AuditRecorder`'s javadoc and task 64's
   now-deleted task file, neither of which a future sink implementor reads.
4. `AuditRecorder` catches `RuntimeException` but not `Error`; a sink
   throwing `AssertionError` or an `OutOfMemoryError` leaves the sequence
   consumed. A gap, not corruption.
5. `FileAuditSink` catches `IOException` and `RuntimeException` but an
   `Error` thrown from inside the write loop escapes unpoisoned.
6. `AuditSinkFailureAbortsResponseTest` pins the sink's raw exception
   message reaching the MCP client. With the file sink, that message would
   name a server filesystem path — a disclosure to a client in a product
   whose premise is controlling what reaches the model. Two reviewers agreed
   the disclosure originates in the propagation path that maps a sink
   exception into an MCP response, not in the sink itself. Fix it where that
   mapping lives, before task 67 wires the sink.
7. `PRIVACY_MODULES_DO_NOT_MUTATE_OBJECT_GRAPHS_REFLECTIVELY` scopes to
   `..core..`, `..pseudonymisation..`, `..validation..`, `..orchestration..`
   — `connectors` is not in scope, and its `methodHandleFieldAccess()`
   predicate names `findGetter`/`findSetter`/`findVarHandle`/`unreflect*`,
   not `defineHiddenClass`/`defineClass`. Nothing stops a future connector
   reintroducing runtime class generation the way task 60's attempt 1 did.
   `data-prism-architecture` was not in any wave-1 task's `Owns`, so this
   needs its own task.
8. Test coverage still missing for properties currently safe by construction
   but unasserted in `FileAuditSink`: an already-closed channel followed by
   a second `record()` throwing `PoisonedException`; `ClosedByInterruptException`;
   a second sink opened on a path after the first was poisoned.
9. `data-prism-integration-tests` shares a surefire fork, and
   `java.net.http.HttpClient`'s default builder eagerly calls
   `SSLContext.getDefault()`, a JVM-wide singleton cached on first use. Any
   test building a default-SSLContext client before `McpHttpEndToEndTest`
   sets its own `javax.net.ssl.trustStore` poisons the cache and breaks that
   test's self-signed JWKS handshake for the rest of the fork. Task 63
   worked around it by giving its own client an explicit non-default
   `SSLContext`, but `McpHttpEndToEndTest` still owns the shared default, so
   the module is one careless new test away from the same failure.
   Recommended fix: give `McpHttpEndToEndTest` its own explicit `SSLContext`
   and retire the trustStore property.

Found on task 66 (audit chain verifier CLI), merged 2026-09-23. Neither
blocks the merge.

10. `AuditChainVerifierCli.java:38` — the `EXIT_STRUCTURAL_ANOMALY` javadoc
    still reads "a known non-tampering structural anomaly", the exact
    phrasing the printed `--help` text dropped at attempt 3 for asserting
    benignity it could not support. Source-only, invisible to a compliance
    reader, but the same claim living on in a comment. Task 72 (merged
    2026-09-23) touched this file but not this line — still open. File it
    separately.
11. Process note, not code: attempt records appended to a task file in the
    main checkout are not visible in a worktree created earlier, because the
    worktree holds its own copy from its branch point. Task 66's worktree was
    created before its attempt-1/2/3 sections were written, and an
    implementer working from the worktree read a stale task file — a note in
    the file itself had to say "read this from the main checkout" to route
    around it. Future briefs should restate defects inline rather than
    relying on the task file being current inside a worktree, or write
    records into the worktree too.

Found on task 60 (nested JSON catalogues), merged 2026-09-22. None blocks the
merge; pick any up only if a future task already owns the file.

10. `ConfiguredJsonNestedCatalogueOrdinalDeterminismTest` asserts with
    `contains("Slot1")`, which also matches `Slot10`-`Slot15`; harmless at
    three catalogues today, but an exact suffix match would be tighter.
11. The reserved-prefix refusal (a `nonSensitive:` reason beginning with the
    nested-pointer token) is asserted only for a top-level field; a case
    inside a nested catalogue would pin both call sites.
12. The null-nested-object passthrough has no named test — a tester verified
    it with a throwaway and deleted it.

Found during `/verify` on task 58, 2026-09-22. Does not block anything; polish,
not a defect.

- `docs/protect-your-own-api.md:132, :157, :337, :351` — four verification/curl
  fences follow foreground processes without naming a terminal, unlike the
  server section, which says "a third terminal". A reader must infer a spare
  shell; no wrong output can result.

Found during `/verify` on tasks 53, 54, 55, 56, 57, 2026-09-21. None blocks
anything already merged; the first is the most important of the six.

- ~~**(53's review, most important.)** The new `IdentityResolver` bean is
  placed on a nested `@Import`ed static configuration class, which keeps it
  outside `AutoConfiguredBeanClassificationTest`'s reflection sweep.~~
  **Resolved 2026-09-22 by task 68** — see v0.3.0 wave 1 above and
  `docs/plan/HISTORY.md` (grep `v0.3.0 wave 1`).
- (54's review.) The subset relaxation lost a property nobody has restored:
  `dataprism.sources` was also the operator's allow-list, and any
  `DataSourceAdapter` bean on the classpath is now implicitly approved
  without appearing anywhere an operator reviewed. Not a fail-closed
  breach — the reviewer could not construct a misconfiguration the subset
  test lets through that exact match caught — but a real weakening of the
  reviewed-adapter posture. Owner-scheduled narrow fix: exclude exactly the
  names the JSON-catalogue mechanism supplies, via a marker or
  catalogue-names bean visible to both `data-prism-connectors-rest` and
  `data-prism-spring-boot-autoconfigure`. Task 54's own summary described
  the change as guarantee-preserving, which was stronger than warranted —
  do not repeat that framing.
- (55's review, for whoever merges 55.) `compose.yaml:12-13`'s comment
  contradicts itself — it says images are "tagged at the reactor version
  (or `QUICKSTART_IMAGE_TAG`, default `latest`)" but the default resolves
  to `latest`, never the reactor version. Also: four unguarded `--load`
  builds in `publish-image.yml` have no consumer, costing three extra Maven
  builds per arch on every plain tag push as a build-breakage smoke test
  only; and a tag pushed ahead of a pom bump fails at `COPY
  ...-${VERSION}.jar` with a raw buildx error rather than the guard's named
  message (fails closed, cosmetic).
- (56's re-review.) `run.sh:68-71` takes the first `data:` frame; if the
  server ever emits a progress or log notification before the result,
  fields come back empty and the demo fails with "missing an expected
  field" rather than parsing the frame whose id is 2. Not reachable against
  today's server and it fails closed, so a robustness nit rather than a
  defect. Also `mcp-handshake.sh:44` hardcodes `clientInfo.name` to
  `quickstart-demo`, so the smoke test now identifies itself as the demo in
  server-side logs.
- (57's review.) `ConfigurationRefusalMessageIT.java:133-138` reads process
  output only after `waitFor`, so a startup log exceeding the ~64KB OS pipe
  buffer would deadlock until the 20s timeout. Latent, not live.
- (minor, from 54's review.) `DataPrismContractValidatorTest.java:47` and
  `:78` have byte-identical bodies — one behaviour asserted twice under two
  names. That file also sits outside task 54's declared `Owns` list, but it
  was compelled by the acceptance criterion and collides with nothing, so
  this is recorded rather than treated as a violation.

Found by the reviewer during `/verify` on task 47, 2026-09-17. Neither is
reachable today; pick either up only if a future task already owns the file.

- `PiiLogScanTest`'s `EXCLUDED_FIXTURE_FIELDS` is consulted only for the
  top-level fixture record's own components, not for a nested type's. An
  exclusion keyed on a nested type (e.g. `DeliveryDto`) would be silently
  ignored — accepted by whatever registers it, applied to nothing — with no
  signal to whoever wrote it. Not reachable today because no nested type
  currently needs an exclusion.
- A `null` leaf in a fixture record would enter the derived banned-value set
  as the literal string `"null"`, which would almost certainly false-positive
  against ordinary log prose (`if (result == null) log.warn(...)` and
  similar are common). No fixture is null today, so this has not fired.

Found during `/verify` on task 41, 2026-09-17. Does not block anything; not
picked up by task 41 because moving the version bump's own criterion is a
different-shaped change than making one.

- Nothing asserts on the MCP `serverInfo` handshake version string
  (`DataPrismMcpServer.java:86,127`). It is the one value every MCP client
  reads on `initialize`, and today it is corroborated only indirectly by
  packaging tests that check jar paths, not the string a client actually
  sees. A future version bump could leave it stale and every existing check
  would still pass.

Found during `/verify` on task 35, 2026-09-16. Neither blocks anything; the
reviewer judged both safe to leave rather than fold into 35's scope.

- `dataprism.transport.mode=stdio` now refusing unconditionally
  (`DataPrismAutoConfiguration`'s `dataPrismStdioTransportRefused`) makes
  `dataprism.transport.fixture-development=true` unreachable everywhere in
  the Spring surface. `ConfiguredJsonSourcesInitializer`'s plaintext-loopback
  relaxation and `DataPrismContractValidator`'s zero-source early return are
  consequently dead-in-effect paths — still executed, still pinned by tests,
  but reachable by no live configuration.
- `data-prism-connectors-rest`'s own `fixture-development` read is dead code
  for the same reason.

Found during `/verify` on task 31, 2026-09-15. Neither blocks anything; pick
either up only if a future task already owns the file.

- An application that registers its own `FieldMetadataResolver` bean
  silently suppresses the descriptor wiring: the shipped bean is
  `@ConditionalOnMissingBean`, so a set `dataprism.privacy.descriptor-file`
  is then never read or validated, and startup succeeds without warning.
  The reviewer scoped the fix to a successor task rather than folding it
  into task 31, because closing it means guarding a `REPLACEABLE` extension
  point, a different-shaped change than wiring the property was.
- A descriptor file containing only a YAML document marker (`---` with no
  `models:` key) makes `ModelDescriptors` throw a `NullPointerException`
  rather than return a named refusal code. Startup still fails closed, so
  the fail-closed invariant holds, but `docs/conventions.md` expects a
  stable code for every refusal, not an incidental `NullPointerException`.

Found during `/verify` on tasks 28 and 29, 2026-09-15. Neither blocks
anything; pick either up only if a future task already owns the file.

- `StrictYamlTest`'s comment claims the old inlined `enumValue` logic was
  never invoked with `null`. The reviewer showed that is false: four call
  sites in `PrivacyProfiles` and `ModelDescriptors` do call it with `null`.
  `StrictYaml.enumValue`'s behaviour is correct either way — only the
  comment's stated justification is wrong, and `docs/conventions.md`
  forbids a comment asserting a state nobody established.
- `JsonTreeScrubbingEngineTest`'s new cross-field test, added to exercise the
  `OwnerScope` record, duplicates an existing assertion on the same fixture
  and does not exercise a nested parent scope as intended. The record's
  behaviour under real nesting remains unproven by a dedicated test.

Found during `/verify` on task 20, 2026-09-15. None blocks anything; pick any
of them up only if a future task already owns the file.

- `ConfiguredJsonSourcesAutoConfiguration` hand-copies
  `DefaultContextOrchestrator`'s construction from
  `DataPrismAutoConfiguration.java:233-241` rather than sharing it, so it is
  invisible to task 22's classification sweep, which scopes to
  `DataPrismAutoConfiguration.class.getDeclaredMethods()`. A future hardening
  of the base orchestrator's assembly will not reach a deployment with this
  jar on its loader path. The architect's recommendation: have the connector
  contribute a composed `ScrubbingEngine` bean instead, which would also let
  it drop its `data-prism-orchestration` dependency.
- `SourceValues.java:33-47` and `DefaultContextOrchestrator.java:234` resolve
  `FieldMetadata` by `record.getClass()`, which the shared
  `ConfiguredJsonPayload` wrapper defeats, so the orchestrator's own
  prohibited-value computation returns an empty or wrong set for configured
  sources. `ConfiguredJsonScrubbingEngine` independently closes that gap and
  fails closed, but the root assumption is wrong and lives in
  `core`/`orchestration`, not in the connector.
- `connectorsAreLeaves` only forbids INBOUND edges into
  `..dataprism.connectors..`; it does not constrain a connector's own
  outbound dependencies. `docs/architecture.md` declares the row
  `connectors-rest | core |`, which `data-prism-connectors-rest/pom.xml` now
  contradicts by depending on `validation` and `orchestration`, with nothing
  able to fail the build over it. The architecture document states a
  boundary no rule enforces.
- `ConfiguredJsonSourcesInitializer` never checks `dataprism.transport.mode`
  itself, relying on other modules to refuse. Fail-closed today, but it
  holds no independent opinion on transport mode.
- A catalogue source name containing `_` or uppercase now makes `Binder.bind`
  throw `InvalidConfigurationPropertyNameException` where `getProperty`
  previously returned null. It still fails closed, but the message names
  neither the source nor the cause; only blankness is validated at
  `ConfiguredJsonSources.java:104`.

Found during `/verify` on tasks 21-24, 2026-09-14. None blocks anything; pick
any of them up only if a future task already owns the file.

- The JWKS discovery success path now has no positive coverage in either
  module: task 21 removed `SecurityConfigTest.discovers_jwks_when_the_issuer_discovery_location_is_configured`
  because it depended on the removed plaintext relaxation. A positive test is
  still constructible — serve metadata over plaintext, return an `https://`
  `jwks_uri`, assert the decoder builds and `metadataRequests == 1`, since
  `NimbusJwtDecoder` fetches lazily. Token decoding itself remains proven by
  `ServerSecurityBoundaryTest` and `McpHttpEndToEndTest`, so this is a gap, not
  a hole.
- `STANDALONE_HTTP_ONLY` is now asserted by no test anywhere.
- `ServerIntegrationsConfiguration.java:22`'s `isFixtureDevelopment()` disjunct
  is now dead code, since fixture implies STDIO.
- `PrivacyExtensionPoints`' `COMPETING_BEAN_REFUSAL` is read from the same file
  the sweep checks, so a future bean can self-certify a guard that does not
  exist; only the `@ConditionalOnMissingBean`/`@Primary` halves are
  independently verified.
- `ServerArchitectureTest`'s `that()` side constrains every dataprism class on
  the server's test classpath rather than just `..dataprism.server..`; a
  future edge module using Spring Security would go red in a test owned by a
  different module.
- `data-prism-server`'s `spring-boot-maven-plugin` repackage replaces its
  plain jar, so its classes are invisible to any ordinary Maven-classpath scan
  after the `package` phase. Task 23 worked around it inside its own module;
  the underlying fix is a `<classifier>` on that plugin execution in
  `data-prism-server/pom.xml`.
- ~~`data-prism-connectors-rest` is an unconsumed leaf — nothing in the
  reactor depends on it.~~ **Resolved 2026-09-15.** Task 20 wired it: the
  configuration-driven JSON REST mode is its first real caller, and its test
  count went from 13 to 49. (`data-prism-hazelcast` was resolved the same
  way one wave earlier: task 25 gives it a real caller through the
  autoconfigure module's `embedded` topology.)

Found during `/verify` on task 18, 2026-09-14. Neither blocks anything; pick
either up only if a future task already owns the file.

- ~~`ServerPackagingIT.DEVELOPMENT_KEY_MARKERS`
  (`data-prism-server/src/test/java/.../ServerPackagingIT.java:42-51`) was
  not extended with the quickstart's HMAC literal,
  `quickstart-demo-hmac-key-not-a-real-secret-32-bytes-long`, and that file's
  own javadoc obliges whoever adds a development key to extend the list.~~
  **Retracted 2026-09-15.** This recommendation was wrong: `data-prism-server`
  never compiles or reads the quickstart env file, so no artefact this scan
  inspects can ever emit the literal, and the class's own javadoc forbids a
  marker no build artefact emits — extending the list would itself have
  violated the file's rule. Task 20 added the marker, then correctly removed
  it once this was established; a tester confirmed by unzip-scanning the
  freshly repackaged jar that the literal appears zero times. Do not
  re-propose this.
- `QuickstartSmokeIT.java:164` asserts the synthetic name's shape, not its
  stability. A second call for the same subject in the same scope returning
  the same value would make the pseudonymisation claim materially stronger,
  and consistency-within-a-scope is the core guarantee this product sells.

Found during `/verify` on task 25, 2026-09-14. None blocks anything; pick any
of them up only if a future task already owns the file.

- `dataprism.privacy.hmac-key`'s neighbour `identity-cache-ttl` is bound,
  validated, and read by nothing — `CachingSyntheticValueSource.java:142-147`
  derives its TTL from `context.expiresAt()`, and `PrivacyCluster.configure()`
  (`PrivacyCluster.java:71-75`) replaces any pre-set `MapConfig`, so no caller
  can wire it. Pre-existing, confirmed by review, not introduced by task 25.
  Same defect shape as `topology` was, one layer down: a property an operator
  can set that changes nothing. `docs/configuration.md:63` makes no false
  claim about it, but the refusal column still implies the property means
  something. Fixing it needs `data-prism-hazelcast`, which task 25 was
  explicitly scoped out of.
- `DataPrismAutoConfiguration.java:225` — `@ConditionalOnBean` also suppresses
  the bean definition, so `topology: embedded` with Hazelcast present but no
  `DataSourceAdapter` refuses with `MISSING_SHARED_BUDGET` and a message
  claiming Hazelcast is missing from the classpath, which is false. Fails
  closed, but reports the wrong cause.
- `DataPrismAutoConfiguration.java:188` — `matchIfMissing=true` is untested;
  flipping it to `false` leaves all 36 autoconfigure tests green. It makes no
  refusal unreachable, so it is not a fail-open, but the default is unproven.
- `DataPrismAutoConfiguration.java:225` — `"embedded".equals(...)` is
  case-sensitive while the `@ConditionalOnProperty` above it matches
  case-insensitively, so `topology=Embedded` under stdio fixture-development
  with Hazelcast absent fails closed on an `UnsatisfiedDependencyException`
  rather than the stable code.

Left by task 09's close-out. Neither blocks anything; pick either up only if a
future task already owns the file.

- `PiiLogScanTest.java:191-193` — the sum assertion (`auditCount + nonAuditCount
  == total`) is tautological: the second count is defined as the complement of
  the first, so the assertion cannot fail. The two non-empty assertions either
  side of it are the load-bearing checks and do work. Harmless, but the sixth
  instance in this repository of an assertion that cannot fail — this one came
  from the task brief itself rather than from the implementer.
- `PiiLogScanTest.java:104-112` — `AUDIT_KEYS` holds 19 names while the javadoc
  says "twenty placeholders": the sink's `seq={}/{}` is two placeholders folded
  into one field. `docs/conventions.md` forbids a comment asserting a state
  nobody established; this one should be corrected to 19, or the javadoc
  reworded to explain the fold, next time this file is touched.

Found by the 2026-09-09 documentation audit, not fixed there because none of
it is a doc fix:

- `ArchitectureTest.pseudonymisationIsDeterministic`'s missing `Instant.now()`
  check, and boundaries 2, 3, 5, 6 in `docs/architecture.md` having no
  enforcing test — see that file's boundaries section for the full
  enforcement audit — are now tasks 12 and 13 above, not free-floating items.
- No `SECURITY.md`, `CODE_OF_CONDUCT.md`, `CHANGELOG.md`, or issue/PR
  templates. Security reporting is folded into `CONTRIBUTING.md`, which works
  but is non-standard for a public repository.

Found by task 10, not fixed there because neither is that task's work:

- Task 10's PRs (#9 and #10) were merged into `main` by that task's own
  implementer, using the owner credentials every agent authenticates as —
  not by repository auto-merge, which was and is off (`allow_auto_merge` is
  `false`). PR #9 was merged while its own Actions run was failing (run
  34385475478). This has two halves, and only one is closed:
  - ~~`main` had no branch protection requiring the `build` check to pass
    before a branch could reach it, by any route including a direct
    push.~~ **Resolved 2026-09-09.** Branch protection on `main` now
    requires the `build` status check (GitHub Actions), up to date with the
    branch being merged, enforced for admins, with force-push and deletion
    blocked. A commit whose `build` check has not passed can no longer reach
    `main`. Consequence for the loop: `/record` can no longer merge a task
    branch locally and push straight to `main` — that push is itself
    rejected, since the merged commit has no check run against it yet.
    Every task branch now reaches `main` through a PR whose head commit goes
    green. See `docs/workflow.md`'s Phase 4 for the updated flow.
  - **Still open.** Branch protection does not stop an agent with owner
    credentials from merging green-but-unreviewed work, including its own
    pull request — agents can do anything the repository owner can, and
    requiring approvals would not change that, since the same credentials
    could approve too. The only real control is role discipline: an
    implementer's job ends at reporting on its branch, and merging happens
    only in `/record`, after `/verify`. That is now stated explicitly in
    `docs/workflow.md` rather than left implicit. Owned by the role
    definitions, not by repository configuration.
- The kit's own `/record` skill definition (outside this repository, in
  `~/.claude/commands/`) still describes the old flow — merge locally, push
  straight to `main`. This repository cannot fix it; it belongs to the
  repository owner to update in the kit itself, or the next `/record`
  invocation will attempt a push that branch protection now rejects.
- The seventh cannot-fail assertion this survey found —
  `RestDataSourceAdapterHttpTest.java:97`'s bare
  `isInstanceOf(RuntimeException.class)`, the same vacuous form task 08
  removed from its neighbour — is now task 11 above, not a free-floating
  item. A survey of every test module found no other test asserting on a
  platform-specific exception type or message, so the cross-platform problem
  task 10 fixed appears confined to the one test it fixed.

## Someday

Ordered, not scheduled. S5, S6 and S7 are independent of one another once S3
lands and are the natural parallelisation point.

- **S5 — Connectors and orchestration.** `RestClient` sources, virtual-thread
  fan-out with timeouts and circuit breakers, `IdentityResolver` SPI, request and
  cost limits.
- **S6 — Canonical model and correlation.** Canonical entity, provenance,
  per-scope pseudonymisation of source names, normalisation-aware consistency
  findings, the injection-heuristic finding type.
- **S7 — Hazelcast.** Client–server topology, forward and reverse maps, TTL,
  scope purge, and the test proving output is identical with the cluster killed.
- **S8 — Security.** OAuth2 resource server, mTLS, RBAC and ABAC, purpose
  validation, scope lifecycle held entirely outside the MCP surface.
- **S9 — Audit and observability.** Per-writer hash chain, append-only sink,
  pseudonymised subject ids in audit, metrics, and a log-scanning test that fails
  on any PII in a full integration run.
- **S10 — Re-identification surface.** Separate application, separate port,
  separate authorisation scope, mandatory purpose and audit. Deferred past V1 by
  decision; the reverse map it will read is still built in S7, because one added
  after the fact cannot resolve any pseudonym issued before it existed.
- **S11 — Example application and search.** Three divergent stub APIs, the
  Elasticsearch connector with allowlists and caps, `search_entity_data` and
  `describe_entity_model`, Docker Compose.
- **S12 — Hardening.** Threat model T1–T10 as tests, mutation testing over the
  privacy and validation modules, `docs/data-protection.md`,
  `docs/threat-model.md`, ADRs, load testing.

## Not doing

Recorded so they are not re-proposed. Each is a deliberate scope boundary from
`docs/design-review.md`, not an oversight.

- Probabilistic entity matching. It sits behind `IdentityResolver` and is a
  different product.
- Any MCP tool that opens, selects or extends a privacy scope. That is the one
  lever that breaks scope isolation.
- Any MCP tool that re-identifies a pseudonym.
- Caching raw source responses.
