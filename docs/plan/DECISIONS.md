# Decisions and accepted residuals

A register of owner decisions that, on 2026-11-09, were recorded only in `docs/plan/PLAN.md`, and of the accepted residuals and risks recorded there. It exists so the decisions survive when `PLAN.md` is cut down to open work. Only the scribe edits this file. It is not loaded routinely: grep it by id (`grep -n 'OD-292-5' docs/plan/DECISIONS.md`).

Every row cites `PLAN.md@5beb829a:<line>`. `PLAN.md` lines move, so read the sha as "the line at that commit"; use `git show 5beb829a:docs/plan/PLAN.md | sed -n '<line>p'` to see it. The wording is copied from `PLAN.md` and shortened, not reinterpreted. Where the line gives no reason the cell says so.

## How the id set was computed

Run from the repository root at `origin/main` 5beb829a. The id pattern is `D-` followed by dot-separated or hyphen-separated parts of digits and capital letters, so it also catches ids that end in a letter (`D-139-C`) or have one part (`D-127`). It also matches `OD-` ids (the open-decision form, which is what most of the ids below are). A first version required a numeric last part and missed 24 ids:

```
ex() { grep -oE '(^|[^A-Za-z])O?D-[0-9A-Z.]+(-[0-9A-Z]+)*($|[^0-9A-Za-z-])' "$@" | sed -E 's/^[^OD]//; s/[^0-9A-Z]+$//' | sort -u; }
comm -23 <(ex docs/plan/PLAN.md) <(cat docs/plan/HISTORY.md docs/architecture.md docs/design-review.md docs/plan/design/* | ex)
```

This gives 86 ids: 35 plain `D-` ids and 51 `OD-` ids. The owner's figure of 35 on 2026-11-04 was not reproducible: `PLAN.md` has since gained the wave-8 and wave-9 records (OD-292-*, OD-293-*, OD-W6-*). Restricting to ids not preceded by a letter gives only the 35 plain `D-` rows. The 86 are listed in full. Ids are sorted by series, not by number.

Status "open" means `PLAN.md` records the item as an open owner decision with a recommendation, not a decision.

## Decisions

