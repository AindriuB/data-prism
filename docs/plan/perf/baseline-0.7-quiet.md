# Load-test baseline, 0.7 code, re-measured on a quieter host

Internal. Re-measurement of `baseline-0.7.md` (task 259, decision D-070-11 (a)).
Not for publication before 0.9. No compliance or certification claim.

## Environment

| | |
|---|---|
| Code and harness | tag `v0.7.0` (commit `64752993858c74cd6cb3e53491f2fe242255c6a7`), working tree clean (`workingTreeDirty` false in every file) |
| Machine | `hw.model` MacBookPro17,1; Apple M1; `hw.ncpu` 8; `hw.memsize` 17179869184 (16 GiB); macOS 27.0.1 (build 26A434), aarch64, bare metal |
| JDK | OpenJDK 25.0.4 (Homebrew); driver heap 4096 MiB max; server heap at JVM default |
| Date | 2026-10-21, about 17:30 to 17:40 UTC |
| Topology | Synthetic sources, token issuer, server and driver share this host over loopback. Comparable only with runs on the same machine. |

## Host state (not "nothing else ran")

This was a laptop, not a dedicated host. The owner approved running on it with
every other agent of the session stopped and the owner away. Still open: the
Claude desktop app, an editor, a browser and an idle remote-desktop client. The desktop
UI alone holds the one-minute load average around 2 to 4 on this 8-core machine
(idle readings just before the first run: 2.4 to 3.5). Before and after every
run a process listing was checked for java, Maven/surefire, a simulator, a video
call or a media encoder other than the run's own: none was present in any run
(every `ps` check was empty). Rule used: repeat a run if its before or after
one-minute load exceeds 4 (half the cores) or a heavy process is running.

Builds were done once beforehand (`package`, never `install`), so the load
figures cover only the run itself, not Maven. The measured command is the last
line of `load-test/run.sh` (`java -jar target/data-prism-load-test.jar <scenario>
--repo-root <checkout> --results-dir <dir>`). The counted runs were made one at a time, after waiting
for the one-minute load to fall to 3.5 or lower. The discarded first pass was not gated.

## Run log and repeats

Load = one-minute load average from `uptime` immediately before and after each
run. "Used" means the file is one of the three counted for the scenario.

| Result file | Load before | Load after | Used | Note |
|---|---|---|---|---|
| sources-8-20261021T173015Z | 3.29 | 3.32 | yes | |
| sources-8-20261021T173027Z | 3.32 | 3.44 | yes | |
| sources-8-20261021T173035Z | 3.44 | 4.00 | yes | after is at, not above, 4 |
| sources-32-20261021T173043Z | 4.00 | 4.39 | no | discarded: after > 4; back-to-back runs let the average climb |
| sources-32-20261021T173055Z | 4.39 | 5.61 | no | discarded: before and after > 4 |
| sources-32-20261021T173105Z | 5.61 | 6.56 | no | discarded: before and after > 4 |
| sources-100-20261021T173115Z | 6.56 | 6.87 | no | discarded: before and after > 4 |
| sources-100-20261021T173127Z | 6.87 | 8.07 | no | discarded: before and after > 4 |
| sources-100-20261021T173139Z | 8.07 | 8.08 | no | discarded: before and after > 4 |
| sources-32-20261021T173304Z | 2.88 | 2.73 | yes | repeat set, with a wait for load <= 3.5 before each run |
| sources-32-20261021T173314Z | 2.73 | 3.10 | yes | |
| sources-32-20261021T173324Z | 3.10 | 2.91 | yes | |
| sources-100-20261021T173334Z | 2.91 | 2.93 | yes | |
| sources-100-20261021T173347Z | 2.93 | 4.64 | yes, exceeds | after > 4 |
| sources-100-20261021T173429Z | 2.93 | 2.78 | yes | |
| sources-100-20261021T173916Z | 3.02 | 4.68 | extra | repeat of the 4.64 run; after > 4 again |
| sources-300-20261021T173441Z | 2.78 | 8.91 | yes, exceeds | after > 4 |
| sources-300-20261021T173623Z | 2.84 | 4.85 | yes, exceeds | after > 4 |
| sources-300-20261021T173706Z | 3.34 | 6.73 | yes, exceeds | after > 4 |
| sources-300-20261021T173959Z | 3.13 | 7.77 | extra | repeat; after > 4 again |

