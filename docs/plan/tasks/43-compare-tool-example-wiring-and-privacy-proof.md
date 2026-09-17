# 43 — Prove the compare path is scrubbed, and grant it in the example

**Repo:** `.`
**Depends on:** 42
**Owns:**
- data-prism-example/**

## Goal

Task 42's tests run against stub orchestrators, so on their own they prove the
tool's control flow and nothing about privacy. This task puts
`compare_entity_sources` on the real assembly — real stub adapters, real
scrubbing engine, real validators, real audit — and proves that no raw fixture
value reaches its output, that the call is audited under its own name, and that
a caller without the capability cannot reach it. It also decides and records
what the shipped example role is allowed to do.

## Context

- `data-prism-example/src/main/java/.../StubCustomerAdapter.java:21`,
  `StubOrderAdapter.java:14`, `StubAccountAdapter.java:14` — subject `123` is
  held as `Patrick Murphy`, `P. Murphy` and `Pat Murphy` across three sources.
  This is the fixture that makes a comparison tool worth having.
- `data-prism-example/src/test/.../WorkedExampleTest.java:95-115` — what that
  fixture currently yields: an `ABBREVIATION` finding, three agreement groups of
  one, named by scope-local aliases, and an assertion that the response contains
  neither `P. Murphy` nor `123`.
- `data-prism-example/src/test/.../http/PiiLogScanTest.java:95-101` — the
  forbidden-value list, and `:345-370` — `runFullIntegrationRun()`, which builds
  `DataPrismAssembly.standard()` and drives the real `GetEntityContextTool`
  through a real `McpSyncServerExchange`. The same harness, extended, is this
  task's strongest evidence.
- `data-prism-example/src/main/java/.../ExampleApplication.java:92-99` and
  `DataPrismAssembly.java:111` and
  `data-prism-example/src/main/resources/application.yaml:33,35` — the three
  places the shipped example role's capability set is written.
- `data-prism-example/src/test/.../ShippedDefaultsTest.java:30,35-41` — asserts
  by *equality* that the shipped developer role holds `GET_ENTITY_CONTEXT` and
  nothing else. Keep it an equality assertion so it still fails on an unintended
  third capability, and keep `EXPOSE_SOURCE_NAMES` out of it.
- `data-prism-example/src/test/.../EndToEndTest.java:62,136` — the existing
  policy and the `event.tool()` assertion to mirror for the new tool.

## Acceptance

- [ ] The shipped example/fixture development role grants exactly
      `{GET_ENTITY_CONTEXT, COMPARE_ENTITY_SOURCES}` in all three places named
      above, and `ShippedDefaultsTest` asserts that set by equality. Any
      widening beyond those two, and any grant of `EXPOSE_SOURCE_NAMES`, fails a
      test.
- [ ] An end-to-end test drives `compare_entity_sources` for
      `("CUSTOMER", "123")` through `DataPrismAssembly.standard()` and asserts
      the serialised result — both the structured content and the text content —
      contains none of `Patrick Murphy`, `Pat Murphy`, `P. Murphy`,
      `patrick.murphy@example.invalid` or the raw subject id `123`.
- [ ] The same test asserts the result is **not** empty of signal: it carries at
      least one finding whose `field` names the disputed field, with more than
      one agreement group, and each group named by the same scope-local aliases
      the response's source list uses — never `customer-api`, `order-api` or
      `account-api`.
- [ ] **Non-vacuity, recorded in the PR description:** with
      `ContextResponse.subject()` in the tool's projection temporarily replaced
      by the raw `subjectId` argument, the raw-value test above fails. Paste the
      failure line. Do the same for one identity value: substituting an
      unscrubbed source value must turn the test red.
- [ ] `PiiLogScanTest`'s full integration run drives a `compare_entity_sources`
      call in addition to the existing one, with the policy granting both
      capabilities, and the log scan over the whole run still passes with no
      forbidden value in any line.
- [ ] A test asserts the audit trail for that call carries
      `tool() == "compare_entity_sources"`, and that the event's subject field
      is the pseudonym, not `123`.
- [ ] A caller whose role grants only `GET_ENTITY_CONTEXT` is denied
      `compare_entity_sources` against the real `AuthorizationService`, with the
      denial audited and no source adapter invoked.
- [ ] `mvn -B clean verify` green over the full reactor, test count stated.

## Out of scope

- Any edit outside `data-prism-example/**`. If the tool needs a change to be
  testable this way, stop and report — that is task 42's file set.
- `data-prism-server`, `data-prism-quickstart-extension` and the quickstart
  smoke IT. The distributable exposes the tool with no code change, and the
  capabilities a deployment grants are its own configuration. Integration
  coverage there is a follow-up, not this task.
- The README, `docs/agents/*.md`, `docs/quickstart.md` and
  `docs/configuration.md` statements that one tool exists and that
  `investigator` holds one capability. Prose is `scribe`'s.
- Adding fixtures. Subject `123` already disagrees across three sources; a new
  fixture would be a new thing to keep true.