| Id | Date | Decision | Reason | Recorded |
|---|---|---|---|---|
| D-0.4.1-C | decided and implemented in 0.4.1 (HISTORY.md "2026-10-06 — Task 132: cluster properties and startup refusals" records the validation of application `PrivacyCluster` beans and the `CLUSTER_SETTINGS_IGNORED` refusal; the entry does not name the decision id; date not stated) | Application `PrivacyCluster` beans stay allowed but are validated: `using()` refuses auto-detection or multicast (`UNSAFE_HAZELCAST_DISCOVERY`) and a `dev` name, and `embedded(Config)` forces both off. Setting the properties alongside such a bean refuses with `CLUSTER_SETTINGS_IGNORED`. | The alternative, forbidding custom beans, breaks hosts that manage their own instance. | PLAN.md@5beb829a:921 |
| D-0.4.1-D | decided and implemented in 0.4.1 (HISTORY.md "2026-10-06 — Task 133: multi-instance Compose and Kubernetes examples" records the Kubernetes manifest with DNS mode first and API mode commented with its own ServiceAccount and Role; the entry does not name the decision id; date not stated) | Support both Kubernetes join modes: DNS mode (`service-dns`, a headless service, no RBAC) and API mode (`service-name`, which needs get and list on endpoints and pods), with DNS shown first in the examples. | DNS mode grants the pod no Kubernetes API rights. | PLAN.md@5beb829a:927 |
| D-0.4.1-F | 2026-10-07 | Publication goes to Central (library modules; `data-prism-server` stays off Central), GHCR and the MCP Registry, as 0.4.0 did. | Not stated in PLAN.md. | PLAN.md@5beb829a:945 (also 972) |
| D-0.6-6 | 2026-10-08 | `ToolAdmission.none()` is kept, but the options record has no default for admission, so every caller names `none()` or a real policy. | Approvals are never turned off silently (task 155). | PLAN.md@5beb829a:1326 (also 1295) |
| D-0.6-8 | 2026-10-08 | The JSON projection bean is `PRIVACY_CRITICAL` with `COMPETING_BEAN_REFUSAL` (161). D1, a read-only audit event listener SPI, becomes task 163. D2, application-written events in the hash-chained trail, is roadmap only. | Not stated in PLAN.md. | PLAN.md@5beb829a:1328 |
| D-0.6-9 | 2026-10-08 | Add `spring-boot-configuration-processor` for IDE completion of `dataprism.*` keys. | 158 stays a pure move and 164 adds the processor. | PLAN.md@5beb829a:1329 |
| D-070-8 | 2026-10-09 | (a) CycloneDX SBOMs, buildx SBOM and provenance, keyless cosign on every pushed digest, `attest-build-provenance` on release assets. | Not stated in PLAN.md. | PLAN.md@5beb829a:654 |
| D-070-10 | 2026-10-09 | (a) The load driver is a Java client on the MCP Java SDK with HdrHistogram, standalone `load-test/` outside the reactor. | Not stated in PLAN.md. | PLAN.md@5beb829a:656 |
| D-070-11 | 2026-10-09 | (a) Baseline at 8, 32 and 100 sources (300 optional), three runs each, one named machine; internal in `docs/plan/perf/`, published from 0.9. | Not stated in PLAN.md. | PLAN.md@5beb829a:657 |
| D-070-16 | 2026-10-09 | (a) A lock that cannot be taken in time fails with the new code `CLUSTER_LOCK_TIMEOUT`. | Not stated in PLAN.md. | PLAN.md@5beb829a:662 |
| D-1.0-2 | 2026-10-19 | The isolation-only interpretation of D-1.0-2 is superseded by the sealed-state cluster design (encrypted re-identification values, separate cluster/name keys). | Owner decisions of 2026-10-19: tasks 204/205/212 are superseded, not completed; 215 plans the successors. The original text of D-1.0-2 is not in PLAN.md. | PLAN.md@5beb829a:604 |
| D-127 | not stated | (a), answered for task 127: when re-identification is on, an application `SyntheticValueSource` is wrapped like the default, and the wrapper falls back to the wrapped source on any cache failure. There is no `REIDENTIFICATION_INDEX_UNWIRED` refusal. | Not stated in PLAN.md. | PLAN.md@5beb829a:1415 |
| D-130-A | 2026-10-07 | Spring Boot 4 is not part of 130. It is its own migration, task 139. | Not stated in PLAN.md. | PLAN.md@5beb829a:1053 |
| D-139-C | 2026-10-07 | Manage nimbus-jose-jwt once in the root pom at 10.10, and remove the quickstart-issuer pin and its stale comment (closed in task 141). | `data-prism-server` bundled nimbus-jose-jwt 9.37.4 through the Spring Boot BOM while the quickstart issuer pinned 10.10. | PLAN.md@5beb829a:1066 (also 1059) |
| D-139-D | 2026-10-07 | Target Spring Boot 4.1.1, not 4.2 milestones. | 4.1 OSS support runs to 2027-07-31. | PLAN.md@5beb829a:1067 |
| D-140-A | 2026-10-07 | Floating `eclipse-temurin:25-jre` and `maven:3.9-eclipse-temurin-25` tags. | Not stated in PLAN.md. | PLAN.md@5beb829a:1068 |
| D-140-B | 2026-10-07 | Dependabot ignores eclipse-temurin 26 and later and maven 3.9.26 and later, plus Maven 4 (semver-major). Moving to the next LTS, 29, is an owner decision. | Not stated in PLAN.md. | PLAN.md@5beb829a:1069 |
| D-140-C | 2026-10-07 | Record the Hazelcast `sun.misc.Unsafe` warning on JDK 25; never silence it with `--sun-misc-unsafe-memory-access=allow`. | Not stated in PLAN.md. | PLAN.md@5beb829a:1070 |
| D-140-D | 2026-10-07 | Load Mockito as a `-javaagent` in data-prism-mcp only. | Not stated in PLAN.md. | PLAN.md@5beb829a:1071 |
| D-148-A | 2026-10-07 | Correlated logging through the SLF4J MDC. `dataprism.correlation.mdc-key` is unset by default (off). Only the validated `ExternalCorrelationId.value()` goes into the MDC, opened at the MCP tool handler and inside each `SourceFanOut` task from that task's own `DataRequest`, never by ThreadLocal inheritance, and always cleared in `finally`. No Micrometer or OTel propagation (C6 stays deferred) and no MDC on background threads. The optional global `dataprism.correlation.outbound.header` amends 111 and 113. | Opened at the tool handler because the SDK runs sync tools off the servlet thread. | PLAN.md@5beb829a:1079 |
| D-149-A | 2026-10-07 | If the only clean fix is in production code (`src/main`, POMs), the implementer stops and files a follow-up task. Owns is not widened. | Not stated in PLAN.md. | PLAN.md@5beb829a:1075 |
| D-149-B | 2026-10-07 | Proof on macOS alone is enough for acceptance. CI's Linux matrix runs on the next push give the Linux evidence, tracked as a release-cut item. | Not stated in PLAN.md. | PLAN.md@5beb829a:1076 |
| D-149-C | 2026-10-07 | Bounded port re-allocation is allowed: at most 3 attempts, only on a bind failure of the chosen port, never wrapped around an assertion. It is a port-selection retry, not a test rerun. | Not stated in PLAN.md. | PLAN.md@5beb829a:1077 (also 1114) |
| D-153-A | 2026-10-08 decided (PLAN.md@5beb829a:1153, "Decisions recorded for task 153 (2026-10-08):", the list that holds line 1154) | A2. A resumed writer terminates a torn tail with `"\r\n"` and fsyncs before appending. The verifier reports any line ending in a raw `\r` as INTERRUPTED_WRITE_FRAGMENT without parsing it. Directory mode uses the same terminator. | Not stated in PLAN.md. | PLAN.md@5beb829a:1154 |
| D-153-B | 2026-10-08 decided (PLAN.md@5beb829a:1153, "Decisions recorded for task 153 (2026-10-08):", the list that holds line 1155) | B1. A fused line already present in a pre-0.5.0 log stays a break (exit 2), and the FIELD_COUNT_MISMATCH text, `docs/audit.md` and the CHANGELOG name the legacy cause. | Not stated in PLAN.md. | PLAN.md@5beb829a:1155 |
| D-163-A | 2026-10-08 | Audit listeners run on one dispatcher thread behind a bounded queue with a capacity property; overflow drops for listeners only and is logged as `AUDIT_LISTENER_DROPPED`; a shutdown drain policy is required; the feed is best-effort. | Not stated in PLAN.md. | PLAN.md@5beb829a:1330 |
| D-163-B | 2026-10-08 | Listeners are called after whichever sink accepted the event; durability holds only for `hash-chained`, and the docs say so. | Not stated in PLAN.md. | PLAN.md@5beb829a:1331 |
| D-163-C | 2026-10-08 | The dispatcher is its own `PRIVACY_CRITICAL` bean with `COMPETING_BEAN_REFUSAL`; applications add listeners but cannot replace it. | Not stated in PLAN.md. | PLAN.md@5beb829a:1332 |
| D-163-D | 2026-10-08 | A listener failure logs one WARN `AUDIT_LISTENER_FAILED` to the application log with the listener class, event id, sequence and exception class name only, never the message or stack trace. | Not stated in PLAN.md. | PLAN.md@5beb829a:1333 |
| D-164-B | 2026-10-08 | The docs-to-metadata check is exact in both directions, with exceptions only in the checked-in `configuration-metadata-gaps.txt`, one reason per line. | Not stated in PLAN.md. | PLAN.md@5beb829a:1335 |
| D-165-A | 2026-10-08 | Add the `ci-gate` job (needs every matrix leg and `container-smoke`, `if: always()`) and rename pages.yml's `build` job to `docs-site`. After `ci-gate` reports on main once, the owner switches the required checks to `ci-gate` alone; the implementer changes no settings. | After task 144's matrix, the required check named `build` silently gated on the docs build, which `pages.yml` also produced. | PLAN.md@5beb829a:1336 (also 1166, 1297) |
| D-183-A | 2026-10-09 | Cut 0.6.0 locally: version, dated CHANGELOG, cut-time doc fixes. PLAN.md records only "D-183-A to D all a" and the cut as done on `release/0.6.0-cut`. | Not stated in PLAN.md. | PLAN.md@5beb829a:1231 |
| D-J3-1 | 2026-10-08 | Option (a) (task 168): the two tool constructors lose their `ObjectMapper` parameter; tools take the mapper from package-private `DataPrismObjectMapper.create()`, and `DataPrismMcpServer` passes one shared instance through a package-private constructor. | Rejected (b) package-private constructors, which rewrites 10 integration-test files for no extra protection; rejected (c) an opaque data-prism-owned type, which adds a public type to carry one already hidden. | PLAN.md@5beb829a:1316 (also 1211) |
| D-J3-2 | 2026-10-08 | Option (b), in 0.6.0, folded into task 168: an ArchUnit allowlist rule for streaming JSON factory and generator construction (allowed: `DataPrismObjectMapper`, `AuditJsonRenderer`, the checkpoint writer `audit.AuditCheckpoint`, `JwtDecoderSupport`) with its own negative-test fixture. Owner delegated the call. | Not in 167, whose port stays behaviour-neutral. Line 1256 corrects the allowlist entry to `audit.AuditCheckpoint`, not `FileAuditCheckpointSink`. | PLAN.md@5beb829a:1317 (also 1256) |
| D-RM-3 | 2026-10-09 | Folded into D-RM-14 (HATEOAS links: sealed, one-step, built from adapter and resolver declarations). | Not stated in PLAN.md. | PLAN.md@5beb829a:24 |
| OD-08-14 | not stated | (a) The operator `resume` writes a linked `DENY` when the apply fails. It decides `resume` only; the operator read endpoints, which write `ALLOW` before the read, are not covered. | The read endpoints leave an `ALLOW` for a read that did not happen; same shape as the `resume` bug 245 fixed. | PLAN.md@5beb829a:566 (also 483) |
| OD-08-15 | not stated | (a) Whether the in-process budget should key as the shared one does is open. | The in-process budget holds raw subject keys on the heap. | PLAN.md@5beb829a:346 |
| OD-207-1 | 2026-10-27/28 (wave 5) | (b) A purge finishes a partly deleted day by its recorded anchors before verifying later days. | Not stated in PLAN.md. | PLAN.md@5beb829a:195 |
| OD-207-2 | 2026-10-27/28 (wave 5) | (a) A partly purged day is finished only after its remaining segments verify on their own (same chain keys, never joined to the next day) with no break, structural anomaly or unterminated tail, and every writer's first remaining record starts at GENESIS or follows a recorded anchor. Purge also refuses at a duplicate sequence. | Not stated in PLAN.md. | PLAN.md@5beb829a:195 |
| OD-207-3 | 2026-10-27/28 (wave 5) | (a) Retention builds anchors and day-deletion boundaries only from records the verifier hashed, through a small verifier API (task 292). | Not stated in PLAN.md. | PLAN.md@5beb829a:195 (also 147) |
| OD-210-1 | 2026-10-27/28 (wave 5) | (a) Allowlists are compared by value: a child context inheriting the parent's list starts, a different list is refused. 210's Owns widened to `data-prism-security` (`ClientAllowlist`, `AuthorizationService`, `CaseAuthorizer`, `TokenTrustedCaseAuthorizer`). | Not stated in PLAN.md. | PLAN.md@5beb829a:196 |
| OD-225-1 | 2026-10-27/28 (wave 5) | (a) A T14 architecture rule forbids `SealedWrites.computing` outside the seal package, with an empty allow-list that 224 and 226 extend. | Not stated in PLAN.md. | PLAN.md@5beb829a:197 |
| OD-270-1 | 2026-10-27 | Superseded by 290 (the engine builds the scrub tree itself; Jackson annotations on source models are refused at startup). | The engine uses its own walker (288), not Jackson `valueToTree`; 289 makes the describer read declared types only. | PLAN.md@5beb829a:168 |
| OD-270-2 | 2026-10-23 (a) | (a) stands. PLAN.md does not record the option's text. The retired task file `docs/plan/tasks/retired/270-classified-records-must-serialise-as-objects.md`, section "OD-270-2: decided (a) by the owner, 2026-10-23", states: "The fix ships in 0.8.0 only. There is no 0.7.2." and that the draft advisory is GHSA-f94h-6mxv-c957. | Only the standing status is stated in PLAN.md; the decision text is not. | PLAN.md@5beb829a:168 (also 364) |
| OD-270-3 | 2026-10-27 | Superseded by 290. | See OD-270-1. | PLAN.md@5beb829a:168 |
| OD-272-1 | 2026-10-23 | (a) A saturated source call reports the existing `FAILED` with detail `UNAVAILABLE`; no new status. | Not stated in PLAN.md. | PLAN.md@5beb829a:148 |
| OD-273-1 | open (filed 2026-10-23) | The default `dataprism.privacy.scope-lifetime`. Recommended `PT8H`. | Not stated in PLAN.md. | PLAN.md@5beb829a:526 |
| OD-273-2 | open (filed 2026-10-23) | What happens to `dataprism.security.jwt.*` now that Spring's keys are read. Recommended (a): Spring's keys only, with a coded migration refusal; (b) accepts both and refuses on conflict. | (a) is breaking for server operators and touches `server.json`, docker, compose and the quickstart. | PLAN.md@5beb829a:532 |
| OD-273-3 | 2026-10-24 | (a) `dataprism.identity.resolver` defaults to pass-through only when exactly one source is configured. With two or more and none configured, startup refuses `MISSING_IDENTITY_RESOLVER`. Mechanism (rewritten 2026-10-26): a marked default bean plus a startup guard over the orchestrator's served sources (refuses on two or more sources, zero orchestrators, any custom orchestrator; fires before the embedded web server starts); the definition-time count is removed. | Found in the external review of PR #162; the rewrite is "for a robust fix". | PLAN.md@5beb829a:527 (also 155) |
| OD-275-1 | open (filed 2026-10-23) | Existing catalogues. Recommended (a): bare names keep working as `$.<name>`; `nested-catalogues:` is retired with a coded refusal and a migration note. | Not stated in PLAN.md. | PLAN.md@5beb829a:533 |
| OD-276-1 | open (filed 2026-10-23) | What the auto-configured chain protects when the host application has none. Recommended (a): every request needs a JWT except the health probe. | The host application fails closed. | PLAN.md@5beb829a:534 |
| OD-277-1 | open (filed 2026-10-23) | Whether the starter depends on `data-prism-connectors-rest`. Recommended (a): yes. | It stays inert until configured, and no artifact is added. | PLAN.md@5beb829a:535 (also 73) |
| OD-283-1 | open (filed 2026-10-23) | When to create the template repository. Recommended: after 0.9.0 is on Central, named `data-prism-starter-template`, public. Needs the owner's own go. | The spec said after 0.8.0, but the example needs 0.9 features. | PLAN.md@5beb829a:536 (also 163) |
| OD-285-2 | 2026-10-27/28 (wave 5) | (a) A release that fails before any write leaves the reservation whole and retryable; a failure after the claim spends it and leaves the counters over-counted. | Not stated in PLAN.md. | PLAN.md@5beb829a:198 |
| OD-285-3 | 2026-10-27/28 (wave 5) | (a) The scope-reuse residual of OD-285-1 also covers a `forget` on any instance during an unlocked read's gap, or after a release's lease lapses, when the scope id is reused. | See the residual row for OD-285-1. | PLAN.md@5beb829a:198 (also 207) |
| OD-288-1 | 2026-10-27 | (a) `@JsonAnyGetter` is refused, and record sources carry no undeclared keys. | Not stated in PLAN.md. | PLAN.md@5beb829a:172 |
| OD-288-2 | 2026-10-27 | (b) JDK wrapper collections are accepted; there is no closed list of collection classes. | Not stated in PLAN.md. | PLAN.md@5beb829a:177 |
| OD-288-4 | 2026-10-27 | (a) Nodes and byte arrays are copied while the tree is built. | Not stated in PLAN.md. | PLAN.md@5beb829a:178 |
| OD-288-5 | 2026-10-27 | (a) A number node holding a user subclass is refused with `SOURCE_MODEL_FOREIGN_NODE`. | Not stated in PLAN.md. | PLAN.md@5beb829a:179 |
| OD-288-6 | 2026-10-27 | (b) A fixed node cap, task 291, `MAX_NODES` = 1,000,000 per source, code `SOURCE_TREE_TOO_LARGE`. | Not stated in PLAN.md. | PLAN.md@5beb829a:180 (also 148, 200) |
| OD-290-1 | 2026-10-27 | (b) Jackson annotations on source models are refused, except a closed list of five deserialisation-only ones: `@JsonAlias`, `@JsonCreator`, `@JsonDeserialize`, `@JsonSetter`, and `@JsonIgnoreProperties(ignoreUnknown=true)` with no names. | Not stated in PLAN.md. | PLAN.md@5beb829a:174 |
| OD-290-2 | 2026-10-27 | (b) The range probe and the workaround matrix are dropped. GHSA-f94h-6mxv-c957 will say every release through 0.7.1 is affected, there is no workaround, and the fix is 0.8.0; the coordinator updates the advisory at the release cut. | 270 and its Outcome no longer exist, so there is no per-tag proof to cite (line 364). | PLAN.md@5beb829a:175 (also 364) |
| OD-291-1 | 2026-10-27 | (a) 291 bases on `main` after PR #163 merges, the same as 247. | #163's hold list was 210, 288, 289 and 290, then 289 and 290; both have merged. | PLAN.md@5beb829a:181 (also 148) |
| OD-291-2 | 2026-10-27 | (b) A fixed per-source byte cap, counted in the same walk as the node cap; covers `byte[]` and `char[]` leaves, `BinaryNode` bytes and string lengths; refuses with `SOURCE_TREE_TOO_LARGE`. Ships in 0.8.x as its own task, not in 0.8.0. | It is not a regression from 0.7.x. | PLAN.md@5beb829a:183 (also 187) |
| OD-292-1 | 2026-10-27 | (a) A real crash can leave a line that parses but was never hashed. It blocks purge of its day, and of every later expired day, until an operator acts (recovery is task 293). | Not stated in PLAN.md. | PLAN.md@5beb829a:201 |
| OD-292-2 | 2026-10-27 | (a) Retention deletes only what it can link to what stays (rules 6 and 7). If a writer never writes again, deleting its last records cannot be detected at the boundary; periodic checkpoints are the defence. | Not stated in PLAN.md. | PLAN.md@5beb829a:201 |
| OD-292-3 | 2026-10-27 | (a) Crash-line recovery is an operator-confirmed acceptance recorded in the anchor store by a verifier CLI subcommand (task 293); moving the segment aside is not the recovery. | Not stated in PLAN.md. | PLAN.md@5beb829a:201 (also 149) |
| OD-292-4 | 2026-10-28 (re-decided) | (b) Rule 6's boundary scan stops for a writer when the anchor store holds a `SHUTDOWN` checkpoint for that instance at the day's last hashed record; without one it scans on to the end. | Not stated in PLAN.md. | PLAN.md@5beb829a:201 |
| OD-292-5 | 2026-10-27 | (a) A forged record placed after a `SHUTDOWN`, beyond where the scan stops, is accepted for 292. | The verifier should report such a record; no brief yet, and 292 adds no code for it (line 206). | PLAN.md@5beb829a:201 (also 206, 287) |
| OD-292-6 | 2026-10-27 | (a) A `SHUTDOWN` is a lower bound, not an exact match (a durable record N+1 can sit above `SHUTDOWN{N}` after a rolled-back write); with several, the highest sequence is used. | `AuditRecorder` should refuse `record()` after `close()`; no brief yet (line 206). | PLAN.md@5beb829a:201 (also 206) |
| OD-292-7 | 2026-10-27 | (a) When the day's last hashed sequence is below the `SHUTDOWN`'s, a linking next record does not resolve the writer; the scan goes on to that writer's record at the `SHUTDOWN`'s sequence, whose `eventHash` must equal the `SHUTDOWN`'s `headHash`. | Not stated in PLAN.md. | PLAN.md@5beb829a:201 |
| OD-292-8 | 2026-10-27 | (a) That record must be newline-terminated with no `\r` before it, or the day is refused. | Not stated in PLAN.md. | PLAN.md@5beb829a:201 |
| OD-293-1 | 2026-10-28 | (a) `accept-crash-line` also requires the line's `previousHash` to equal its instance's last hashed `eventHash` and its sequence to be that record's plus one. | Not stated in PLAN.md. | PLAN.md@5beb829a:202 |
| OD-293-2 | 2026-10-28 | Withdrawn. | A torn write is always `\r\n`-terminated or an unterminated tail, which the verifier already skips, so a cut inside `raw[23]` purges without the command. | PLAN.md@5beb829a:202 |
| OD-293-3 | 2026-10-28 | (a) The command also accepts an instance's first line with `previousHash` GENESIS, provided the instance has no other record in the directory and no `RETENTION_ANCHOR` (branch (ii), sequence 1 only). | Not stated in PLAN.md. | PLAN.md@5beb829a:202 |
| OD-293-4 | 2026-10-28 | (a) A third branch accepts a first-of-day line that continues exactly from the instance's highest `RETENTION_ANCHOR` (branch (iii)). | Not stated in PLAN.md. | PLAN.md@5beb829a:202 |
| OD-293-5 | 2026-10-28 | (a) The command refuses, on every branch, when the checkpoint store holds a `BOOT`, `PERIODIC` or `SHUTDOWN` checkpoint for the line's instance at or above the line's sequence. | Not stated in PLAN.md. | PLAN.md@5beb829a:202 |
| OD-293-6 | 2026-10-28 | (a) No branch requires the line's own `eventHash` to recompute; the hash is reported (status token), not required. | Not stated in PLAN.md. | PLAN.md@5beb829a:202 |
| OD-W6-1 | 2026-10-28/29 (wave 6) | (a) 290 ran after 289, and 290's Owns widened to one `SourceTree.java` edit (the `refuseForeignProperties` block in `refuseIfReadByGetters`). | Not stated in PLAN.md. | PLAN.md@5beb829a:219 (also 146) |
| OD-W6-2 | 2026-10-28/29 (wave 6) | (a) 262 owns the quickstart issuer (`TokenController`, `TokenRequest`, `IssuerProperties`, its tests, a test-scope `spring-test` dependency) and the compose server entrypoint override. | Not stated in PLAN.md. | PLAN.md@5beb829a:219 |
| OD-W6-3 | 2026-10-28/29 (wave 6) | (a) 246 owns `SourceCircuitBreaker` (`admit` and `releaseTrial`). | Not stated in PLAN.md. | PLAN.md@5beb829a:219 |
| OD-W6-5 | 2026-10-28/29 (wave 6) | `requestTimeout` is `max(PT10S, perSourceTimeout)` until 254 binds it. | Not stated in PLAN.md. | PLAN.md@5beb829a:220 |
| OD-W6-6 | 2026-10-28/29 (wave 6) | 289 describes a declared non-record user-type field as OMIT. | Records, enums and scalars never reach it. | PLAN.md@5beb829a:221 |
| OD-W6-7 | 2026-10-28/29 (wave 6) | Member-card grace applies only to members that join after this instance is already serving. | Not stated in PLAN.md. | PLAN.md@5beb829a:222 |
| OD-W6-8 | 2026-10-28/29 (wave 6) | The grace clock is cluster-wide; its reset clause is superseded by OD-W6-9. | Not stated in PLAN.md. | PLAN.md@5beb829a:222 |
| OD-W6-9 | 2026-10-28/29 (wave 6) | (a) The grace clock resets only after zero unverified members have been seen continuously for at least GRACE. | Not stated in PLAN.md. | PLAN.md@5beb829a:222 |

