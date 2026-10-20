# 200 — Design: one register of every tool, code and key planned up to the 0.9 freeze

**Repo:** .
**Branch:** from `plan/0.7.0` after every task in **Depends on** has merged there; use `main` once the integration branch has merged.
**Depends on:** 189, 190, 191, 192, 193, 197, 198, 202, 216, 232
**Kind:** design, docs only. No code.
**Owns:**
- docs/plan/design/contract-register.md (new)
- docs/plan/tasks/200-design-contract-register.md (Outcome section only)

## Goal
The surface freezes at the 0.9.0 release: public API, configuration properties,
refusal codes, status codes, MCP tool schemas and the audit format. The 0.7
design set proposes names across four documents and the 0.7 code adds more.
This task gathers every one into a single register, reconciles collisions and
naming inconsistencies, and marks which release introduces each, so the freeze
checklist exists before 0.8 starts building against it.

## Context
- Closing tables of `docs/plan/design/sealed-links.md`, `key-resolvers.md`
  (191), `cluster-security.md` (192), `catalog.md` (197),
  `batch-and-bulk-follow.md` (198).
- Shipped surface: `docs/tools.md` (tools, refusal and admission codes,
  status codes from 190, size codes from 193), `docs/audit.md` (decision forms,
  fields), `docs/configuration.md` (keys including `dataprism.limits.*` from
  189), `frozen-property-names.txt`, `RefusalCodes` format.