The six discarded first-pass files exist and are committed unedited under
`load-test/baselines/0.7-quiet/discarded/` for the record; they are not in any
table below. Every run in that pass, bar the 8-source ones, started or ended
above 4 because the runs followed each other with no gap.

The before-load of `sources-100-20261021T173429Z` (2.93) equals that of
`sources-100-20261021T173347Z` (2.93). I checked it against the recorded
`uptime` output of the run script: it is what was read, not a copy slip (the
preceding run ended at 4.64 and the gate then waited for the load to fall).

Some "after" figures exceed 4. Observed: the before-loads of all counted and
extra runs were 3.44 or lower for the gated set, no heavy process was present in
any check, and the after-load was above 4 on all four 300-source runs and on two
100-source runs (4.64 and 4.68, the second being a repeat of the first). A repeat
of each did the same, so I stopped repeating. The cause is not established. The
run's own load (four driver workers, the server and the source listener on 8
cores, runs of about 5 seconds each) is one plausible contributor; background
desktop load is another. Treat those rows as measured with an end-of-run load
above the threshold. The "extra" files are committed unedited and are not in the
tables below.

## Results

Median of the three counted runs, with min to max in brackets. Latency in ms over
completed calls; throughput in completed calls per second.

| Scenario | p50 | p95 | p99 | max | Throughput /s |
|---|---|---|---|---|---|
| 8 | 30.4 (30.2-30.4) | 38.0 (37.6-39.2) | 43.8 (41.4-45.6) | 48.5 (47.9-49.9) | 128.7 (126.9-129.2) |
| 32 | 51.6 (51.2-52.0) | 67.5 (66.8-68.3) | 78.3 (71.8-78.3) | 90.5 (81.0-90.6) | 74.8 (74.8-75.4) |
| 100 | 105.0 (104.8-106.0) | 127.1 (125.8-137.6) | 146.6 (133.5-177.3) | 150.9 (142.6-183.5) | 36.9 (35.9-36.9) |
| 300 | 184.8 (178.0-199.4) | 272.7 (268.1-293.3) | 324.5 (323.7-339.2) | 347.7 (337.3-363.5) | 19.9 (19.2-20.1) |

In every counted run (and both extras): `transportFailures` 0, `notDispatched` 0,
`clamped` 0, `refusalCodes` empty, and every source read `ANSWERED` (8: 2400,
32: 9600, 100: 20000, 300: 30000); no other status occurred, so the single
`FAILED` read in the 300-source run 2 of `baseline-0.7.md` did not recur. With
three runs the spread is only the min to max range, not a confidence interval.

## Comparison with `baseline-0.7.md` (contended host)

| Scenario | p50 then / now | p95 then / now | p99 then / now | Throughput then / now |
|---|---|---|---|---|
| 8 | 30.1 / 30.4 | 39.7 / 38.0 | 45.8 / 43.8 | 126.6 / 128.7 |
| 32 | 50.5 / 51.6 | 63.1 / 67.5 | 67.5 / 78.3 | 77.1 / 74.8 |
| 100 | 103.2 / 105.0 | 125.1 / 127.1 | 136.5 / 146.6 | 37.5 / 36.9 |
| 300 | 190.3 / 184.8 | 287.7 / 272.7 | 341.8 / 324.5 | 19.7 / 19.9 |

The 32-source tail latency is higher than the original baseline and this is
unexplained: p95 was 62.8 to 63.2 then and is 66.8 to 68.3 now, and p99 was 66.7 to
69.2 then and is 71.8 to 78.3 now; the ranges do not overlap. The medians (p50) and throughput agree within about 3 percent in all four
scenarios, in either direction. So the earlier host load did not visibly move the
median or throughput. The 300-source tail (p95, p99) is 5 percent lower now and
its p50 spread is narrower (178 to 199 against 168 to 208 ms), consistent with
less contention but not proof of it, given three runs each. The 100-source p99 is higher now (146.6 against
136.5), which is within the spread of the two sets. This
run also did not profile anything, so it adds no evidence on where the time goes.

## What 0.8 compares against

0.8 compares against this file for the four scenarios, p50, p95, p99 and
throughput, on this same machine and JDK. Quote the median and the range, and
note that the 300-source rows and the 100-source run `173347Z` (after-load 4.64) were taken with an end-of-run load above 4.
`baseline-0.7.md` stays as the earlier, contended record; where the two differ
(the 32-source p95 and p99), use this file.

## Not measured

Failure paths, source counts above 300, soak, cluster, paced load, CPU, heap or
GC profile, and any other machine.