## Accepted residuals

Lines in `PLAN.md` at 5beb829a that record an accepted residual, accepted risk or accepted current behaviour. Lines that only list unfixed Lows or follow-ups (for example lines 380, 459, 1093) are not residuals in this sense and are left in `PLAN.md`. Where the same line is also a decision row above, it is listed again here.

| Id | Date | Residual accepted | Reason | Recorded |
|---|---|---|---|---|
| OD-285-1 | 2026-10-27/28 (wave 5) | A release after a `forget` or `endScope` on another member, followed by scope-id reuse, can still subtract from the new scope's counters (clamped). Accepted until 226 adds stored scope generations. Whichever task first wires `forget` or `endScope` into production must close this first, or depend on 226. (OD-285-2 and -3 extend it; see the decision rows.) | Not stated beyond the dependency on 226 (also line 207). | PLAN.md@5beb829a:198 (also 207) |
| OD-288-3 | 2026-10-27 | Trust model: adapter code is trusted deployer code. User callbacks that reach the walk through JDK objects are a documented residual: they can stall a request, exhaust memory or weaken the leak oracle, but cannot leak data. | 288 guarantees three things: no direct call on a user class; one tree built and scrubbed from; `prohibited` throws nothing but a `VirtualMachineError` and is bounded in depth. This reverses the earlier goal that the walk run no user code at all. | PLAN.md@5beb829a:176 |
| 210 FactoryBean | not stated | A `FactoryBean` with a null object type has its product created whenever a setting is in use; if `getObject()` throws, startup fails with that exception. | Not stated in PLAN.md. | PLAN.md@5beb829a:211 |
| 254 FactoryBean guard | not stated | The raw `FactoryBean` guard is a per-bean watcher plus a post-singleton check. A post-processor that deliberately replaces a guarded bean under its own name is a residual (operator code); Spring's own type check can create a raw factory half-wired before the guard. | Not stated in PLAN.md. | PLAN.md@5beb829a:339 |
| 272 interrupt | not stated | A forwarded interrupt does not stop an adapter blocked on a plain `java.net.Socket`, so an adapter on a blocking-socket client must set a read timeout. | The quickstart already does; 261 and 213 say so. | PLAN.md@5beb829a:293 |
| 297 hung disk | not stated | Writes run on an internal thread, so a hung disk holds a request uninterruptibly. | Accepted as fail closed. | PLAN.md@5beb829a:300 |
| 293 second line | not stated | A second, different line for the same instance and sequence is accepted. | That needs an operator's action, and refusing it needs a new condition. | PLAN.md@5beb829a:327 |
| 240 enum body | not stated | An enum constant body's `@Chainable` is not visited. | Not stated in PLAN.md. | PLAN.md@5beb829a:386 |
| 248 describer | not stated | A decorator around `ValueTokenSource.unavailable()` reports as available; `Optional` content is judged by the type-level lookup; `Optional<List<Record>>`'s element path is not described; `profileChangesDescription` only asserts `isNotEqualTo`. Also the scrubber-side leak behind GHSA-f94h-6mxv-c957: exposure accepted by the owner. | Not stated in PLAN.md. | PLAN.md@5beb829a:446 |
| 245 test edit | not stated | `ReidentificationServiceTest.aClusterRefusalWithTheAuditSinkDownStillRefusesAndResolvesNothing` was edited because it pinned the old answer; it now expects `AUDIT_UNAVAILABLE`. | 245's brief said existing tests pass unmodified; this is the one accepted deviation. | PLAN.md@5beb829a:487 |
| 0.8 cutover fence | not stated | Every serving 0.8 member fences 0.7 members by keeping key `all` = TRUE in `dataprism.oversight`. Residuals: anyone on the network can delete the fence until it is re-asserted, and plain-Java embedders using `ToolAdmission.none()` are not fenced. | Recorded among the owner decisions for `docs/migration-0.8.md`. | PLAN.md@5beb829a:498 |
| T10, T17 (links) | not stated | The owner accepts residual risks T10 (key compromise before destruction decrypts links left in transcripts or provider logs) and T17 (length bucket leak). | OD-1 (a'): the real key travels in the link, AEAD-sealed under a dedicated short-lived link key, padded to 64-byte buckets (max 256). The key-destruction procedure goes into the 0.8 operator docs. | PLAN.md@5beb829a:626 |
| Rate-limit token | not stated | An approval-required call still consumes a rate-limit token. | Documented as accepted current behaviour. | PLAN.md@5beb829a:830 |
| Backup count 1 | not stated | The maps have backup count 1, so losing an entry's owner and its backup together loses it. For a pause flag, that reopens a paused path. | Documented by 135 and pinned by 134. | PLAN.md@5beb829a:932 |
| 160 checkpoint tail | not stated | An attacker who can write the checkpoint file can label a deletion as "not tampering", which is still noisier than an outright delete; a CRLF-converted checkpoint file reports every line torn (exit 4). | Owner chose the narrow torn rule (only `\r`-ended lines and a final unterminated unparseable chunk are torn). To be documented. | PLAN.md@5beb829a:1296 |
| Pending-approval cap | closed 2026-10-07 | Default 5 live pending approvals per requester, counted separately for tool-call and re-identification approvals; the operator surface has no rate limit; `TOO_MANY_PENDING` maps to HTTP 429. | Planner's choice (task 120), accepted by the owner. | PLAN.md@5beb829a:1400 |
| 210 tokenTrusted | not stated | A deployer bean that is itself `tokenTrusted()` is accepted until 210 makes the choice an explicit setting. | Not stated in PLAN.md. | PLAN.md@5beb829a:459 |

## Decisions and residuals moved out of PLAN.md on 2026-11-10

When `PLAN.md` was cut to open work, every line of the cut text that records an owner decision, an accepted residual or an accepted risk, and that is not already cited above, was copied here word for word. The match was mechanical (lines containing "residual", "accepted", "decided", "Decision", or an owner decision, choice or approval), so the list also holds some lines that only mention such a word. Each bullet starts with its line in `docs/plan/archive/PLAN-2026-11-10.md`, which is `PLAN.md` at `3dbc3324`. Groups follow the nearest heading in the archive. Decision ids are not repeated here when the rows above already carry them.

296 lines in 12 groups.

### Archive 79: 0.7.1 plan (owner decisions 2026-10-21)

- `archive:81` Found after 0.7.0: a user-generic element type (a collection whose type argument is a user generic class) lets the scrubbing engine treat fields as unclassified in a way that fails open. The owner decided:
- `archive:84` 0.7.1 is cut from `v0.7.0` with 265, 267, 244, 245, 257 and 268, through the release brief 266. Everything else stays in 0.8.0. 267 (records inside nested containers) was added by a second owner decision and 268 (embedded Tomcat 11.0.27, for three CRITICAL Tomcat advisories) by the coordinator, both on 2026-10-21. The tasks are cherry-picked from `main` after they merge there; their briefs carry a "0.7.1 backport" note.
- `archive:85` Open owner decision, for the 0.7.1 release: whether to publish a GitHub security advisory for 0.7.0.

### Archive 131: 0.8 plan (agreed 2026-10-20)

- `archive:133` **Superseded in part by the 2026-10-23 re-plan below (owner decision (b)); the counts and the 264 note here are historical.** Task 215 (sealed cluster) and the core planning produced 42 briefs: 213, the sealed-cluster tasks 218 to 231 and 264, and the core tasks 238 to 263. Each brief carries its dependencies and a **Wave** line; 215's Outcome (now in `tasks/retired/`) has the sealed-cluster table, superseded by the table below. The waves used to count 203 and 206 to 211 as landed. A planner audit on 2026-10-23 found that only 209 landed: 203, 206, 207, 208, 210 and 211 have no commits, branches or code on `main`. They are now placed in the table by their Depends-on edges, and every open brief's **Wave** line says so. The superseded briefs 204, 205 and 212 and the landed 215 are retired. 34 briefs remain to build for 0.8.0; the owner decided on 2026-10-23 to cut none of them (see under the table). No decisions remain open for 0.8: the 34 in `tasks/OD-08-core-open-decisions.md` (OD-08-1 to 18) and `tasks/OD-215-open-decisions.md` (OD-215-1 to 16) are recorded as decided. 264 is the one brief the owner accepts slipping to 0.9.0; its fallback is agreed (links refused only on members joining via `tcp-ip` or `kubernetes`).
- `archive:135` **Re-planned 2026-10-23 for owner decision (b)** (D-RM-1 (a) as amended, above). Deferred past 1.0 and moved to `tasks/deferred/`: 224, 226, 229, 230, 231 and 264. Kept: 227 and 228, and 225 (restored by a second owner decision the same day; wave 5), none of which depends on a deferred brief. Folded into 227 (wave 6 after the review of PR #164): a public gated accessor `PrivacyCluster.servingInstance()` that every Data Prism store uses, with an ArchUnit rule, because the unsealed stores never call `Sealer`; `instance()` stays ungated, so embedders see no break. 284's `trusted-network: true` is the operator's isolation assertion (only Data Prism members reach the member port), stated in its refusal, metadata, docs and an INFO line, and the Kubernetes member-port NetworkPolicy is pinned by a test. New: 284 (Spring trusted-network guardrails, wave 6), 285 (unsealed `reserve` and `release` on `HazelcastScopeBudget`, which 246 needs, taken from 226; wave 5), 286 (the `clusterSecurity` readiness contributor and the cluster-wiring defects 230 carried; wave 10), and 287 (the same trusted-network rule on the plain-Java entry points; wave 8). Re-wired: 246 depends on 285, not 226; 211 and 255 gain serialisation edges (284, 286); 260 drops 230 and 264; 261 drops 230; 263 drops 231 and 264; 213 drops 218 to 231 and 264 except 227 and 228. 264's fallback (c), core 255's `MISSING_SHARED_LINK_LIMITER` on joining members, is the 1.0 behaviour. 29 briefs remain to build for 0.8.0: the 31 open after wave 4, less the 6 deferred, plus the 4 new.
- `archive:137` Order by wave (briefs in a wave can run in parallel when their owned files do not overlap; recomputed 2026-10-21 for the 0.7.1 edges: 245 moved to 4, 248 to 4, 262 to 5, 265 added at 3, 266 added at 5; recomputed 2026-10-23 from every open brief's Depends on, with 203, 206, 207, 208, 210 and 211 as open: 203 and 206 join wave 4, 207 and 210 wave 5, 208 wave 6 and 211 wave 7; 262 moves to 6 and 256 to 7, because 262 waits for 210 and 256 for 208; nothing at wave 8 or later moves. Owns were intersected pairwise inside every wave and no two briefs in one wave share a file. 213 and the sealed-cluster briefs 223 to 231 now carry **Wave** lines too, matching this table; 266, the 0.7.1 cut, is retired and no longer listed; recomputed 2026-10-27 for owner decisions 1-4 below (270 retired; 288 joins wave 5, 289 and 290 wave 6); recomputed again 2026-10-23 for owner decision (b): the six deferred briefs leave the table, 225 stays at 5, 284 to 287 join it, 260, 261 and 263 move from 13 or 14 to 12, 213 from 14 to 13, and (after the review of PR #164) 227 from 5 to 6 and 228 from 6 to 7; nothing else moves, and Owns were intersected pairwise again in every wave, every path on every bullet, with no shared file):
- `archive:166` Owner decisions of 2026-10-27 on PR #161 (the scalar-record re-plan). Task 270 is retired; its repros are 288's acceptance corpus.
- `archive:169` 2. **Explicit authorisation wiring (210 as amended).** The `CaseAuthorizer` and the `ClientAllowlist` each need a bean or an explicit opt-out property. Spring's single injection is used. The release gate, the bean-guessing lookup and the `AMBIGUOUS` and `NON_SINGLETON` codes go. This REVERSES the 2026-10-24 decision, recorded on `wave/0.8-wave4`, that an unset allowlist means unrestricted. Planner's Spring probe, 24 contexts: `getObject()`, direct injection and `resolveNamedBean` resolve identically; prototype and unproxied custom scopes are non-singleton; a scoped proxy of the final `ClientAllowlist` fails in Spring.
- `archive:170` 3. **PR #163 was held** until 289 and 290 had merged into `wave/0.8-wave4`; both have (PR #179 on 2026-10-28, PR #184 on 2026-10-28), so no task on the hold list remains (updated by the wave-6 record). What is left before #163 can merge is the owner's own timing and its review state, plus the wave-6 record PR landing in the wave branch first. 210 (PR #168) and 288 (PR #170) merged on 2026-10-27, which left 289 and 290 on the list; both merged on 2026-10-28. 271, 207, 225 and 285 also merged into `wave/0.8-wave4` (PRs #167, #166, #169 and #171) and ride into `main` with #163. 289 and 290 base on `wave/0.8-wave4`, each with its own PR targeting it. 0.8.0 is not tagged before #163 merges, and there is no release gate any more.
- `archive:171` 4. **The name-swap leak in released 0.7.x and earlier** (a `@JsonProperty` swap, or a swapping class-level serializer, on a source record) is documented only, as a known-gap note in the 0.8.0 CHANGELOG (290 carries the sentence). No advisory entry and no 0.7.x patch.
- `archive:173` 6. **Maps of records and nested records are also refused at scrub time** (288).
- `archive:182` 15. **New refusal code `SOURCE_VALUE_READ_FAILED`:** a source whose read throws is refused alone, and the other sources still answer.
- `archive:185` Follow-ups from the re-plan:
- `archive:188` 246 owns a `SourceFanOut` root-type check: an adapter that returns a record other than its `responseType()`. Refused `POJONode` and user-class contents also do not reach the prohibited set.
- `archive:189` The earlier item "an older `SOURCE_MODEL_*` refusal thrown from `prohibited` fails the whole request" is closed by 288: `prohibited` never throws.
- `archive:190` Existing bug on wave4 and `main` (filed 2026-10-27 for the coordinator to plan; no task file yet): an interrupted request thread poisons the audit sink. `FileAuditSink.recordLine` (`FileAuditSink.java:158-163`) and `SegmentedFileAuditSink.java:117-119` write through a `FileChannel`, and `FileChannel.write` on an interrupted thread closes the channel and throws `ClosedByInterruptException`. The sink then stores `poisonedBy`, and every later request is refused with `AUDIT_SINK_POISONED` until a restart. `SourceFanOut.java:158-161` restores the interrupt flag on a request thread, so one interrupted fan-out can trigger it. Fix direction: the audit write must survive an interrupt, for example by clearing the flag and restoring it around the write, or by a write path that cannot be interrupted. A genuine I/O failure must still poison the sink.
- `archive:192` Wave 5 (207, 210, 225, 271, 285 and 288) landed 2026-10-27 and 2026-10-28, each as its own PR into `wave/0.8-wave4`: #166 (207), #167 (271), #168 (210), #169 (225), #170 (288), #171 (285). The Outcomes are under `tasks/retired/`. Decisions recorded with this wave, in the owners' words where a brief or PR body carries them:
- `archive:194` **OD-W5-1 (a):** wave 5 branched from and merged into `origin/wave/0.8-wave4`, not `main`. PR #163 (wave4 to `main`) was on hold at the time; `origin/main` was still 5887e3b3 when wave 5 landed. Wave 5's record is therefore PR record/0.8-wave5 into `wave/0.8-wave4` (it merges `origin/main` first, docs only, so it edits the current plan), and `main` receives it with #163. The hold list for #163 was then 289 and 290 (item 3 above; both merged in wave 6); the wave4 PRs it will carry are #166, #167, #168, #169, #170 and #171 on top of its own six tasks.
- `archive:199` **Owns widened during the wave:** 210 took `data-prism-security` (above); 288 took `SourceValues` (`collect`, `addScalars`, `binaryText`) and the `ArchitectureTest` getter exemption (coordinator).
- `archive:217` Wave 6 (208, 227, 246, 262, 284, 289 and 290) landed 2026-10-28 and 2026-10-29, each as its own PR into `wave/0.8-wave4`: #178 (208), #179 (289), #180 (246), #181 (284), #182 (262), #183 (227), #184 (290). The Outcomes are under `tasks/retired/`. The hold list for PR #163 was 289 and 290; both have merged, so nothing on that list holds #163 now. 247, 291 and 292 still base on `main` after #163 merges, so #163 is now the gate for wave 7. Decisions recorded with this wave, in the owner's words where a PR body carries them:
- `archive:223` **Orchestrator calls, recorded as such.** 208: recognisers also run on property names, and the interrupt flag is not restored, to protect the audit sink (superseded 2026-11-06: task 297 removed that reason; the wave-8 follow-ups ask to reconsider it). 284: an enabled `MemberAddressProvider` or advanced network config is refused (`PUBLIC_MEMBER_INTERFACE`). 227: per-wrapper `MemberCards` plus a shared `Declarations` object, accepted in place of one shared card object. 246: the leftover counts live per `SourceFanOut` instance. Owns widenings accepted as each PR body lists them.
- `archive:224` **Harness fix approved (done as 294):** the owner approved a small task to fix the flaky Hazelcast test-harness port selection ("no free loopback ports after 3 attempts", hit three times in CI: #169 and #171 in wave 5, and PR #182's Java 21 run in `HazelcastReservationTest.start:52`) and `Q01InboundAllowlistSpikeTest`. It replaces the "Test harness" follow-up under wave 5. Done as task 294 (PR #196).
- `archive:262` Wave 7 (211, 228, 247, 256, 292 and 294) landed 2026-10-29 to 2026-11-04, each as its own PR into `main`: #188 (211), #189 (247), #191 (228), #192 (292), #193 (256), #196 (294). Outcomes are under `tasks/retired/`, and the history entry is "2026-11-04 — 211, 228, 247, 256, 292 and 294". 228 finished at contract version 2.45. 295 moved to `tasks/deferred/`: it must be reissued against F1 to F5 of 228's contract before pickup. The next wave is 249, 250, 251, 272, 287 and 291. Decisions recorded with this wave:
- `archive:264` **Lock wait:** keep the 2 s cluster lock wait (`CLUSTER_LOCK_TIMEOUT` default). The 15 s wait in the race tests (#187) is a test setting only.
- `archive:265` **Central File Count:** the choice moved to the 0.8.0 release cut (see the open item near the top). The owner does not expect wave 8 to finish before 2026-11-08 and can defer the release.
- `archive:266` **Review stopping rule (2026-11-04):** code stops once a review finds nothing at Medium or above. For documents and briefs, Mediums do not block a merge and do not start another round. Lows and Nits are follow-ups.
- `archive:267` **`targetSources` label limit (247, OD-200-4):** 128 code points in 0.8 and 64 from 0.9, when `INVALID_SOURCE_NAME` refuses a longer name. The 247 acceptance line "items of length 1 to 64" is superseded by PR #195.
- `archive:268` **Owns widenings accepted:** 211 (the `AutoConfiguredBeanInventoryTest` count and the contract-register rows), 247 (`McpHttpEndToEndTest`, `SourceAliasingTest`, `ToolCalls.java` and the tool call sites), 292 (`AuditRetentionBreakParityTest` and `AuditRetentionSizeCapTest` changed expectations; rule 6(b) of the brief, line 109, is stricter than the old behaviour), 228 (the PR #198 and #199 amendments).
- `archive:272` Audit: record "held locally, write did not land" for a failed operator pause, instead of a denial (from 228, owner decision 199-22). New task candidate for wave 8.
- `archive:293` Wave 8 (249, 250, 251, 272, 287, 291, 296, 297 and 298) landed 2026-11-05 and 2026-11-06, each as its own PR into `main`: #207 (249), #209 (250), #208 (251), #206 (272), #210 (287), #204 (291), #203 (296), #214 (297), #213 (298). Outcomes are under `tasks/retired/`, and the history entry is "2026-11-06 — 249, 250, 251, 272, 287, 291, 296, 297 and 298". Tasks 299 and 300 were filed from the wave-8 review and are release obligations. The next wave is 293 and 299 (wave 9), then 252, 254 and 300 (wave 10), 253 and 286 (11), 255 (12), 260, 261 and 263 (13) and 213 (14). Decisions recorded with this wave (owner decisions marked; the rest are coordinator calls under the owner's standing instruction to decide what is clearly correct):
- `archive:296` **Owner:** tool input validation moves into the handlers (task 300), so every attempt is authenticated, audited and counted. The reshaped design was chosen over amending the SDK-validation design.
- `archive:297` **Owner:** 0.7.x gets a security advisory (draft GHSA-hcfg-rxg3-mgw6, not published) and no 0.7.2. Work goes on to 0.8.0.
- `archive:298` **287:** the rule fires on "reachable beyond this host", not "joins others". `launch` judges the addresses the sockets really listen on after start, and a wildcard refuses with `PUBLIC_MEMBER_INTERFACE`.
- `archive:299` **250:** only ALIAS labels are in `emitted`, and a shaped real name refuses. Unexpected failures report `REQUEST_FAILED`. Generated descriptions stay within 240 characters. With human approval on, each `list_sources` page needs its own approval, bound to the page token.
- `archive:300` **249:** root leaves are guarded and `REMOVE` fields are exempt. A structure at a root leaf now refuses `NESTED_LEAF_NOT_SCALAR`, not `UNCLASSIFIED_STRUCTURE` (CHANGELOG has it; 261 carries it). A single `config-location` is never split at commas.
- `archive:301` **251:** `tokenExpiry` is dropped from `LinkIssuing`. `OmitReason.TOO_LONG` is unreachable at legal settings and is kept as a fail-closed branch.
- `archive:303` **Design documents and the contract register** were checked against the merged code in this record: `sealed-links.md` rules 2 and 7 and the expiry text, `catalog.md` (the `entityType` audit, the three new audit reasons, and a note on task 300 at the "one event per call" line), `batch-and-bulk-follow.md` (the same note), `cluster-security.md` OD-10 and the 4.2 row (the reachability rule as built), `contract-register.md` (`SOURCE_TREE_TOO_LARGE`, `PAGING_UNAVAILABLE`, the three `CATALOG_*` reasons, the three cluster codes, the `AUDIT_SINK_POISONED` citation, `NESTED_LEAF_NOT_SCALAR`, and metric rows for `links.issued` and `links.omitted`), `architecture.md` (connectors-rest depends on validation and orchestration) and one sentence of `configuration.md`. Task 300 owns the `TOOL_INPUT_INVALID` register row (its brief says so), so it is not registered yet.
- `archive:321` Wave 9 (293 and 299) landed 2026-11-06, each as its own PR into `main`: #217 (293) and #216 (299). Outcomes are under `tasks/retired/`, and the history entry is "2026-11-06 — 293 and 299". The next wave is 252, 254 and 300 (wave 10), then 253 and 286 (11), 255 (12), 260, 261 and 263 (13) and 213 (14). Decisions recorded with this wave (coordinator calls under the owner's standing instruction):
- `archive:323` **293:** acceptance applies to rule 4 only. The rule-6 skip was dropped, because a genuine accepted line never trips rule 6, so the skip only loosened checks. Lines 50, 215, 228 and 304 of the brief are superseded (stated in its Outcome).
- `archive:324` **293:** partial-day recovery tolerates only an exact accepted line, by segment name, offset and hash, so a failed delete cannot block purge for ever (external review finding).
- `archive:325` **Contract register** rows added for `CRASH_LINE_ACCEPTED`, `accept-crash-line`, exit code 6 and `AuditCheckpointSink.crashLinesAccepted()`; the `CatalogQueries.java` anchors moved (421, 433, 438) with the constructor change. No design document describes retention rules 4 and 6 or the crash-line flow outside the briefs, so none needed a correction; the operator paragraph for `docs/audit.md` is still owed by 213 (the CHANGELOG entry has the text).
- `archive:335` Wave 10 (252, 254 and 300) landed 2026-11-06, each as its own PR into `main`: #219 (252), #221 (254) and #220 (300). Outcomes are under `tasks/retired/`, and the history entry is "2026-11-06 — 252, 254 and 300". The next wave is 253, 286, 301 and 302 (wave 11), then 255 (12, behind 301), 260, 261 and 263 (13) and 213 (14). Decisions recorded with this wave:
- `archive:337` **Owner:** input validation moved into the handlers and SDK validation is off on both transports. A malformed `tools/call` envelope and an unknown tool name are still refused unaudited by the SDK. A schema-invalid call always refuses, even with a genuine token. `page` reaches the gate as it is only when the value itself breaks the schema.
- `archive:338` **252:** a source may declare `namespaceTypes`. A multi-type source with no mapping is not a link target: no link at issue, `RELATION_UNDECLARED` at follow. This overrides `key-resolvers.md:67,138`, `sealed-links.md:441` and `catalog.md:54,314-315`, which are corrected in this record.
- `archive:339` **252:** a low-entropy chained key equal to a `@NonSensitive` value refuses, as the only fail-closed reading.
- `archive:340` **252:** one finishing step for every successful follow return. A deadline before any read reports `TIMED_OUT`. A follow's deadline holds across the reservation for translated follows. `budget.reserve` itself has no deadline. A direct follow gets the normal request timeout.
- `archive:342` **Design documents and the contract register** were corrected against the merged code in this record: `key-resolvers.md` (type eligibility, in the declaration and in the direct-or-translated rule), `catalog.md` (the `SourceDescription` block, the `inputKeys` row and the audit note on task 300), `sealed-links.md` (rules 1 and 6, and two amendments under the audit reason table), `batch-and-bulk-follow.md` (the task 300 note and the `MISSING_PAGING_KEY` row), `cluster-security.md` (the two `_SHARED` rows now include the link keys), `contract-register.md` (`TOOL_INPUT_INVALID`, `namespaceTypes`, `MISSING_PAGING_KEY` replacing the drafted `PAGING_KEY_REQUIRED`, the two `_SHARED` rows, a `max-sources` note) and `docs/extending.md` (`description()` and the mapping). `docs/extending.md` had no text on `description()`, so that is an addition, not a correction.
- `archive:368` Owner decision 2026-10-23 (on the coordinator's recommendation): no cuts from 0.8.0. 207 (size-capped audit segments) stays. 264 (shared Hazelcast link limiter) stays, with its agreed fallback: if it is not ready, links and page links are refused only on members joining via `tcp-ip` or `kubernetes`, and 264 slips to 0.9.0. The count stays 34. Superseded in part later the same day by owner decision (b) (D-RM-1 (a) as amended): 264 and five other sealed-cluster briefs are deferred past 1.0, not cut, and 264's fallback is the 1.0 behaviour.
- `archive:382` 221 residuals (no brief yet; fold into 222 or 227 when next editing the membership watch): bound the legacy-check thread pool or skip a check while one runs; a late check must not overwrite a newer count; comment that the reply-path test uses a test-only processor.
- `archive:390` An external review of PR #154 (the wave-3 record) was addressed in the same PR: 4 Medium, 19 Low and 4 Nit findings, fixed on `fix/222-external-review` and `fix/242-external-review` and described in the two Outcomes under "External review of PR #154 — fixes". Its follow-ups for later tasks are in the list below and the dated Context bullets of 251, 252, 254, 255 and 264: gate on `refused.counted()`, issue time and link id from the clock and a random source, `LINK_KEY_UNRESOLVED` from `forScope` and `attemptCounterKey`, a stable counter key across rotation, check-then-record, a bounded limiter map, per-id provider calls in `heldKeyIds`, and the pseudonymisation key through the same provider.
- `archive:392` A second review round on PR #154 (4 Medium, 13 Low, 4 Nit; 2026-10-23) is answered on the same branches. New follow-ups for 251, 252 and 255 are in their Context bullets. The limiter findings belong to task 269 (wave 4 row), which replaces `attemptCounterKey`.
- `archive:399` 242, owner decision: the `Optional` record components (`FollowPayload.resolverName`, the `CatalogPagePayload` filters, `LinkSettings.lifetime`) need a rule in `docs/conventions.md`, which has none.
- `archive:405` [security] 227: a keyless member accepts `localhost` as `localAddress`, and `PrivacyCluster.launch()` never checks the bound address (`using()` does). Coordinator decision 2026-10-23: a keyless member's `localAddress` and `publicAddress` must be a literal loopback address (`127.x.x.x` or `::1`); `localhost` is no longer accepted, because it is resolved through the hosts file. Acceptance items in 227.
- `archive:430` 264 (deferred past 1.0; kept for that milestone): the per-principal attempt counter splits across key ids in rotation phase 2, so decide whether to accept that or key the counter on something stable; the window is decided by the stored start and the clock, not by entry expiry.
- `archive:436` Planning gap: closed 2026-10-23. The planner audit confirmed that 203, 206, 207, 208, 210 and 211 are not started and that 209 landed. They are placed in the wave table above; 211 lands at wave 7, before 254 (9) and 255 (11), so the link-key and audit-key briefs keep their waves. 211, 254 and 255 now own `ClusterKeyResolution.java`.

### Archive 540: 0.8 backlog (carried from 0.7)

- `archive:553` Owner decision D-0.7-AUD-1 (accepted 2026-10-20): fix unaudited approval decisions before 0.7.0, through task 235, using design (a), audit-first. Landed 2026-10-20.
- `archive:555` Owner decision D-0.7-OPS-1 (accepted 2026-10-20): the operator `ApprovalView` carries an effective `status`, with a stored PENDING at or past expiry shown as EXPIRED (task 236). It answers a PR #137 review finding and makes the retry check in `docs/multiple-instances.md` work. Landed 2026-10-20.
- `archive:604` Owner decisions, all dated 2026-10-19:
- `archive:607` D-1.0-5 rescheduling CONFIRMED by the owner: the 1.0 controls (case authorisation and client allowlist 203/210, keyed audit and segment caps 206/207/211, FREE_TEXT and recognisers 208, controls and rotation docs 213) ship in 0.8.0. The pattern-validator wiring (209) is a 0.7 correction. The annex (214) is 0.9 evidence. 1.0 remains a stability release, with the freeze at 0.9. Task 213 is blocked until 215 names concrete cluster implementation dependencies.
- `archive:608` Task 214 legal scope is generic: the annex stays on the GDPR and the EU AI Act, and its only mention of other regimes is the line "Deployers whose processing falls under a separate law-enforcement or sector-specific regime must assess it themselves." The earlier jurisdiction-specific citation is removed.
- `archive:609` D-0.7-HZ-1: Hazelcast approval creation is reworked to be atomic (task 216), and bounded leases are restored. The retained (infinite-lease) locks of commits 34adde0b and 08939a79 close real races but contradict `cluster-security.md` and task 202's "lease derived from the timeout", so 216 supersedes them (landed 2026-10-20).
- `archive:610` D-0.7-HZ-2: after an external review of PR #134 found what our review missed, all findings from the whole-component bug hunt are fixed, with creation tokens on approval entries, claims and pending slots (task 232, landed 2026-10-20). Owner's words: "Fix all, with creation tokens".
- `archive:611` Task 198 design (`design/batch-and-bulk-follow.md`): the owner decided OD-B12 in part (truncation option (i): off by default while links are off, never truncate without a `next` link; the default link `max-length` is raised to 1024, replacing the 768 task 191 decided), OD-B18 (a per-source `REFUSED:RESPONSE_TOO_LARGE` replaces task 193's whole-call refusal for a source that cannot be truncated, with the hard overall cap as backstop) and OD-B24 (b) (a bulk link whose target is `REFUSED` is audited `DENY:<code>`, aligned with `follow`). The owner delegated every other OD-B1 to OD-B24, and the mechanism part of OD-B12, to the recommendations.

### Archive 618: 0.7.0 state (updated 2026-10-09)

- `archive:620` Follow-ups from task 190: re-capture the two converted HTTP example blocks (`docs/agents/remote-http.md`, `docs/protect-your-own-api.md`) against the Compose stack before the 0.7.0 release. For task 201's migration notes: a 0.7 log carrying `REFUSED:<code>` cannot be read by a pre-0.7 verifier, while old logs verify with the new one. The custom-validator SPI change (the final pass validates an array root, not an object) is Breaking and belongs in the same notes.
- `archive:622` Landed on `plan/0.7.0`: task 188 (supply chain), task 189 (limits from configuration), task 191 (sealed-links and key-resolver design), task 192 (cluster-security design) and task 195 (mutation testing, report only) task 197 (catalog design), task 190 (per-source results) and task 202 (Hazelcast approval consume, split-brain protection, lock timeouts), then on 2026-10-19 tasks 193, 194, 196, 198 and 217, and on 2026-10-20 task 216 (atomic approval creation). Tasks 200 and 201 have since landed too; nothing 0.7 is open except the release gate above.
- `archive:624` Coordinator interpretation of D-070-15: "approvals in use" means `dataprism.oversight.approval-required-tools` is not empty OR `dataprism.reidentification.enabled` (re-identification four-eyes uses the same approval store). The kubernetes rule and every quorum check apply only then; otherwise no quorum is computed or required.
- `archive:626` Design outcomes (documents in `docs/plan/design/`: `sealed-links.md`, `key-resolvers.md`, `cluster-security.md`; both approved by security review; the owner delegated every open decision to the recommendation on 2026-10-09, recorded in the documents as "Decided (owner delegated)"):
- `archive:629` Links are bound to the caller and off by default (`dataprism.links.enabled=false`) through 0.9. The failed-attempt limiter counts only pre-authentication failures, per principal, 20 per PT10M. Link-key rotation has three phases. Maximum link length is 768 under (a'); the owner raised it to 1024 on 2026-10-19 (OD-B12 of task 198). One link per collection element.
- `archive:630` Key resolvers: `KeyResolver` SPI returning `Resolution(keys, more)`, resolved at follow time, cap 5, beans only. Audit shape C: `REFUSED:<code>`-shaped values only; `docs/audit.md` documents the new keys in 0.8.
- `archive:631` Cluster (192): seal what is stored in Hazelcast (AES-GCM values, HMAC'd keys under a non-rotating name key) and advise transport protection. Isolation-only multi-member clusters are not allowed. A signed, sequenced, leased pause document is updated by CAS with union merge on MERGED; a stale or absent pause refuses every call. Cluster-key rotation is phased with sealed member cards. Budgets and rate limits keep a per-instance floor. Separate secrets per purpose.
- `archive:632` OD-13: the 1.0 claim is the qualified statement. Sealed against reading and forging; not against deletion, short replay or availability; transport protection is the deployer's and unverified.
- `archive:633` OD-16: `PrivacyCluster.using` refuses in sealed mode when it cannot enforce settings.
- `archive:635` Catalog design (197, `docs/plan/design/catalog.md`, approved after two review rounds). OD-C2 (a) was decided explicitly by the owner on 2026-10-09; OD-C1 to OD-C19 otherwise were delegated to the recommendations:
- `archive:637` OD-C2 (a): reserved audit key `names` takes `REAL` or `ALIAS`. This deliberately reopens OD-13 shape C for that one key only.
- `archive:638` OD-C3: per-scope real names through `dataprism.catalog.real-names-scopes`, a scope-pattern list, empty by default.
- `archive:639` OD-C5: `list_sources` allows all in 0.8 only; deny-by-default from 0.9 per D-RM-11. Say so in the 0.8 release notes.
- `archive:640` OD-C7: `dataprism.catalog.unselected-source-limit`, default 8, with a startup check that it is at most `max-sources` (`INVALID_CATALOG_SETTING`).
- `archive:641` OD-C16: `list_sources` is paged in 0.8; task 198 specifies that page first.
- `archive:642` OD-C18: source-name grammar `[a-z][a-z0-9-]{0,63}`. In 0.8 a violation raises a warning (gauge and count-only log); `INVALID_SOURCE_NAME` refuses from the 0.9 freeze.
- `archive:643` OD-C19: any unresolvable label refuses the whole call.
- `archive:645` 0.8 work items these create: catalog paging moves into 0.8 (specified by 198); the `names` audit key needs an `AuditEvent` grammar for that key only, `docs/audit.md`, the verifier and the closed-grammar test.
- `archive:647` Owner decisions for 0.7 (2026-10-09):
- `archive:649` D-070-1 (b): each `sources` entry becomes `{status, entity}`, `entity` only for `ANSWERED`; the top-level `entity` is removed. The one shape break before 1.0.
- `archive:650` D-070-2 (b): a source whose scrub or validation refuses is marked `REFUSED` and omitted; the others are returned. If every answered source is refused, the whole call is refused as today.
- `archive:651` D-070-3 (a): seven status codes (today's six plus `REFUSED`); clients treat an unknown code as "not answered" (a gap), not as an error. Corrected on 2026-10-20 by OD-200-1; the earlier text said "an error".
- `archive:652` D-070-4 (a): prefix `dataprism.limits`; keys `max-sources`, `max-records`, `max-response-size`, `per-source-timeout`, `max-concurrency`, `scope-read-budget`; defaults unchanged; positive only; `INVALID_REQUEST_LIMITS`; one `PRIVACY_CRITICAL` bean `dataPrismRequestLimits`.
- `archive:653` D-070-5 (a): a record is a source's top-level record plus each object in an array; exceeding `max-records` refuses (truncation is 0.9).
- `archive:654` D-070-6 (a): size is a conservative upper bound computed in the orchestrator before the audit event, no mapper; one `DENY:RESPONSE_TOO_LARGE`.
- `archive:655` D-070-7 (a): the key-shape scan is always on under every profile, no SPI change.
- `archive:657` D-070-9 (a): PIT on core, pseudonymisation, validation and orchestration; weekly plus manual; report only.
- `archive:660` D-070-12 (a): design documents and the contract register are internal, under `docs/plan/design/`.
- `archive:661` D-070-13 (a): the audit value for a refused source is `REFUSED:<code>` with the code sanitised. Record fields, hash-chain encoding and the `merged:<refused>` key are unchanged. 0.7 logs are not readable by a pre-0.7 verifier; old logs verify with the new one.
- `archive:662` D-070-14 (a): fix the approval double-consume and the locks-without-timeout bugs in 0.7, as task 202 (wave 2).
- `archive:663` D-070-15 (b): approval consume becomes conditional, the approval map merges consumed-wins, and split-brain protection is configured by `dataprism.hazelcast.approval-minimum-members`. Default for tcp-ip is a majority of the listed members; for none, 1; for kubernetes, required whenever approval-gated tools are configured. Refusals: `MISSING_APPROVAL_QUORUM` and `INVALID_APPROVAL_QUORUM`. Cost: in a two-member cluster, losing either member stops approval-gated tools.
- `archive:665` D-070-17: `dataprism.hazelcast.lock-timeout`, default `PT2S`, range `PT100MS` to `PT30S`, refusal `INVALID_LOCK_TIMEOUT`.
- `archive:667` Owner decisions from the contract register (task 200, 2026-10-20). The register is `docs/plan/design/contract-register.md`, section 14; each decision is recorded there too.
- `archive:669` OD-200-1 (owner, explicit): an unrecognised per-source status means "not answered". Supersedes the "an error" wording of D-070-3.
- `archive:670` OD-200-2 (owner, explicit): `CLUSTER_OPERATION_REFUSED` is surfaced to callers in 0.7, like `CLUSTER_LOCK_TIMEOUT`; cause chained and logged, no data in the message; the class moves to `core.refusal`.
- `archive:671` OD-200-3 (delegated): the per-source status is `RESOLVER_FAILED`, not `KEY_UNRESOLVED`.
- `archive:672` OD-200-4 (delegated): the sealed-link layout's length caps govern: source labels 64, namespace and resolver name 32, entity type 64, field path 64; catalog schemas follow.
- `archive:673` OD-200-5 (delegated): new capabilities `FOLLOW` and `FOLLOW_BULK`, off until a role is granted them, with an upgrade note.
- `archive:674` OD-200-6 (delegated): the `@Chainable` attribute is `keyNamespace`.
- `archive:675` OD-200-7 (delegated): metric words are separated by `_` inside a segment; `dataprism.catalog.source-names.nonconforming` becomes `dataprism.catalog.source_names.nonconforming`.
- `archive:676` OD-200-8 (delegated): `dataprism.links.max-chain-depth` and `LINK_DEPTH_EXCEEDED` are dropped (option (b) of links is rejected); `LINK_ORIGIN_DRIFT` stays.
- `archive:677` OD-200-9 (delegated): `dataprism.hazelcast.security.mode` and its two startup codes are dropped; the security reviewer is told, because the approved cluster design changes by one key.
- `archive:678` OD-200-10 (delegated): key-failure startup codes use one family, `<AREA>_KEY_WEAK`, `<AREA>_KEY_UNRESOLVED`, `<AREA>_KEY_SHARED`; task 211 uses `AUDIT_HMAC_KEY_WEAK` and `AUDIT_HMAC_KEY_SHARED`.
- `archive:679` OD-200-11 (delegated): the shipped `Authoriz*` spelling is accepted for new security types, recorded as an exception to `docs/conventions.md`.
- `archive:680` OD-200-12 (delegated): the audit key stays `names` and the result field stays `labels`; the documents say they are one setting.
- `archive:681` OD-200-13 (delegated): the shipped near-synonym codes (`MISSING_PURPOSE` and `PURPOSE_REQUIRED` and the others) stay and are documented by surface.
- `archive:682` OD-200-14 (owner, explicit): `dataprism.approval.pending.sweep-seconds` is replaced by `dataprism.hazelcast.approval-sweep-interval`, a Spring-bound `Duration`, default `PT5M`, validated at startup and pinned in the frozen property list.
- `archive:683` OD-200-15 (delegated): a metric with a closed label gets one `Metric` constant per outcome; `PrivacyMetrics` gains no label overload. Whether the `source` tag should carry an alias is a separate, undecided change.

### Archive 835: Release 0.4.1 — real, safe, honestly documented clustering (tasks 131-136)

- `archive:884` Owner decisions, open:
- `archive:938` Owner decisions, closed 2026-10-07:

### Archive 997: Release 0.5.0 — correlation ids and log-stack output (tasks 108-116, 130, 141-147)

- `archive:1027` | Done | 150 | **Done** (2026-10-07, attempt 2, D-150-A decided). Audit `entityType` verbatim only when registered or upper-case-identifier-shaped, otherwise `<unregistered>` | 148 |

### Archive 1033: Open owner questions

- `archive:1053` **Decisions from task 130 (2026-10-07):**
- `archive:1056` **D-130-B (historical, superseded 2026-10-07 by the Java 25 decision below):** the Docker images stay on Java 21.
- `archive:1057` **Java 25 (2026-10-07):** the owner approved runtime and build images on Java 25 LTS, not 26, with a CI matrix on JDK 21 and 25. Bytecode stays `--release 21`, the README states the Java 21 consumer minimum, and Dependabot allows 25 but not 26. Language level 25 is not adopted. Risks: Hazelcast's Unsafe warnings, Mockito agent warnings, a JDK TLS message-text test, and Maven image tags. Work is task 140.
- `archive:1059` Follow-ups from 130, not yet tasks:
- `archive:1062` (b) Answered. The task 130 docker `maven` semver-major ignore does not block `-temurin-26`, because Dependabot reads `3.9-eclipse-temurin-21` as 3.9.21, so `-26` is a patch update. Task 145 fixes it.
- `archive:1064` **Owner decisions on the platform, 2026-10-07** (all decided):
- `archive:1066` **D-139-A:** Stay on Jackson 2 for 0.5.0 and keep the Jackson 3 enforcer ban.
- `archive:1067` **D-139-B:** 0.5.0's starter and autoconfigure require Spring Boot 4.1. Boot 3 users stay on 0.4.x. CHANGELOG Breaking.
- `archive:1075` **Decisions on task 149, test-suite stability (2026-10-07):**
- `archive:1083` Data Prism supports MDC-based correlated logging. `dataprism.correlation.mdc-key` is unset by default, which means off.
- `archive:1084` It puts only the validated `ExternalCorrelationId.value()` into the MDC.
- `archive:1085` It is opened at the MCP tool handler, because the SDK runs sync tools off the servlet thread, and inside each `SourceFanOut` task from that task's own `DataRequest`. It never relies on ThreadLocal inheritance.
- `archive:1086` It is always cleared in `finally`, restoring any previous value. A rejected or absent id sets nothing.
- `archive:1087` Codes: `INVALID_CORRELATION_MDC_KEY`, `CORRELATION_MDC_KEY_RESERVED`, `CORRELATION_MDC_KEY_WITHOUT_HEADER`.
- `archive:1088` Whatever the inbound pattern admits appears in every log line on those threads, so the pattern should admit generated ids only.
- `archive:1089` No Micrometer or OTel propagation (C6 stays deferred), and no MDC on Hazelcast or other background threads.
- `archive:1090` The optional global `dataprism.correlation.outbound.header` is an amendment to 111 (consumption) and 113 (binding, validation, docs). A per-source correlation-header overrides it.
- `archive:1091` 115's Owns excludes `examples/log-shipping/mdc/**`, which is task 148's. 116 now depends on 148.
- `archive:1095` (a) Audit verifier wording. An unterminated last line ("POSSIBLY IN FLIGHT, not a break", exit 3), and the truncation-equivalent residuals (21-23 fields with field 20 = "2"/"3", and more than 25 fields with an unparseable field 20, both reported as "INTERRUPTED WRITE, not tampering"), should read "unverified, tampering not ruled out". Consider covering a writer's tail with `--checkpoints`.
- `archive:1152` External review of PR #117 (2026-10-08) found two P2 defects. Both are fixed and merged on the PR branch (tasks 152 and 153). The container-smoke SIGPIPE fix (e23de419, merged as fix/smoke-sigpipe) also landed on PR #117 after CI caught exit 141.
- `archive:1154` Decisions recorded for task 153 (2026-10-08):

### Archive 1199: 0.6.0 — package structure, API and style cleanup

- `archive:1244` **Wave 5 done 2026-10-08:** 167 merged onto `release/0.6.0-jackson3` (Jackson 3.1.5; 1455 tests, 0 failed on JDK 21; JDK 25 and container-smoke run in CI on the PR). Wave 6 is next: 168, 169 and 170 in parallel. Accepted or noted behaviour changes, signature changes and the pinned Jackson defaults are in the retired 167 task file's Outcome.
- `archive:1285` DONE in 179 (D-179-2 b): `ToolAdmission.none()` is one shared instance recognised by identity in `ToolOptions`, with no `isNone()`; `.admission(ToolAdmission.none(), null)` is accepted, while real policies and subclasses still need a fingerprinter. Fixture move DONE in 181.
- `archive:1288` **Owner instruction (2026-10-09), now spent: pause before any release step after 162.** The owner gave the go and the release is done.
- `archive:1294` **Wave 1 done 2026-10-08** (tasks 154, 155, 160, 165, integrated on `release/0.6.0-wave1`; task files retired to `docs/plan/tasks/retired/`). Owner decisions and notes:
- `archive:1296` 154: `audit` and `oversight` are under the core outer-layer ArchUnit rule with a baseline of 0 violations. Owner decided NOT to add a Jackson databind ban for `audit`; the existing output-shape tests cover it. Follow-up: move the fixtures into package-matching directories.
- `archive:1301` **Jackson 3 port is now IN 0.6.0.** Owner decisions J3-0 to J3-5, dated 2026-10-08. Task files are 166 to 169; the wave table above carries the order: package moves (156, 157), then Jackson 3 (166 to 169), then the Spring splits (158, 159).
- `archive:1303` J3-0: Jackson 3 port is IN 0.6.0 (owner: "let that shape everything").
- `archive:1304` J3-1: B, after the package moves (156, 157) and after 155; before the Spring splits (158, 159). Strictly separate tasks from the moves.
- `archive:1305` J3-2: A. Data-prism keeps its own fixed, private Jackson 3 mappers: the single JSON writer (`DataPrismObjectMapper`), the `SourceTree` reader and the five YAML readers, built with Jackson 3 builders. They are not Spring beans and cannot be customised by application configuration. Owner's reason: adapter authors may bring their own `ObjectMapper` for their APIs, and must never be able to reconfigure data-prism's mapper for core behaviour. The ArchUnit "only `DataPrismObjectMapper` writes" rule must be rewritten for builders, with a negative test proving it still catches a violation.
- `archive:1306` J3-3: C. Expose Jackson 3 tree types (`tools.jackson.databind.JsonNode`/`ObjectNode`) where they are the real data: `ScrubResult`, `SourceTree`, `Generalizer`, the validators, `ContextResponse` and `ComparisonResponse`. Narrow the accidental surface: `DataPrismObjectMapper.create()` and any public method that hands out or accepts data-prism's `ObjectMapper` stop being public, which reinforces J3-2. List every public signature change for the 162 migration page.
- `archive:1307` J3-4: A. A small characterisation task runs first, on Jackson 2, before the port. Golden-byte tests for the audit JSON projection (non-ASCII, U+2028/2029, control characters, surrogate pairs, escape casing), checkpoint lines, and a full tool-result response. YAML characterisation tests for duplicate keys, YAML 1.1 booleans no/yes/on/off, unknown keys, enum case and whitespace, octal-looking scalars. The port must keep all of them green, or list each difference for owner acceptance.
- `archive:1308` J3-5: A, a full flip. Enforcer: ban the Jackson 2 artifacts (`com.fasterxml.jackson.core:jackson-databind`, `jackson-core`, the `jackson-dataformat-*` and `jackson-datatype-*` artifacts), `io.modelcontextprotocol.sdk:mcp-json-jackson2` and `org.springframework.boot:spring-boot-jackson2`. CARVE-OUT: allow `com.fasterxml.jackson.core:jackson-annotations`, which Jackson 3 still uses and mcp-core needs. Lift the `tools.jackson`, mcp aggregate and `mcp-json-jackson3` bans. Spring: re-adopt `spring-boot-starter-jackson`, removing the exclusions and `spring-boot-jackson2` in the server, starter, quickstart-fixtures and quickstart-issuer poms. Task 142's `Boot4RegressionGuardsTest`: invert it to assert Jackson 3 converters and no Jackson 2. Docs: update the D-139-A decision in architecture.md (:284-290), and fix the stale "scrubbing engine is a Jackson module" wording at architecture.md:152 and conventions.md:36. The real invariant is "only `DataPrismObjectMapper` writes".
- `archive:1315` Scope added to 162: the migration page and `[Unreleased]` Breaking entry cover D-173-2 (source models must be records, `SOURCE_MODEL_NOT_A_RECORD`, `@JsonProperty`/`@JsonGetter` on non-component record methods refused) and D-173-1 (`java.time` and `Optional` now accepted as ISO text; `Date` stays millis).
- `archive:1321` Owner decisions, all decided 2026-10-08:
- `archive:1340` **Owner decision, 2026-10-06:** Dependabot stays on, with version updates
- `archive:1343` noise and queueing, not cost. C1 to C7 are all decided (2026-10-07). The

### Archive 1372: Owner decisions

- `archive:1374` D1, D2, D3, D4, D5, D7 and D8 are answered; D6 is open and informational.
- `archive:1376` D1: answered 2026-10-06. The 2026-09-08 deferral of the re-identification
- `archive:1377` operator surface is lifted. Recorded in
- `archive:1378` `docs/architecture.md#decisions-worth-knowing`.
- `archive:1379` D2: answered 2026-10-06. Task 107 dropped; the 2026-09-23 decision rejecting
- `archive:1380` a keyed chain is reaffirmed. The key would live in the operator's process,
- `archive:1381` and third-party verification would need a key that also lets its holder
- `archive:1382` forge.
- `archive:1383` D3: answered 2026-10-06. A segmented audit sink writes daily files, writes a
- `archive:1384` `RETENTION_ANCHOR` checkpoint, then deletes segments past retention. The
- `archive:1385` single-file `FileAuditSink` is unchanged. Native segment files are named
- `archive:1386` `audit-YYYY-MM-DD.log`.
- `archive:1387` D4: answered 2026-10-06. The operator surface is a second port in the same
- `archive:1388` process, not a separate JVM. Shapes 105.
- `archive:1389` D5: answered 2026-10-06. A retention below six months fails startup unless
- `archive:1390` `dataprism.audit.retention-override` is set (Art. 19 allows other periods
- `archive:1391` under Union or national law); using it is the operator's legal
- `archive:1392` responsibility.
- `archive:1393` D6: open, informational. No Art. 10(5) bias-detection profile has been filed.
- `archive:1394` Affects what 106 may claim.
- `archive:1395` D7: confirmed 2026-10-06. A checkpoint-write failure refuses all audited
- `archive:1396` calls until a checkpoint can be written again. Implemented in 97.
- `archive:1397` D8: answered 2026-10-06. Four-eyes for re-identification defaults ON. A
- `archive:1398` configured high-impact tool call is refused with `APPROVAL_REQUIRED` and an
- `archive:1399` `approvalId`; a different person approves it on the operator port; the
- `archive:1400` identical call with the same argument fingerprint then succeeds once.
- `archive:1403` default 5 live pending approvals per requester, counted separately for
- `archive:1404` tool-call and re-identification approvals. The operator surface has no rate
- `archive:1405` limit. `TOO_MANY_PENDING` maps to HTTP 429.
- `archive:1407` Owner decisions of 2026-10-06 taken during tasks 103, 118, 123 and 124:
- `archive:1409` A purge integrity failure (task 103) gives an ERROR log, metrics, and the
- `archive:1410` `auditIntegrity` health status DOWN, while serving continues. Four separate
- `archive:1411` metric names (`dataprism.audit.retention.unverified`, `.anchor_failed`,
- `archive:1412` `.delete_failed`, `.failed`) are accepted in place of one tagged metric.
- `archive:1413` Task 123 unifies every denial as `DENY:<code>`, including the MCP tools' bare
- `archive:1414` codes. A malformed code is recorded as `DENY:INVALID_REFUSAL_CODE`.
- `archive:1415` Task 124 numbers the placeholders `<undeclared-N>` alphabetically by name.
- `archive:1416` Scanning property names in the leak validators is a 0.4.x follow-up.
- `archive:1418` application `SyntheticValueSource` is wrapped like the default, and the
- `archive:1419` wrapper falls back to the wrapped source on any cache failure. There is no
- `archive:1420` `REIDENTIFICATION_INDEX_UNWIRED` refusal.
- `archive:1421` Approvers on the operator port see the pseudonym and namespace, never the
- `archive:1422` subject id.
- `archive:1423` Exposing the operator port in Docker Compose, `server.json` and the image is
- `archive:1424` a 0.4.x follow-up.
- `archive:1426` Follow-ups from the task 124 review, not yet tasks:
- `archive:1428` `UndeclaredNameToolResultScanTest` never plants an `AuditEvent`; add a
- `archive:1429` not-vacuous audit case.
- `archive:1430` The orchestrator's shallow merge collapses `<undeclared-1>` from two sources
- `archive:1431` into one key. No readable data is lost, but the count is understated.
- `archive:1433` Follow-ups from the task 120 review, not yet tasks:
- `archive:1435` `HazelcastApprovalStore`'s cap count scans all approvals under the requester
- `archive:1436` lock (O(n)); consider an index or a predicate.
- `archive:1437` The two-member contract test picks a port from `nanoTime` with auto-increment
- `archive:1438` off and does not retry on collision.
- `archive:1440` Follow-ups from the wave 4 reviews (tasks 103, 117, 118, 121, 125), not yet tasks:
- `archive:1442` `AuditEventHashTest`: add a non-ASCII pair, so that the length prefix is
- `archive:1443` pinned to byte length and not char length.
- `archive:1444` Docs: the shape guard still renders real loop-counter indices (safe), which
- `archive:1445` is inconsistent with `[*]` elsewhere.
- `archive:1446` `RefusalPaths`: `email[07700900123]` next to a declared `email` is reported as
- `archive:1447` `$.email[*]`, which can mislead triage.
- `archive:1448` Task 103: the containment refusal messages for the same code differ between
- `archive:1449` validation and the bean re-check. Also note that two `AuditSink` beans raise
- `archive:1450` `NoUniqueBeanDefinitionException`.
- `archive:1451` Task 125: `requireVersion()` is duplicated in four integration tests; consider
- `archive:1452` a shared helper.
- `archive:1453` ~~`DataPrismMcpServer.serverInfo` hardcodes `"0.3.1"`~~ — closed 2026-10-06 by
- `archive:1454` task 129: it reads a Maven-filtered version resource and falls back to `unknown`.
- `archive:1455` ~~Release time: the Dockerfile `ARG VERSION` and the `publish-image.yml`
- `archive:1456` default~~ — closed 2026-10-06 by task 129 (both say 0.4.0).
- `archive:1458` Follow-ups from the task 102 review, not yet tasks:
- `archive:1460` Document in `docs/audit.md` that a custom `AuditCheckpointSink` without a
- `archive:1461` `retentionAnchors()` implementation refuses every purge after the first.
- `archive:1462` Add a comment at `AuditRetention.java` near line 224 explaining why skipping
- `archive:1463` unparseable lines is safe: `verifySegments` runs first and refuses a segment
- `archive:1464` it cannot parse.
- `archive:1466` Follow-ups from the task 100 review, not yet tasks:
- `archive:1468` The core `ApprovalStore` needs an explicit revoke or expire path for APPROVED
- `archive:1469` requests. `ReidentificationService` rolls back an unaudited approve by
- `archive:1470` consuming it, which looks the same as a real collect.
- `archive:1471` Move `SubjectForMethodReferenceFixture` into a nested class of
- `archive:1472` `ArchitectureTest`; it sits outside task 100's Owns list.
- `archive:1473` `findPending` on `InMemoryApprovalStore` and `HazelcastApprovalStore` is no
- `archive:1474` longer used by re-identification. Check whether `ToolAdmission` still needs
- `archive:1475` it; remove it if not. Tasks 119 and 120 touch both stores, so decide there.
- `archive:1477` Follow-ups from the task 105 review (0.4.x), not yet tasks:
- `archive:1479` ~~Add a behavioural test that an MCP-port MVC error still reaches Boot's
- `archive:1480` `/error`~~ — closed 2026-10-07: task 142's `Boot4ErrorPathTest` guards the
- `archive:1481` `spring.web.error.path` operator error mapping.
- `archive:1482` `data-prism-architecture` tests log the SLF4J multiple-providers warning
- `archive:1483` (logback-classic plus slf4j-simple). Remove one from that module's test
- `archive:1484` classpath.
- `archive:1485` Operator 401s keep the Bearer `error_description` header. Informational; it
- `archive:1486` matches the MCP port.
- `archive:1488` Follow-ups from the task 128 review, not yet tasks:
- `archive:1490` `MalformedRefusalCodeTest`'s log assertion uses the throwable proxy's
- `archive:1491` `toString`; also check `getThrowableProxy().getMessage()`.
- `archive:1492` `ToolCalls` copies `approvalId` into client text unchecked. It comes from the
- `archive:1493` store, so the risk is low; consider validating its format.
- `archive:1495` Follow-ups from the task 96 review, not yet tasks:
- `archive:1497` Non-scrub refusals (budget exhaustion, `NO_SOURCE_DATA`) also get
- `archive:1498` `merged:<refused>`, which wrongly suggests a validation failure.
- `archive:1500` Follow-up from the task 122 review, not yet a task:
- `archive:1502` `ServerStartupTest.java:224-225`: the comment says a port collision makes
- `archive:1503` startup fail. With `--server.port=0` a held 127.0.0.1 port is simply never
- `archive:1504` assigned, so reword it. The 404 cause is also unproven (mechanism
- `archive:1505` reproduced only); reopen 122 if the flake returns.
- `archive:1507` Follow-ups from the task 97 review, not yet tasks:
- `archive:1509` `AuditRecorder.writeCheckpoint` clears `checkpointFailure` directly, outside
- `archive:1510` the two D7 methods. Move it into a D7 method or fix the field javadoc.
- `archive:1511` `docs/audit.md:133`: the exit-3 row's precedence text omits exit 5.
- `archive:1512` `AuditChainVerifierCli`: a malformed checkpoint file prints "could not read
- `archive:1513` <audit path>". It should name the checkpoint file.
- `archive:1514` A whole deleted boot is detectable only if that boot wrote a checkpoint past
- `archive:1515` sequence 0. Consider a periodic checkpoint soon after boot (task 103's
- `archive:1516` scheduling).
- `archive:1518` Follow-ups from the wave 1 reviews, not yet tasks:
- `archive:1520` Rename `HazelcastOversightTest`'s `sameIdCreatedConcurrently...NeverCrossApproves`
- `archive:1521` or assert approve and consume after the race; the name claims more than the
- `archive:1522` test checks.
- `archive:1523` Add a direct test for `reject()` on an ambiguous id.
- `archive:1524` Add a test pinning that undeclared object or array values are never descended
- `archive:1525` into when recording dispositions.
- `archive:1526` `data-prism-integration-tests` surefire reports "kill self fork JVM" after
- `archive:1527` about 30 seconds following `ShippedDefaultsTest`. A non-daemon thread is the
- `archive:1528` likely cause. The build still passes.
- `archive:1530` Retired from these lists on 2026-10-06 because tasks 117-122 now cover them:
- `archive:1531` the `AuditEventHash` escaping gap and the verifier's limitation text (117), the
- `archive:1532` payload-key leak on refusal paths (118), `InMemoryApprovalStore` parity and the
- `archive:1533` `HazelcastApprovalStore.java:55` comment (119), the per-requester pending cap
- `archive:1534` (120), the `REFUSED` wording and `DECISION:<code>` documentation (121), and the
- `archive:1535` `ServerStartupTest` 404 flake (122).

### Archive 2136: v0.3.0 plan (tasks 60-71) — opened 2026-09-22, none started

- `archive:2156` increment size. Owner decision: the descend-key mechanism stays entirely
- `archive:2174` log is fine" with independent verification. Owner decision on what it
- `archive:2207` **Owner decision, recorded 2026-09-23: the audit trail's threat model is a
- `archive:2236` Open question, not yet decided by the owner: the trail records
- `archive:2245` **Owner decision, recorded 2026-09-22: pseudonym discriminator widens from 20
- `archive:2293` bits, per the owner decision recorded above).
- `archive:2461` ~~1. **Blocks recording task 67 done.** `approved-sink` is still accepted by
- `archive:2469` absent-or-blank-property case and for any other accepted value reaching

### Archive 2607: Discoverability (tasks 76-84) — done

- `archive:2710` **Owner, after go-live:**
- `archive:2740` FAIL_REQUEST` only refuses~~ — resolved 2026-09-24 by owner decision:

### Archive 2900: Task 91 — done, and the 0.3.1 release is complete. No task file remains under `docs/plan/tasks/`.

- `archive:2902` Cut 0.3.1 for GHCR and the MCP Registry only — the owner decided Maven
- `archive:2964` Accepted 2026-09-25: listed at
