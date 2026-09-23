# 59 — Give the demo an exit ramp and bring the reference docs up to the new path

**Repo:** .
**Base:** `main` (v0.3.0 merged at 543defb, not yet tagged or published)
**Depends on:** 53, 54, 55, 56, 60, 67, 71, 73, 75 — all merged to `main`
**Owns:**
- docs/quickstart.md
- docs/configuration.md
- README.md

## Goal
`docs/quickstart.md` ends at "stop and reset", so the minutes someone just spent lead
nowhere. End it by pointing at the walkthrough, replace its four hand-scraped curl
blocks with task 56's single demo command, and record in the reference docs what the
v0.2.x onboarding wave (53, 54, 55) and the v0.3.0 wave (60, 67, 73, 75) changed, so
that `DataPrismConfigurationFailureAnalyzer`'s pointer at `docs/configuration.md` is
never a dead end for an audit or identity refusal.

## Run order: before publish, not after
PLAN.md's release sequence said 59 runs "against the published result". The owner has
decided 59 runs **before** the `v0.3.0` tag is pushed and the images are published.
Consequences:
- Every command this task runs that would pull a published GHCR image (plain
  `docker compose up`) is instead verified with the from-source path,
  `docker compose -f compose.yaml -f compose.build.yaml up --build`. The implementer's
  report must say which path each quoted output came from.
- Doc text describing the published path must still describe it correctly for the
  moment the images exist: `compose.yaml` pulls
  `ghcr.io/aindriub/data-prism-quickstart-{server,fixtures,issuer,certs-init}` tagged
  `${QUICKSTART_IMAGE_TAG:-latest}`; `publish-image.yml` pushes both `:0.3.0` and
  `:latest` on the `v0.3.0` tag. Do not claim the pull path was run.
- Every version literal this task writes is `0.3.0`.

## Context
- docs/quickstart.md:57-167 — the token block, the `Mcp-Session-Id` scrape and the
  `python3 -c` parse that task 56's command replaces; :180-195 — the dead end.
- docs/quickstart.md:26,34 and README.md:78 — still say `docker compose up --build`
  and "all built from source", stale since task 55 removed every `build:` block
  from `compose.yaml` (see compose.yaml:13-21 and compose.build.yaml:1-13).
- examples/quickstart-demo/run.sh — task 56's one command.
- docs/configuration.md:75 — the `dataprism.audit` vocabulary row; :118-120 — the
  `writer-id: ${HOSTNAME}` example; :193-206 — the owed-work paragraph task 54
  discharges; :208-219 — the "Ownership boundary" table, whose `Sources` row names
  `IdentityResolver` as application code only (task 53 changed that for the flat case).
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/
  DataPrismProperties.java:176-198 (audit codes), DataPrismAutoConfiguration.java:92-110
  (identity-resolver codes, accepted value `pass-through` only), :223
  (`AUDIT_SINK_FILE_UNUSABLE`), DataPrismContractValidator.java:66-90
  (`AUDIT_SINK_BEAN_REQUIRED`, `MISSING_AUDIT_SINK`, `MISSING_IDENTITY_RESOLVER`).
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/
  ConfiguredJsonSources.java:273-360 (nested grammar, load-time refusals) and
  ConfiguredJsonNestedLeafShapeGuard.java:28-110 (`NESTED_LEAF_NOT_SCALAR`,
  `NESTED_FIELD_NOT_STRUCTURED` — request-time `PrivacyRefusedException`s, not startup
  refusals).
- docs/protect-your-own-api.md:17-27 and its "A nested response" section — the nested
  walkthrough to link, not duplicate. docs/audit.md — chain, `instanceId`, verifier
  and exit codes to link, not restate.
- PLAN.md, follow-up item 8 (and its task-75 extension); HISTORY.md entries for tasks
  67, 70, 73, 75.

## Acceptance

### Quickstart and README
- [ ] `docs/quickstart.md` ends with a "what next" section linking
      `docs/protect-your-own-api.md`, and no section of it still ends the reader's
      journey at `docker compose down`.
- [ ] The four curl blocks are replaced by `examples/quickstart-demo/run.sh`, quoted by
      that path, with its real output pasted from an actual run against the
      from-source stack. No `grep | tr | cut` header scrape and no `python3 -c`
      one-liner remain in the file.
