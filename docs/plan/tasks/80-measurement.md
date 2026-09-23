# 80 — Set up discoverability measurement: questions, run template, snapshots, baseline

**Repo:** `.`
**Wave:** 1 (spec task T5)
**Depends on:** none
**Base branch:** the LOCAL `discoverability` branch (not pushed, not `main`).
`wt-new.sh` bases new worktrees on `main`, so right after it, before any edit,
run `git -C <worktree> reset --hard discoverability`. The branch merges back
into `discoverability`. This task file is uncommitted: read it from
`/srv/dev/projects/data-prism/docs/plan/tasks/80-measurement.md`.
**Owns:**
- docs/plan/discoverability/**

## Goal

A tracker-free way to tell whether discoverability work is working:
- a fixed 15-question assistant check, re-run monthly
- a snapshot script for the public GitHub signals, which expire after 14 days
- the 2026-09-23 baseline committed next to them

All of it is internal and never published on the site.

## Context

- `docs/plan/specs/2026-09-23-discoverability.md` — binding. Read
  "Decisions → Measurement", "Baseline", "T5" and "Risks → Measurement".
- Raw baseline JSON is in `/srv/dev/scratch/data-prism-baseline-2026-09-23/`:
  `repo.json`, `repo-traffic-views.json`, `repo-traffic-clones.json`,
  `repo-traffic-popular-referrers.json` and `repo-traffic-popular-paths.json`.
- The baseline values, from the spec:

  | Signal | Value |
  |---|---|
  | Stars / forks / watchers / open issues | 1 / 0 / 0 / 7 |
  | Views, last 14 days | 318 total, 9 unique (almost all the owner) |
  | Clones, last 14 days | 1,358 total, 346 unique (mostly CI and automation, not people) |
  | Referrers | github.com only (184 views, 7 unique) |

  The window includes the v0.3.0 release activity.
- The GitHub traffic endpoints need push access. `gh` on this host is logged
  in as `AindriuB`, the owner. The script reads data only. It must never
  write to the API.
- `shellcheck` is not installed on this host. Use
  `docker run --rm -v "$PWD:/mnt" koalaman/shellcheck:stable …` or an
  equivalent.
- `docs/plan/` is excluded from the site by task 82's `exclude_docs`, so no
  front matter is needed here.
- Waiting on processes: never wait with `pgrep -f <pattern>` in a shell whose
  own command line contains that pattern. Wait on a captured PID, or use
  `timeout`.

## Acceptance

- [ ] `docs/plan/discoverability/questions.md` defines exactly 15 questions,
      each as a heading `## Qnn <question>`.
      `grep -cE '^## Q(0[1-9]|1[0-5]) ' questions.md` prints 15, and
      `grep -cE '^## Q[0-9]+' questions.md` prints 15.
- [ ] The questions are phrased the way this audience would ask them. They
      include these three, in substance:
      - "How do I stop an LLM agent seeing customer PII from our internal
        REST APIs?"
      - "Presidio alternative for Java"
      - "GDPR data minimisation MCP tools"
      No question names Data Prism, because the check measures unprompted
      citation.
- [ ] `docs/plan/discoverability/runs/TEMPLATE.md` records date, assistant,
      model, and mode (fresh session, web search on or off). It holds a
      15-row × 4-column grid (ChatGPT, Claude, Perplexity, Copilot), where
      each cell records cited Y/N, the URL cited, and the other tools named.
      It states "citation rate = cited cells / 60".
- [ ] `docs/plan/discoverability/snapshot.sh` uses only bash, `gh` and `jq`.
      It writes `snapshots/YYYY-MM-DD.json` (UTC date) holding:
      - repo counts: stargazers, forks, subscribers and open issues
      - `views` from `/traffic/views`
      - `clones` from `/traffic/clones`
      - `referrers` from `/traffic/popular/referrers`
      - `paths` from `/traffic/popular/paths`
      - a `captured_at` timestamp
      It exits non-zero if any call fails, and never writes a partial file.
- [ ] `bash -n snapshot.sh` exits 0, and shellcheck reports nothing at the
      default severity.
- [ ] A live run by the tester produces a file for which
      `jq -e '.views.count | numbers'` exits 0. The tester reports that
      file's path. It is committed only if the run is on the execution date
      and adds no fields beyond those listed.
- [ ] `docs/plan/discoverability/baseline.md` reproduces the baseline table
      and the note about the window.
- [ ] The five raw files are copied unmodified to
      `docs/plan/discoverability/snapshots/raw/2026-09-23/`: `cmp` against
      the scratch copies exits 0.
- [ ] `snapshots/2026-09-23.json` is built from the raw files with `jq`, in
      the same shape `snapshot.sh` writes, and the jq command is recorded in
      `baseline.md`.
- [ ] `docs/plan/discoverability/README.md` gives the procedure: the monthly
      assistant check, and `snapshot.sh` at least every 14 days, with the
      reason (GitHub traffic data expires). It lists the numbers that must be
      read by hand: Central Portal download stats, GHCR pull totals, and
      Search Console and Bing Webmaster Tools once they are verified.
- [ ] `git diff --name-only discoverability` lists only files under
      `docs/plan/discoverability/`.

## Out of scope

- Any analytics, tracker or beacon on the site or anywhere else.
- Running the 15-question assistant check itself. The owner does that.
- Scheduling `snapshot.sh` (cron, or a workflow using a stored token).
- Search Console and Bing verification: owner actions after go-live.
