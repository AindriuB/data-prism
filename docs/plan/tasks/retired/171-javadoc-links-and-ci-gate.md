# 171 — Fix two unresolvable Javadoc links and gate Javadoc in CI

**Repo:** .
**Base:** `release/0.6.0-jackson3`. Unplanned: found while testing 168.

## Goal
Task 157's package polish left two `{@link}`s unresolvable. The release-profile Javadoc would have failed the 0.6.0 publish. Fully qualify the links, and make every PR build Javadoc so this cannot recur unseen.

## Owns as touched
- data-prism-audit/src/main/java/io/github/aindriub/dataprism/audit/AuditRecorder.java (one link)
- data-prism-audit/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java (one link)
- .github/workflows/build.yml (one step)

## Outcome (2026-10-08, wave 6)
Merged onto `release/0.6.0-jackson3` (task branch head 6cff7975). Both links are fully qualified. `build.yml`'s build job has a step "Javadoc (release profile, unsigned)" on the JDK 21 leg: `mvn -B --no-transfer-progress -Prelease -DskipTests -Dgpg.skip package`. It needs no secret and stops before signing. Fail-before and pass-after were shown locally, and the coordinator reviewed the diff.

Finding: `release.yml` runs plain `mvn verify`, and the release profile runs only in `publish-central.yml`. That is why a Javadoc error reached the publish stage in 0.5.0 and now would have again.
