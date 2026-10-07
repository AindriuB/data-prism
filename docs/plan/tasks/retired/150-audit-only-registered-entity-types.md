# 150 — Audit `entityType` only when it is a registered entity type

**Repo:** `.`
**Release:** 0.5.0 (owner approved 2026-10-07)
**Depends on:** 148
*(148 owns `data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/**` wholesale, and the `dataPrismHttpTransport` call into `DataPrismMcpServer` in `DataPrismAutoConfiguration.java`. This task edits both, so it must start after 148 merges. There is no overlap with 115, which owns `examples/**` only, or with 116, whose `docs/audit.md` Owns are new sections only. This task touches the existing field-list paragraph and none of 116's sections.)*
**Owner decision:** D-150-A, open. See "Owner decision" below. Do not start until it is recorded.
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditedEntityTypes.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditedEntityTypesTest.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ContextRequest.java *(one new `auditedEntityType` component and its additive constructor overloads only)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java *(the `audit(...)` method's `entityType` argument only, currently `:379-380`)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/AuditedEntityTypeOrchestratorTest.java *(new)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/** *(after 148)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/UnregisteredEntityTypeAuditTest.java *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/** *(existing files: only assertions on the audited `entityType` that this change alters, and constructor calls this change breaks)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java *(the `entity-types` field of the `Audit` class (`:924`) and its validation only, after 148)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java *(the argument list of the `DataPrismMcpServer.streamableHttp(...)` call in `dataPrismHttpTransport` (`:989-997` at e7f574db) only, after 148. No new `@Bean` method.)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AuditEntityTypesConfigurationTest.java *(new)*
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java *(the `DataPrismMcpServer.stdio(...)` call at `:86-88` only)*
- data-prism-integration-tests/src/main/resources/application.yaml *(one `entity-types` entry under `dataprism.audit` only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/** *(existing files: only assertions on the audited `entityType` that this change alters. `PiiLogScanTest.java` is not touched.)*
- docker/server/application.yaml *(one `entity-types` entry under `dataprism.audit` at `:40-42` only)*
- docs/configuration.md *(the `dataprism.audit` row of the vocabulary table at `:75`, and one new paragraph with its refusal code inside the `### dataprism.audit` section before `#### Segmented files, checkpoints and retention`, only)*
- docs/audit.md *(one sentence appended to the hashed-field-list paragraph at `:81-87` only)*
- docs/tools.md *(one new paragraph after the field-dispositions paragraph that starts at `:200`, only)*

## Goal

On the tool paths, the caller's raw `entityType` argument is written verbatim
into the audit record's `entityType` field. It is caller-controlled free text,
so it can carry personal data, for example `ACC-1`, into the hash-chained
`.log`, the ndjson projection (tasks 112 and 113), the `Slf4jAuditSink` line,
and anything shipped from those to Elastic. After this task the audit field
holds the argument only when it exactly matches an operator-registered entity
type. Otherwise it holds the fixed sentinel `AuditedEntityTypes.UNREGISTERED`.
What the caller sees does not change: the same refusal codes, the same
`isError` results, and the same echoed `entityType` in the response body.

## Owner decision

**D-150-A (open; three parts, each with a recommendation).** The brief says
"registered entity type", but the repository has no entity-type registry. The
core carries no business domain. `DataSourceAdapter` has only `sourceName`,
`responseType` and `fetch`, and `@LlmExposedModel` has no entity type. A
registry has to be introduced, and that adds a configuration key, which
`docs/configuration.md:62-65` says needs "a contract update".

- **(a) Where registration comes from.** Recommended: a new optional property,
  `dataprism.audit.entity-types`, a list of names. Java callers pass an
  `AuditedEntityTypes` to new `DataPrismMcpServer` and tool overloads. Every
  entry must match `[A-Za-z][A-Za-z0-9_-]{0,63}`. Otherwise startup is refused
  with `INVALID_AUDIT_ENTITY_TYPE`. Matching is exact and case-sensitive.
  Rejected alternatives:
  - A shape check such as "upper-case word". `ACC1` and many account or case
    references pass it.
  - Inferring types from adapters. Adapters declare none.
- **(b) The sentinel value.** Recommended: `<unregistered>`.
  - The validation pattern in (a) cannot produce it, so it never collides with
    a real type.
  - It follows the existing `merged:<refused>` convention for a fixed audit
    placeholder (`DefaultContextOrchestrator.java`, the `dispositions.put`
    in the catch block).
  - It is not empty. Empty already means "not applicable" on operator records
    (`OversightOperatorController.java:281-285`).

  It is not a hash of the input. An unkeyed hash of a low-entropy value such
  as an account number is reversible by enumeration. A keyed HMAC is still
  pseudonymous personal data. Either would keep the data in the log and only
  look like it removes it.
- **(c) The default when nothing is registered.** Recommended: an empty
  registry, so every `entityType` is audited as the sentinel. This is fail
  closed (CLAUDE.md rule 2). It applies to the property's default and to every
  existing tool, server and `ContextRequest` constructor that takes no
  registry. Consequence: until operators set `entity-types`, upgraded
  deployments audit `<unregistered>` where they used to audit `CUSTOMER`. This
  is a visible audit-content change for 0.5.0 and needs a CHANGELOG line at
  the release cut. The shipped quickstart and integration-test configs
  register their own types, so their output is unchanged.

The audit record format does not change. `entityType` stays one string field
in the same position. `AuditEventHash`, `AuditRecordFormat`, the verifier and
`recordVersion` are untouched. **No record version bump is needed**, so none is
proposed.

## Context

- **The leak sites, at e7f574db.** Line numbers will move when 148 merges.
  - `GetEntityContextTool.java:206` reads the argument. It is audited raw by
    `denyUnauthenticated` (`:306-307`) and by `deny` (`:339`).
  - `CompareEntitySourcesTool.java:206` reads it the same way. It is audited
    raw at `:367` and `:400`.
  - Both tools have six DENY paths that reach those two methods:
    1. no caller (`:216`);
    2. correlation `REQUIRED` with the id absent (`EXTERNAL_CORRELATION_ID_REQUIRED`);
    3. correlation `REQUIRED` with the id rejected (`EXTERNAL_CORRELATION_ID_INVALID`), both at `:220-221`;
    4. authorisation denied (`:226`);
    5. scope resolution refused (`:233`);
    6. admission refused (`:243`).
- **A second path the reviewer did not list.** The same raw argument goes into
  `ContextRequest` (`GetEntityContextTool.java:254`,
  `CompareEntitySourcesTool.java:254`). `DefaultContextOrchestrator.audit`
  (`:379-380`) then writes `request.entityType()` into the ALLOW record and
  into every orchestrator-audited DENY: `SCOPE_READ_BUDGET` (`:178`),
  `NO_SOURCE_DATA` (`:203`), `VALIDATION_FAILED` and `REQUEST_FAILED`
  (`:238`). An adapter that ignores `entityType` serves the call, so
  `{"entityType":"ACC-1"}` can produce an ALLOW record with `entityType=ACC-1`.
  This task fixes that path too. `ContextRequest.entityType` itself must stay
  raw, because adapters (`DataRequest.of`, `:363`) and the response echo
  (`ContextResponse.of`, `:243`) use it.
- **Other tool-argument values checked; all are safe, so none is in scope.**
  - `subjectId` is audited only as `synthetics.syntheticValue(...)` and as a
    keyed fingerprint (`DefaultContextOrchestrator.java:156-158`).
    `ToolCalls.binding` HMACs it with `entityType` before admission.
  - `rejectedArguments` is the intersection of the argument *names* with the
    fixed `ReservedArguments.NAMES` and `ToolCalls.APPROVAL_ARGUMENTS` sets
    (`ReservedArguments.java:26-31`, `ToolCalls.java:55-65`). It never holds a
    caller-chosen string.
  - `approvalId` and `approverId` come from the approval store's record, never
    from arguments (`GetEntityContextTool.java:236-238`).
  - `externalCorrelationId` comes only from the validated transport header
    (`ToolCalls.inboundCorrelation`, `:49-53`).
  - Tool `LOG` lines carry no argument value (`:313`, `:347`).
- **The three outputs the test must scan.**
  - `FileAuditSink` (the native `.log`).
  - `SegmentedJsonAuditSink` (the ndjson projection). Use the ECS mapping, as
    `AuditFilePiiScanTest`'s `runThroughTee` does.
  - `Slf4jAuditSink`'s line (`Slf4jAuditSink.java:63-67`).

  `TeeAuditSink` takes two sinks, so nest them. `data-prism-mcp/pom.xml:44`
  already has `logback-classic` at test scope for a `ListAppender`.
  `AuditFilePiiScanTest.java:454-530` shows the native and ndjson scan
  pattern to follow.
- **Wiring.** `DataPrismMcpServer` has the `stdio(...)` overloads (`:88-125`)
  and the `streamableHttp(...)` overloads (`:179-219`). The auto-configuration
  builds the HTTP transport at `DataPrismAutoConfiguration.java:989-997`, and
  148 extends that same call. `AutoConfiguredBeanClassificationTest` requires
  every `@Bean` method to be classified in `PrivacyExtensionPoints`, so read
  the property inside the existing bean method rather than adding a bean. That
  keeps the guard file out of Owns. Task 113 did the same ("wire the JSON
  projection without new bean methods").
- **The existing sentinel to mirror.** `UNAUTHENTICATED_PRINCIPAL =
  "unauthenticated"` is a private constant on each tool
  (`GetEntityContextTool.java:85`). The new sentinel is one public constant,
  `AuditedEntityTypes.UNREGISTERED`, in core. Both tools and the orchestrator
  reference it. It is not duplicated.
- docs/conventions.md, the paragraph on leak tests and mutation (`:148-151`):
  every absence assertion needs a mutation proving it non-vacuous.
- docs/conventions.md, the reviewer-isolation rule (`:325-366`): the mutation
  must not run while the reviewer reads the tree.

## Acceptance

- [ ] `AuditedEntityTypes` (core, `io.github.aindriub.dataprism.audit`) has:
      - a `public static final String UNREGISTERED` whose value is the one
        D-150-A(b) records;
      - `none()` and `of(Collection<String>)`;
      - `String audited(String raw)`, which returns `raw` only when it is
        exactly a registered name and returns `UNREGISTERED` for anything
        else, including `null`, blank, a case variant, a registered name with
        surrounding whitespace, and the string `UNREGISTERED` itself.
      `AuditedEntityTypesTest` covers each of those inputs. The constant's
      javadoc states its value and that the value is stable.
- [ ] `AuditedEntityTypes.of` rejects any name that does not match the
      D-150-A(a) pattern, which includes the sentinel. A unit test feeds it
      the sentinel and asserts the rejection.
- [ ] `UnregisteredEntityTypeAuditTest` (mcp) has one test per DENY path,
      for each of the two tools: the six paths listed in Context, giving
      twelve tests (or twelve cases of one parameterised test, each named
      after its path and tool). Each test:
      - calls with arguments `{"entityType":"ACC-1","subjectId":"x"}` and
        nothing registered, through a sink that tees `FileAuditSink`,
        `SegmentedJsonAuditSink` and `Slf4jAuditSink`;
      - asserts that the substring `ACC-1` appears nowhere in the native
        `.log` file, any ndjson line, or any captured `Slf4jAuditSink` log
        event's formatted message or key-value pairs;
      - asserts that the record's `entityType` equals
        `AuditedEntityTypes.UNREGISTERED`.
- [ ] The same test class covers the orchestrator-audited path for each tool:
      - one ALLOW, where the adapter ignores `entityType`;
      - one orchestrator DENY, for example `NO_SOURCE_DATA`.
      Both use the planted `ACC-1`, nothing registered, and the same
      three-output absence assertion.
- [ ] Each of those tests asserts that the client-visible result is unchanged
      from before this task: the same `isError`, the same refusal-code text,
      and on ALLOW the same `entityType` echoed in `structuredContent`. Any
      existing assertion on a client-visible result passes without edits.
- [ ] A registered type is audited verbatim. With `CUSTOMER` registered, one
      DENY path and the ALLOW path per tool record `entityType=CUSTOMER` in
      all three outputs.
- [ ] `AuditedEntityTypeOrchestratorTest` checks the orchestrator directly:
      - a `ContextRequest` built with a legacy constructor (no audited value)
        audits `UNREGISTERED`;
      - one built with an explicit `auditedEntityType` audits that value;
      - in both cases `ContextResponse.entityType()` and the `DataRequest`
        each adapter receives still carry the raw `entityType`.
- [ ] **Mutation proof, reported in the hand-back with the observed failures.**
      1. Change `AuditedEntityTypes.audited` to `return raw;`. Every planted
         `ACC-1` test fails.
      2. Change it to `return UNREGISTERED;`. Every registered-verbatim test
         fails.
      3. Revert the tools' `deny` and `denyUnauthenticated` to pass the raw
         `entityType`. The tool-level DENY tests fail.
      4. Revert `DefaultContextOrchestrator.audit` to `request.entityType()`.
         The ALLOW and orchestrator-DENY tests fail.

      Restore the tree after each mutation. `git diff` is empty of mutations
      at hand-back.
- [ ] The audit format is unaffected. `git diff e7f574db --stat` shows no
      change to:
      - `AuditEventHash.java`, `AuditRecordFormat.java`, `AuditEvent.java`,
        `AuditEntry.java` and `AuditChainVerifier*.java`;
      - `AuditJsonRenderer.java` and `Slf4jAuditSink.java`.

      `recordVersion` is unchanged. A file written by the new tests verifies
      clean with `AuditChainVerifierCli`, and the test asserts exit code 0.
- [ ] Configuration:
      - `dataprism.audit.entity-types` binds a list and defaults to empty.
      - `AuditEntityTypesConfigurationTest` (an `ApplicationContextRunner`
        test) has:
        - a valid list reaching the HTTP transport, proved by an audited call
          or by the tools' registry;
        - one refusal case per invalid shape, each refused with
          `INVALID_AUDIT_ENTITY_TYPE`: blank, leading digit, a space, the
          sentinel, and 65 characters.
      - `DataPrismAutoConfiguration` declares no new `@Bean` method. The
        `grep -c '@Bean'` count is unchanged from 148's merge head.
- [ ] `docker/server/application.yaml` and the integration-tests
      `application.yaml` register the entity type their fixtures use.
      `ExampleApplication`'s stdio server is given the same registry. Existing
      integration tests that assert an audited `entityType` pass, edited only
      where a test builds tools directly without a registry. Each such edit is
      listed in the commit body.
- [ ] Docs:
      - `docs/configuration.md` documents `dataprism.audit.entity-types`: its
        default, the pattern, `INVALID_AUDIT_ENTITY_TYPE`, and the sentinel
        value with what it means.
      - `docs/audit.md` adds one sentence after the field list at `:81-87`
        saying `entityType` holds the sentinel for an unregistered type.
      - `docs/tools.md` adds one paragraph saying that the audit record's
        `entityType` is the registered name or the sentinel, while the
        response still echoes the request.
      - The three docs give the sentinel's literal value identically, and a
        `grep` for it finds the same string in `AuditedEntityTypes.java`.
- [ ] `mvn -q clean verify` on the full reactor passes.

## Out of scope

- The response echo of `entityType` (`docs/tools.md:117`, `:229`), and the
  `REQUEST_FAILED at <entityType>` refusal text
  (`AuditedRefusalException.java:33-36`). Both go back only to the caller who
  sent the value, and the brief forbids changing client-visible results.
- Refusing unregistered entity types. That changes DENY behaviour, which this
  task must not do.
- `OperatorAudit` and `OversightOperatorController`'s `entityType` (a tool
  name, `ALL` or empty), and `ReidentificationService`'s namespace field.
  These come from the operator HTTP surface, not from an MCP tool argument.
  If they are a concern, raise them as a separate recon.
- `PiiLogScanTest.java`, `DataPrismAssembly.java` and
  `PrivacyExtensionPoints`. They are not needed and not owned.
- `docs/audit.md`'s "Record version 3", "External correlation id" and
  "Structured JSON output" sections, `docs/log-shipping.md` and
  `mkdocs.yml`. Task 116 owns them. If 116's new text describes the
  `entityType` field, adding the sentinel there is a one-line follow-up for
  the recorder, not this task.
- `CHANGELOG.md`. The D-150-A(c) audit-content change is a release-cut line.
- `describe_entity_model` (`docs/design-review.md:177`) or any other use of
  the registry beyond audit.

## Owner decision D-150-A: decided 2026-10-07

The owner chose the configured list with a shape-check fallback when the list is unset. This replaces recommendation (c); parts (a) and (b) stand.
- (a) `dataprism.audit.entity-types` is an optional list. Every entry must match `[A-Za-z][A-Za-z0-9_-]{0,63}`, otherwise startup is refused with `INVALID_AUDIT_ENTITY_TYPE`. Matching is exact and case-sensitive.
- (b) The sentinel is `<unregistered>` (`AuditedEntityTypes.UNREGISTERED`).
- (c) When the list is unset or empty, the raw value is audited verbatim only if it matches `[A-Z][A-Z0-9_]{0,63}`; otherwise the sentinel is audited. When the list is set, only exact list members are audited verbatim. Residual risk: upper-case tokens such as MURPHY or ACC123 pass when no list is set, so the docs recommend setting the list. Startup logs an INFO line when the list is unset.

## Attempt 1 — failed

Reviewer: CHANGES.
- The `stdio(..., CorrelationRequirement, AuditedEntityTypes)` overload passed oversight=null. The `oversight == null` branch then built the tools without `entityTypes`, so the registry was silently dropped and shape mode applied.
- The audit.md and tools.md residual-risk wording was missing.
Attempt 2 (80b246fd, 0cb4ca8c) fixed both and added a stdio test with a mutation proof. Tester PASS (1350/0/0/0, the stdio test stable over 3 runs). Reviewer APPROVE.
