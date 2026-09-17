# 45 — Cut version 0.2.0

**Repo:** `.`
**Depends on:** 42, 43, 44
**Owns:**
- pom.xml
- */pom.xml
- server.json
- CHANGELOG.md
- docker/*/Dockerfile
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java
- README.md
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerPackagingIT.java
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ConfiguredJsonSourcesPackagingIT.java
- data-prism-quickstart-extension/src/test/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartSmokeIT.java

In every file except `CHANGELOG.md`, this task may change **version literals
only**. Anything else in the diff is a bug.

## Goal

Move the tree from 0.1.1 to 0.2.0 so the second MCP tool and the corrected
registry namespace can be released. Mechanical, and the same sweep task 41 did
one version earlier — the value of the task is that the sweep is exhaustive and
that the CHANGELOG says only what is true.

**Owner gate:** this task assumes the owner wants 0.2.0 cut now. `v0.1.1` is
tagged but its publish sequence (`docs/plan/PLAN.md:283-292`) may still be
outstanding, and Maven Central 0.1.0 is already immutable. Confirm before
opening the PR; do not tag, dispatch or publish anything.

## Context

- `docs/plan/HISTORY.md` — grep `Task 41` — the previous cut, its file list, and
  the false premise a reviewer caught in its CHANGELOG. Read it before writing
  this one's.
- 19 poms carry the version: `pom.xml` plus each of the 18 modules, one
  occurrence each.
- `server.json` carries it twice: `.version` and `.packages[0].version`.
  `publish-mcp.yml:57-64` fails the publish if they disagree, and `:66-78` fails
  if the tag disagrees with either.
- `data-prism-mcp/.../DataPrismMcpServer.java` — two `serverInfo("data-prism",
  "0.1.1")` literals, one per transport. Task 42 also edits this file; that is
  why this task runs after it.
- `docker/distribution/Dockerfile`, `docker/server/Dockerfile`,
  `docker/fixtures/Dockerfile`, `docker/issuer/Dockerfile`.
- `README.md`, `ServerPackagingIT`, `ConfiguredJsonSourcesPackagingIT` and
  `QuickstartSmokeIT` each carry a `0.1.1` literal.

## Acceptance

- [ ] `rg -n '0\.1\.1' --glob '!docs/**' --glob '!**/target/**'` returns nothing
      outside `CHANGELOG.md`, where it names the previous release.
- [ ] `git diff` shows no change other than a version literal in every file
      except `CHANGELOG.md`. Checkable line by line.
- [ ] `jq -r '.version, .packages[0].version' server.json` prints `0.2.0` twice.
- [ ] `mvn -B clean verify` green over the full reactor, test count stated and
      compared with what task 43 left it at.
- [ ] `CHANGELOG.md` gains a `0.2.0` entry naming: the new
      `compare_entity_sources` tool and what it returns; the registry namespace
      correction and the fact that it takes effect only on a rebuilt image; and,
      explicitly, that nothing in the privacy engine, pseudonymisation or
      security modules changed behaviour. Every claim in it must be checkable
      against the diff of tasks 42-44 — if you cannot point at the lines, do not
      write the sentence.
- [ ] The PR description states which of the release's publish steps have **not**
      been performed, rather than implying the release is out.

## Out of scope

- Tagging `v0.2.0`, dispatching `release.yml`, `publish-image.yml`,
  `publish-central.yml` or `publish-mcp.yml`. All owner actions.
- Any behaviour change, dependency bump or workflow edit.
- The README's and `docs/`' prose about how many tools exist. `scribe` updates
  those at `/record`; this task touches only the README's version literal.
