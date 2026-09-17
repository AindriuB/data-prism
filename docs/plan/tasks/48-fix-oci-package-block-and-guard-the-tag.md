# 48 — Correct server.json's OCI package block and guard the identifier tag

**Repo:** `.` (data-prism)
**Depends on:** none
**Owns:**
- server.json
- .github/workflows/publish-mcp.yml
- CHANGELOG.md — the existing `[0.2.0]` section only, no new version section

## Goal

`mcp-publisher publish` against the v0.2.0 tag was rejected by the registry with
400 Bad Request: OCI packages must not carry `registryBaseUrl`, and `identifier`
must be a canonical reference. Correct the package block to the shape the
registry currently accepts, and extend `publish-mcp.yml`'s version-agreement
guard so the release version cannot drift out of the tag that the corrected
`identifier` now embeds.

**No version bump.** The v0.2.0 tag is pushed; the GitHub Release, the multi-arch
GHCR image and the Maven Central bundle are all already published for 0.2.0.
Nothing has ever reached the MCP registry (`curl` against it confirms count 0),
so there is no published listing to supersede. This fix stays at 0.2.0
everywhere — `.version`, the package version if it survives, the identifier tag,
the poms, the README and the CHANGELOG heading.

**Local validation is not evidence.** `mcp-publisher validate server.json`
passed before *both* failed publishes. It checks the declared JSON schema; the
403 that task 44 fixed and this 400 are server-side rules the schema does not
express. Treat a green `validate` as necessary and not sufficient, and do not
report it as proof that the next publish will be accepted.

## Context

- `server.json:12-18` — the rejected block: `registryType: "oci"`,
  `registryBaseUrl: "https://ghcr.io"`,
  `identifier: "ghcr.io/aindriub/data-prism-server"`, `version: "0.2.0"`.
- The exact rejection, already observed — do not re-derive it by publishing:
  `registry validation failed for package 0
  (ghcr.io/aindriub/data-prism-server): OCI packages must not have
  'registryBaseUrl' field - use canonical reference in 'identifier' instead
  (e.g., 'docker.io/owner/image:1.0.0')`.
- `.github/workflows/publish-mcp.yml:74-81` — the guard task 45 added, which
  asserts `.version` equals `.packages[0].version`. It covers two of the places
  the version will now live and not the third.
- `.github/workflows/publish-mcp.yml:105-111` — the "image is already published"
  step, which today rebuilds the coordinate by hand from a literal path plus
  `.packages[0].version`.
- `.github/workflows/publish-mcp.yml:86-95` — the tag guard, and the house style
  for reaching a `${{ }}` value from a script: bind it once under `env:`.
- `CHANGELOG.md:24-34` — the 0.2.0 `### Changed` entry describing task 44's
  namespace correction, i.e. the precedent for how a registry-metadata
  correction is worded here.

## What to establish before editing

The error message states two things (drop `registryBaseUrl`, put a canonical
reference in `identifier`). It does not state the whole contract. Read the
registry's current OCI package documentation, or the `2025-12-11` server schema
this file declares, and settle at minimum:

- whether `version` is still required on an OCI package once `identifier`
  carries the tag, or is now redundant/forbidden;
- whether `registryType: "oci"` is still the right value and `runtimeHint:
  "docker"` still belongs;
- whether the canonical reference is expected to be `ghcr.io/...:0.2.0` or a
  digest form.

A third rejected publish is the cost of guessing here, and each round trip needs
the owner to dispatch a workflow by hand. Cite what you read in the PR body.

## The trap this creates

Once `identifier` embeds the tag, the release version appears in **three**
places in one file: `.version`, `.packages[0].version` (if it survives), and the
tag inside `.packages[0].identifier`. Task 45's version sweep knew about the
first two. A future bump that misses the identifier publishes a registry listing
that resolves to the *previous* image — it validates clean, it looks right, and
it is wrong. The guard extension is the point of this task; the `server.json`
edit alone is a one-line change and would not have been worth a task file.

## Acceptance

- [ ] `server.json`'s package 0 has no `registryBaseUrl` key
      (`jq -e '.packages[0] | has("registryBaseUrl") | not' server.json`).