- [ ] `docs/quickstart.md` and `README.md` both state `docker compose up` (pull) as the
      default and `docker compose -f compose.yaml -f compose.build.yaml up --build` as
      the from-source override, exactly as `compose.yaml`/`compose.build.yaml` define
      them; neither file still presents `docker compose up --build` alone as the
      command. The from-source command was run; the pull command was not (see "Run
      order") and the report says so.
- [ ] Where either file tells the reader how to pin a version, it uses
      `QUICKSTART_IMAGE_TAG=0.3.0`, and names images as
      `ghcr.io/aindriub/data-prism-quickstart-<name>`.
- [ ] The two stale pseudonym literals in `docs/quickstart.md` — `SUBJ-AE9Y` at :146
      and `Rowan Okafor (2TV5)` at :150 — are replaced by the eight-character forms
      task 71 produces, pasted from a real run of the from-source stack, not
      hand-edited.
- [ ] `README.md` offers the newcomer both paths — "see the demo" and "protect your own
      API" — with the second linked.
- [ ] `grep -nE '0\.[12]\.[0-9]' README.md docs/quickstart.md docs/configuration.md`
      prints nothing, and README.md's `data-prism-server-0.3.0.jar` line (task 70) is
      unchanged.

### Identity (tasks 53, 54)
- [ ] `docs/configuration.md` documents `dataprism.identity.resolver` (currently
      absent from the file): the one accepted value `pass-through`, the
      `UNSUPPORTED_IDENTITY_RESOLVER` refusal for any other non-blank value, and that
      absence with no `IdentityResolver` bean still yields `MISSING_IDENTITY_RESOLVER`.
      The Ownership-boundary `Sources` row no longer says `IdentityResolver` is
      application code only.
- [ ] `docs/configuration.md` no longer tells the reader to state a configured source's
      `base-url` twice, and the owed-work paragraph (:193-206) is gone rather than
      reworded, replaced by a statement of where a configured source's transport is
      now stated — checked against `DataPrismContractValidator` as merged, not PLAN text.

### Nested catalogues (task 60)
- [ ] `docs/configuration.md`'s `json-sources` reference states the nested grammar:
      exactly one level; a root field declares `nested: <name>`; the named catalogue
      is declared under the source's top-level `nested-catalogues:` map; a nested
      catalogue's leaves may be `nonSensitive` or classified only — `identifier:` and
      `nested:` inside one are refused at load time.
- [ ] It names `NESTED_LEAF_NOT_SCALAR` and `NESTED_FIELD_NOT_STRUCTURED` as
      request-time privacy refusals (response shape does not match the declared
      nesting), not startup refusals, and links `docs/protect-your-own-api.md` for the
      walkthrough instead of reproducing it.

### Audit (tasks 67, 73, 75)
- [ ] `docs/configuration.md` documents `dataprism.audit.sink`'s three accepted values
      (`approved-sink`, `slf4j`, `hash-chained`) and `dataprism.audit.file-path`,
      required when `sink: hash-chained`, and links `docs/audit.md` for the chain,
      `instanceId` and verifier rather than restating them.
- [ ] Each of these has an entry naming its cause: `MISSING_AUDIT_SINK`,
      `UNKNOWN_AUDIT_SINK`, `AUDIT_SINK_BEAN_REQUIRED` (`approved-sink` with no
      `AuditSink` bean), `MISSING_AUDIT_FILE_PATH`, `AUDIT_SINK_FILE_UNUSABLE` (path
      cannot be opened at startup; refuses, never degrades to no or `slf4j` auditing),
      `MISSING_AUDIT_WRITER`, `INVALID_AUDIT_WRITER`, `INVALID_AUDIT_REFERENCE`.
- [ ] The `writer-id: ${HOSTNAME}` example (:120) and its surrounding text say that a
      writer-id need not be unique per boot (each boot's `instanceId` is
      `<writer-id>/<uuid>`), that it must not contain `/`, and that a `/` refuses with
      `INVALID_AUDIT_WRITER`.
- [ ] The `dataprism.audit` vocabulary row (:75) is corrected: writer-id is required
      for every sink (`DataPrismProperties.java:188` checks it unconditionally), not
      only for a hash-chained one.

### Evaluation issuer (task 55)
- [ ] The evaluation issuer image (`data-prism-quickstart-issuer`) is documented — how
      to point a deployment's `jwk-set-uri` at it, and, in the same paragraph, that it
      is fixture-only, that `dataprism.transport.fixture-development=true` is still
      refused on the standalone server, and that nothing about it permits an
      unauthenticated call.

### Catch-all
- [ ] Every code in the output of
      `grep -rhoE '"[A-Z_]*(AUDIT|IDENTITY)[A-Z_]*"' data-prism-spring-boot-autoconfigure/src/main | sort -u`
      appears in `docs/configuration.md` (the report lists each code and its line).
      At 543defb that is the eight audit codes above plus `MISSING_IDENTITY_RESOLVER`
      and `UNSUPPORTED_IDENTITY_RESOLVER`.
- [ ] No claim in any of the three files is carried forward unverified: every command
      quoted was run (from-source where the published path is unavailable), and any
      pre-existing claim the run contradicts is corrected or reported.

## Out of scope
- `docs/extending.md`, `docs/protect-your-own-api.md`, `docs/audit.md`,
  `docs/architecture.md` — tasks 58/62 own them; link, do not edit.
- `examples/quickstart-demo/run.sh`'s header comment, which still says
  `docker compose up --build` — outside `Owns`; report it.
- Refusal codes for non-audit, non-identity properties that are also undocumented —
  report any found, do not document them here.
- `CHANGELOG.md`, `server.json`, `docs/plan/*` — release and scribe territory.
- Tagging, dispatching `publish-image.yml`, or pulling published images.
- Any code, workflow or Compose change. If a doc cannot be made true without one,
  report it rather than editing outside `Owns`.