- D-RM-13: `compare_entity_sources` is kept and reviewed before the freeze.
- D-070-3 (a): the status contract is seven codes (today's six plus `REFUSED`),
  clients treat an unknown code as "not answered", and future codes are
  registered here.
- D-070-12 (a): this register is internal (`docs/plan/design/`).

## Required content
1. One table per kind (tools and their arguments, status codes, refusal codes,
   audit fields and decision forms, configuration keys, SPI types): name,
   state (shipped / proposed), introducing release, source document, owner decision ref.
2. Collisions and near-collisions found, and the proposed resolution of each
   (as an owner decision where it is a product choice).
3. Naming rules the register applies (code format, key casing, tool naming),
   and every entry that breaks one.
4. The freeze checklist for 0.9.0: what must exist, be documented and be pinned
   by a test (for example a frozen list) for each kind.

## Acceptance
- [ ] Include tasks 203, 206–208, 210–214 and the cluster design as proposed pre-freeze contracts, with release and decision status from `docs/plan/reconciliation-2026-10-19.md`. Proposals are not shipped APIs. Include task 209's merged bean behaviour when available; the register does not wait for 0.8 implementations.
- [ ] Register the refusal code that task 216 introduces for Hazelcast lock, index or entry-processor failures during approval creation (`CLUSTER_OPERATION_REFUSED`, or the name recorded in 216's Outcome), with its meaning and where it surfaces.
- [ ] Every name in the five design documents' closing tables, and every
      shipped tool, status code, refusal code and `dataprism.*` key, appears in
      the register; the Outcome shows the extraction commands and counts.
- [ ] No two entries share a name with different meanings, or each such pair
      is listed under item 2 with a resolution.
- [ ] **Review gate:** `reviewer` returns APPROVE after re-running the
      extraction independently and checking the register against D-RM-1 to
      D-RM-15 and `docs/architecture.md` boundaries. Verdict in the Outcome.

## Out of scope
- Editing the design documents or shipped docs; inconsistencies become owner
  decisions or successor tasks named in the Outcome.

## Outcome

### Result (2026-10-20)

Built `docs/plan/design/contract-register.md` (about 1,440 lines) from the code on `plan/0.7.0` at `840c291b`, the five design documents, the open and retired task briefs and the shipped documents. Nothing else is changed: no design document, no shipped page, no code. Every entry carries a `file:line` or a decision id.

The register has 16 sections: tools, capabilities and arguments (1); status codes (2); codes by family (3); audit format (4); metrics (5); configuration (6); SPI types and changing constructors (7); cluster artefacts and the link wire format (8); task contracts (9); withdrawn names (10); 21 collisions (11); 13 document disagreements (12); naming rules and breaches (13); 14 open owner decisions (14); the freeze checklist (15); six successor tasks (16).

**Review gate: NOT YET RUN.** A `reviewer` verdict (APPROVE, after re-running the extraction independently and checking the register against D-RM-1 to D-RM-15 and `docs/architecture.md`) has not been obtained; this task cannot spawn one. Verdict: pending. The commands below are written so a reviewer can re-run them unchanged. A first pass against D-RM-1 to D-RM-15 is recorded after them so the reviewer can check it, not accept it.

### Acceptance

- [x] Tasks 203, 206 to 208, 210 to 214 and the cluster design are registered as proposals with release and decision status from the reconciliation (section 9, with 7.2 and 7.3); task 209\'s merged bean behaviour is included.
- [x] `CLUSTER_OPERATION_REFUSED` (task 216) is registered with its meaning and where it surfaces (3.2): today it surfaces nowhere as a code (OD-200-2). `dataprism.approval.release.skipped` (task 232) is in section 5.
- [x] Every name in the five closing tables (250 names, 170 rows) and every shipped tool, status code, refusal code and `dataprism.*` key appears; commands and counts below.
- [x] Repeated and near-repeated names are listed with resolutions (section 11), or become owner decisions.
- [ ] Review gate (pending, see above).

### Extraction commands and counts

Run from the repository root with `bash`. `E1`, `E2`, `E3`, `E4`, `E5`, `E6`, `E7`, `E10` list what the code and pinned files hold; `E8`, `E8b` and `E9` read the closing tables; each list is then checked against the register with `grep -F`.

```sh
R=docs/plan/design/contract-register.md
# E1 string literals shaped like codes (222 distinct; 0 missing from the register)
grep -rhoE --include='*.java' '"[A-Z][A-Z0-9]*(_[A-Z0-9]+)+"' */src/main | tr -d '"' | sort -u > /tmp/e1.txt
while read c; do grep -qF "\`$c\`" $R || echo MISSING $c; done < /tmp/e1.txt
# E2 constants of enum Code types that declare codes (15; 0 missing; 5 of them are not string literals)
for f in $(find . -name PrivacyClusterRefusal.java -o -name ApprovalRefusedException.java | grep -v target); do
  awk '/enum Code/{p=1} p{print} /;|^    }/{if(p&&NR>1)exit}' $f; done | grep -oE '\b[A-Z][A-Z0-9_]{3,}\b' | grep -v '^Code$' | sort -u
# E3 tools (2) and capabilities (4)
grep -rhoE --include='*.java' 'NAME = "[a-z_]+"' data-prism-mcp/src/main
grep -hoE 'String [A-Z_]+ = "[A-Z_]+"' data-prism-core/src/main/java/io/github/aindriub/dataprism/core/model/Capability.java
# E4 status codes (7)
cat data-prism-orchestration/src/test/resources/source-status-codes.txt
# E5 metrics (17)
grep -oE '\("dataprism[^"]+"\)' data-prism-core/src/main/java/io/github/aindriub/dataprism/core/metrics/Metric.java
# E6 public interfaces (24) and E7 public annotations (6)
grep -rhE --include='*.java' '^public (sealed )?interface ' */src/main
grep -rhE --include='*.java' '^public @interface ' */src/main
# E10 configuration: pinned leaf keys (93), bean groups (28), and the 101 keys docs/configuration.md names
F=data-prism-spring-boot-autoconfigure/src/test/resources/io/github/aindriub/dataprism/spring/boot/frozen-property-names.txt
grep -v '| bean' $F | cut -d'|' -f1 | sed 's/ *$//' | sort -u
grep -oE '`dataprism\.[a-z0-9.*<>-]+`' docs/configuration.md | tr -d '`' | sort -u
```

E8 (closing-table names; 250 distinct, 0 missing), E8b (closing-table rows; 170 rows, 0 uncited) and E9 (names repeated across rows; 16):

```sh
# E8: every backticked name in column 1 of the four closing tables and column 2 of cluster-security 4.9
R=docs/plan/design/contract-register.md
D=docs/plan/design
{
for f in sealed-links key-resolvers catalog batch-and-bulk-follow; do
  awk '/^## [0-9]+\. Proposed names/{p=1;next} p&&/^## /{p=0} p&&/^\|/&&!/^\|---/&&!/^\| Name/' $D/$f.md | cut -d'|' -f2 \
   | awk -F'`' '{pre=""; for(i=2;i<=NF;i+=2){t=$i; if(t ~ /^dataprism\./ && t ~ /\..*\./){pre=t; sub(/\.[^.]*$/,"",pre)} else if(t ~ /^\./ && pre!=""){t=pre t} print t}}'
done
awk '/^### 4.9 Closing table/{p=1;next} p&&/^---/{p=0} p&&/^\| (key|code|metric|map|audit event|health contributor) \|/' $D/cluster-security.md | cut -d'|' -f3 | grep -o '`[^`]*`' | tr -d '`'
} | sort -u > /tmp/e8.txt
wc -l < /tmp/e8.txt
miss=0; while IFS= read -r n; do grep -qF -- "\`$n\`" $R || { echo "MISSING: $n"; miss=$((miss+1)); }; done < /tmp/e8.txt
echo "missing closing-table names: $miss"

```

```sh
# E8b: every closing-table row (by line number) is cited by the register as design/<doc>:<line>
R=docs/plan/design/contract-register.md
D=docs/plan/design
total=0; miss=0
for f in sealed-links key-resolvers catalog batch-and-bulk-follow; do
  for n in $(awk '/^## [0-9]+\. Proposed names/{p=1;next} p&&/^## /{p=0} p&&/^\|/&&!/^\|---/&&!/^\| Name/{print NR}' $D/$f.md); do
    total=$((total+1)); grep -qE "design/$f\.md:$n([^0-9]|$)" $R || { echo "UNCITED $f:$n"; miss=$((miss+1)); }
  done
done
for n in $(awk '/^### 4.9 Closing table/{p=1;next} p&&/^---/{p=0} p&&/^\| (key|code|metric|map|audit event|health contributor) \|/{print NR}' $D/cluster-security.md); do
  total=$((total+1)); grep -qE "design/cluster-security\.md:$n([^0-9]|$)" $R || { echo "UNCITED cluster-security:$n"; miss=$((miss+1)); }
done
echo "closing-table rows: $total, uncited: $miss"

```

```sh
D=docs/plan/design
{
for f in sealed-links key-resolvers catalog batch-and-bulk-follow; do
  awk '/^## [0-9]+\. Proposed names/{p=1;next} p&&/^## /{p=0} p&&/^\|/&&!/^\|---/&&!/^\| Name/' $D/$f.md | cut -d'|' -f2 \
   | awk -F'`' '{pre=""; for(i=2;i<=NF;i+=2){t=$i; if(t ~ /^dataprism\./ && t ~ /\..*\./){pre=t; sub(/\.[^.]*$/,"",pre)} else if(t ~ /^\./ && pre!=""){t=pre t} print t}}'
done
awk '/^### 4.9 Closing table/{p=1;next} p&&/^---/{p=0} p&&/^\| (key|code|metric|map|audit event|health contributor) \|/' $D/cluster-security.md | cut -d'|' -f3 | grep -o '`[^`]*`' | tr -d '`'
} | sort | uniq -d

```

Counts: E1 222 literals = 34 call + 10 admission + 12 re-identification + 8 operator + 128 startup + 3 violation + 11 audit-runtime codes + 4 status literals (the load harness\'s mirror of the enum) + 12 literals that are not codes (listed in 3.9). E2 adds `INVALID_CLUSTER_PORT` and `UNSAFE_HAZELCAST_DISCOVERY` to the startup codes (130) and three internal approval names. Operator HTTP adds `UNAUTHENTICATED` and `FORBIDDEN`, single words that E1 cannot see. 68 of the 206 call, admission, re-identification, operator, startup, violation and audit-runtime codes are not named in any shipped page (`docs/**/*.md` outside `docs/plan/`).

### First pass against D-RM-1 to D-RM-15 (for the reviewer to check)

D-RM-1 generic tools, per-source results never merged, real names opt-in: tools 1.1; `sources.<key>.{status, entity}` 1.4; `labels`, `names`, `dataprism.catalog.real-names-scopes` 1.4, 4.3, 6.4. D-RM-2 search after 1.0: `search_entity_data` reserved, 1.1. D-RM-3 folded into D-RM-14. D-RM-4 links only for `chainable: true` fields: `chainable` descriptor key and `@Chainable`, 6.4 and 7.3. D-RM-5 exact namespace match, translation only by a declared resolver: `KeyResolver`, relation bytes `0x00` and `0x01`, 7.3 and 8.2. D-RM-6 link lifetime, default 30 minutes: `dataprism.links.lifetime` (`PT30M`, design/sealed-links.md:394), 6.4. D-RM-7 batch size configurable per scope: `dataprism.batch.default-size`, `.ceiling`, `.scope-sizes`, 6.4. D-RM-8 alias description checked at startup: `alias-description`, `INVALID_SOURCE_DESCRIPTION`, 6.4 and 3.5. D-RM-9 refuse when no source is chosen and the estate is large: `SOURCE_SELECTION_REQUIRED`, 3.1. D-RM-10 real names only when scope setting and permission allow, audit records both: `names`, `labels`, `EXPOSE_SOURCE_NAMES`, 4.3 and 1.2. **D-RM-11 per-source role authorisation: no name exists; flagged in 6.5 and S6.** D-RM-12 per-source truncation with markers and next-page links: `_truncated`, `RECORD_CAP`, `SIZE_CAP`, relation bytes `0x03`, 1.4 and 8.2. D-RM-13 `compare_entity_sources` kept and reviewed: 1.1 and 6.5. D-RM-14 sealed one-step links built from declarations: sections 1.4, 3.8, 8.2. D-RM-15 paging plus status-only bulk follow: `list_sources` paging keys, `follow_bulk`, 1.1 and 6.4.

`docs/architecture.md` boundaries: nothing in the register adds a path from a source adapter to the MCP layer. The proposals keep to the rules the boundaries state (no `data-prism-reidentification` dependency from the link module, sealed state only in the hazelcast module, privacy-critical beans guarded); OD-200-2 moves an exception class between modules and needs the dependency direction checked (core to hazelcast, never the reverse) when it is built.

### Owner decisions (none taken here)

Fourteen, each with options and a recommendation in section 14 of the register. Those that change what 0.8 builds: OD-200-1 (unknown-status wording: the record says "not answered", `PLAN.md:130` says "an error"; recommend "not answered"), OD-200-2 (surface `CLUSTER_OPERATION_REFUSED` and move its class; recommend yes), OD-200-4 (one set of label and identifier length caps; recommend the link layout\'s), OD-200-5 (a `FOLLOW` capability; recommend yes), OD-200-8 (drop the option-(b)-only names; recommend yes). The rest: 3 (`KEY_UNRESOLVED` spelling; recommend `RESOLVER_FAILED`), 6 (`@Chainable` attribute name), 7 (metric separators), 9 (the one-value `security.mode` key), 10 (key-failure code family), 11 (`-izer` spelling), 12 (`names` or `labels`), 13 (near-synonym codes), 14 (the approval sweep interval property).

### Successor tasks named (not allocated)

S1 correct the design documents\' stale rows; S2 surface or log `CLUSTER_OPERATION_REFUSED`, move its class, replace the sweep property; S3 document the shipped codes the pages lack; S4 add the freeze tests of section 15 that need no decision; S5 correct the D-070-3 line in `PLAN.md`; S6 design the 0.9 contract with no name yet (per-source authorisation, audit-entry size handling, the `compare_entity_sources` review).

### Noted, not fixed

- `PLAN.md` says task 201 follows 200 and must take its lines from 216, 232 and 209, but `201-*.md` lists no dependency on 200 and does not mention `CLUSTER_OPERATION_REFUSED`; if OD-200-2 is chosen, 201\'s `docs/tools.md` text changes.
- `dataprism.approval.pending.sweep-seconds` is documented in `docs/multiple-instances.md` only, not in `docs/configuration.md`, and `PropertyNamesFrozenTest` cannot see it.
- Nine pinned keys are named in `docs/configuration.md` only by their last segment (6.1 "name only").
- `docs/tools.md:3` says both tools accept "exactly two arguments"; catalog adds `targetSources` to both in 0.8, so that sentence changes then.
- The cluster design (4.2) lists `UNSAFE_HAZELCAST_DISCOVERY` as "unchanged"; it is an enum constant, not a literal (D10), so a literal-only audit of codes misses it.
