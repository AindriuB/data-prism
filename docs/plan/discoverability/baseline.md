# Baseline — captured 2026-09-23

Captured before any discoverability work merged, from
`/srv/dev/scratch/data-prism-baseline-2026-09-23/`. The window includes the
v0.3.0 release activity, so clones in particular are inflated by CI and
release automation, not organic interest. The owner still has to run the
15-question assistant check once, to give the assistant side of this
baseline (see `questions.md` and `runs/TEMPLATE.md`).

| Signal | Value |
|---|---|
| Stars / forks / watchers / open issues | 1 / 0 / 0 / 7 |
| Views, last 14 days | 318 total, 9 unique (almost all the owner) |
| Clones, last 14 days | 1,358 total, 346 unique (mostly CI and automation, not people) |
| Referrers | github.com only (184 views, 7 unique) |

## Raw data

The five raw GitHub API responses are copied unmodified into
`snapshots/raw/2026-09-23/`:

- `repo.json`
- `repo-traffic-views.json`
- `repo-traffic-clones.json`
- `repo-traffic-popular-referrers.json`
- `repo-traffic-popular-paths.json`

## Building `snapshots/2026-09-23.json`

`snapshots/2026-09-23.json` is built from those raw files with `jq`, in the
same shape `snapshot.sh` writes (`repo`, `views`, `clones`, `referrers`,
`paths`, `captured_at`). Run from `docs/plan/discoverability/snapshots/`:

```sh
RAW=raw/2026-09-23
jq -n \
  --argjson repo "$(jq '{stargazers_count,forks_count,subscribers_count,open_issues_count}' "$RAW/repo.json")" \
  --argjson views "$(cat "$RAW/repo-traffic-views.json")" \
  --argjson clones "$(cat "$RAW/repo-traffic-clones.json")" \
  --argjson referrers "$(cat "$RAW/repo-traffic-popular-referrers.json")" \
  --argjson paths "$(cat "$RAW/repo-traffic-popular-paths.json")" \
  --arg captured_at "2026-09-23T20:36:00Z" \
  '{
    repo: $repo,
    views: $views,
    clones: $clones,
    referrers: $referrers,
    paths: $paths,
    captured_at: $captured_at
  }' > 2026-09-23.json
```

`captured_at` is the raw files' own capture timestamp (their filesystem
mtime in the scratch copy), not the moment this file was assembled.