- [ ] `.packages[0].identifier` is a canonical reference whose tag is `0.2.0`
      and whose path is exactly `ghcr.io/aindriub/data-prism-server`.
- [ ] `.packages[0].version` is either present and equal to `0.2.0`, or absent
      with the PR body citing the registry documentation that says it is
      redundant or forbidden alongside a tagged identifier. Not left at a value
      that disagrees with the tag either way.
- [ ] `publish-mcp.yml` fails the run when the tag inside
      `.packages[0].identifier` disagrees with `.version`, with an
      `::error::` line naming both values. If `.packages[0].version` survives,
      the existing two-way assertion stays and becomes three-way; if it is
      removed, the step asserts identifier-tag against `.version` and its
      comment says why the third field is gone.
- [ ] **The guard is proven non-vacuous.** Edit the identifier tag to a value
      that differs from `.version`, run the guard step's script body, observe a
      non-zero exit and the error line, then revert. Paste the failing output
      (two or three lines) into the PR body. The publish job only runs on
      `workflow_dispatch` against a `v*` tag, so this proof is local — do not
      claim it from reading the script. A guard nobody has watched fail is not
      proven here.
- [ ] The "Verify the referenced image is already published" step derives the
      coordinate it inspects from `.packages[0].identifier` rather than
      reassembling it from a literal registry path, so it checks the exact
      reference the registry will list.
- [ ] No `${{ }}` expression appears inside any `run:` block in
      `publish-mcp.yml` — `${{ }}` only under `env:`, `if:` or `with:`.
      Verifiable by inspection of the diff; this cost an earlier task three
      review rounds on a sibling workflow.
- [ ] All three casing rules are unchanged and still correct: MCP registry name
      `io.github.AindriuB/data-prism` in `server.json`, `README.md`'s
      `mcp-name` marker and the Dockerfile `LABEL`; Maven Central group
      `io.github.aindriub`; GHCR path `ghcr.io/aindriub/data-prism-server`
      lowercase because Docker requires it. These are three different
      identifiers in three different systems, each correct as written.
- [ ] `./mcp-publisher validate server.json` exits 0 — recorded as a sanity
      check, and explicitly not as evidence the publish will succeed.
- [ ] `jq -e . server.json` exits 0 and the diff to `server.json` touches the
      package block only: no `runtimeArguments`, `environmentVariables`,
      `description`, `repository` or `name` changes.
- [ ] The PR body states the handoff: after merge, the owner must move the
      `v0.2.0` tag to the merge commit before dispatching `publish-mcp.yml`,
      because the workflow checks out the tag ref and the tag currently points
      at the commit carrying the rejected package block.

## CHANGELOG judgement

Amend the existing `[0.2.0]` section; do not open a new one. The amendment is
warranted because that section already tells readers the MCP registry namespace
correction "takes effect for MCP clients once the server image is rebuilt and
republished", which implies a registry listing that does not exist and has never
existed. One or two sentences under the existing `### Changed` bullet, recording
that the OCI package reference is now canonical and carries the tag, is enough.
Nothing about the already-published 0.1.1 or 0.2.0 artifacts changes, so the
released content the section describes stays accurate. Do not add an
`[Unreleased]` section and do not restate the failure here — the CHANGELOG
describes what shipped, not the two rejected attempts.

## Out of scope

- **Publishing.** This task ends at a merged, green `main`. The owner dispatches
  `publish-mcp.yml` afterwards. Do not dispatch it, do not move the tag, do not
  cut a release.
- Any version bump, in `server.json`, the poms, `README.md` or the CHANGELOG
  heading. 0.2.0 is already released from three other pipelines.
- Relocating the version-agreement guards out of the gated `publish` job into
  `validate`. Their placement was a deliberate choice with a comment explaining
  it at `publish-mcp.yml:66-73`; extend the assertion where it lives.
- `publish-image.yml` and `publish-central.yml`, and the name-agreement guard at
  `publish-mcp.yml:33-41` — task 44's fix worked, this run cleared the
  permission check entirely.
- "Fixing" the casing difference between the registry name, the Maven group and
  the GHCR path. Doing so breaks two already-published artifacts.
- Any Java source, test or `docs/` file. This is a packaging-metadata task.
