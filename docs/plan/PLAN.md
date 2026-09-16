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

**Nothing left is development work** — what remains is an owner-driven publish
sequence, and the ordering matters:

1. Manually dispatch `publish-image.yml` on the existing `v0.1.0` tag. This is
   the first real execution of task 40's digest-push and manifest-assembly
   steps — the only prior run of this workflow was against the old,
   single-platform shape.
2. Verify the manifest resolves per platform on real hardware: an x86_64
   Ubuntu host should pull `amd64`, an Apple Silicon Mac should pull `arm64`.
3. Manually dispatch `publish-mcp.yml` on the tag to publish the registry
   entry, which requires the image to be pullable. The reviewer confirmed
   `publish-mcp.yml`'s `docker manifest inspect` pullability guard is
   satisfied by a manifest list, so no successor task is needed there.

Maven Central 0.1.0 is already published and synced — repo1.maven.org serves
`data-prism-core` and `data-prism-spring-boot-starter` 0.1.0, verified by
building an external consumer project against an empty local repository. The
GHCR package is already public from the prior amd64-only publish.

Owner actions outstanding, none of which any agent can perform:

- The `central` GitHub Environment exists but has no required reviewers
  ticked, so it currently gates nothing.
- `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD` should move from
  repository secrets to the `central` environment's scope, now that
  `publish-central.yml`'s stage job no longer references them.
- The MCP registry namespace claim (`io.github.aindriub`) happens via GitHub
  OIDC at `publish-mcp.yml` dispatch time — no separate owner action, but the
  dispatching identity must be the repository owner's.

### Dependabot PRs — open, unplanned, needs triage

Twelve PRs (#44-#55) opened since task 34's `dependabot.yml` landed
2026-09-16, none merged, none equally safe:

- #54 bumps `spring-boot.version` 3.5.16 to 4.1.1 — a major version touching
  the autoconfiguration ordering and `BeanFactoryPostProcessor` sequencing
  tasks 35 and 39 just fixed.
- #48-#53 move the Docker base images to Java 25/26 while every pom
  deliberately targets `--release 21`.
- #45, #47, #51 are Actions version bumps (`checkout`, `upload-artifact`,
  `setup-java`) that would clear the Node 20 deprecation warnings and touch
  the workflow task 40 just rewrote.
- #55 is a `nimbus-jose-jwt` patch bump, the lowest-risk of the twelve.

Not triaged here — that is a decision for whoever picks this item up, not
something to plan in advance.

**Nothing is scheduled past this point in this plan.** Picking anything up
from "Someday" below is a new planning decision, not a continuation of this
plan.

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
