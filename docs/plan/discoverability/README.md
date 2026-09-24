# Discoverability measurement

Internal only. Nothing in this directory is published on the site, and
none of it is an analytics tool, tracker or beacon — it is a tracker-free
way to tell, after the fact, whether the discoverability work (SEO/GEO) is
having any effect. See `docs/plan/specs/2026-09-23-discoverability.md`
("Decisions → Measurement", "Baseline", "T5") for the design this
implements.

## Procedure

- **Monthly: run the 15-question assistant check.** Ask every question in
  `questions.md` against ChatGPT, Claude, Perplexity and Copilot, one fresh
  session per question per assistant, and record the results in
  `runs/YYYY-MM-DD.md`, copied from `runs/TEMPLATE.md`. This is a manual,
  owner-run check — nothing here automates asking the questions.
- **At least every 14 days: run `snapshot.sh`.** GitHub's traffic endpoints
  (`views`, `clones`, `popular/referrers`, `popular/paths`) only ever return
  a trailing 14-day window, and older data is not retained anywhere: a
  missed snapshot loses that period's numbers permanently. Run it with `gh`
  authenticated as a user with push access to the repo (it only issues
  read-only `gh api` GETs):

  ```sh
  docs/plan/discoverability/snapshot.sh
  ```

  It writes `snapshots/YYYY-MM-DD.json` (UTC date) and exits non-zero,
  writing nothing, if any call fails.
- **`baseline.md`** holds the 2026-09-23 baseline, captured before any
  discoverability change merged, and the raw JSON it was built from.

## Numbers that must be read by hand

These are not scriptable from this host and are not captured by
`snapshot.sh`. Read and record them alongside each monthly run:

- **Central Portal download stats** for the published artifacts.
- **GHCR pull totals** for the published images.
- **Search Console and Bing Webmaster Tools** search impressions and
  clicks, once the site is verified with each (an owner action after
  go-live; see the spec's "Owner actions").
