# 139 — Migrate to Spring Boot 4 (umbrella)

**Repo:** `.`
**Release:** 0.5.0
**Depends on:** 130
**Status:** planned 2026-10-07 and split. Nothing is executed under this id.
Work it as tasks 141, 142 and 147.

## Goal

Dependabot #114 proposed Spring Boot 3.5.16 → 4.1.1. Owner decision D-130-A
(2026-10-07) treats this as its own migration, not a version bump.

## Split

| Task | What |
|---|---|
| 141 | Reactor on Spring Boot 4.1.1, Framework 7.0.9, Security 7.1.1 and Tomcat 11, on a single Jackson 2 classpath; nimbus-jose-jwt managed once |
| 142 | Runtime guards for what Boot 4 moved: Jackson 2 converters, actuator JSON, the operator `/error` path |
| 147 | Documentation of the 0.5.0 platform (shared with 140) |

## Target version

4.1.1, the newest 4.1 patch on Central on 2026-10-07. 4.2 is at milestone 2
only. 4.0.x open-source support ends 2026-12-31 and 4.1.x ends 2027-07-31
(spring.io generations API). Boot 3.5.x open-source support ended
2026-06-30.

## Probe result (2026-10-07, scratchpad clone)

With 19 files and about 50 lines changed, the full reactor `mvn verify`
passed on JDK 21 and on JDK 25. The changes were: the version, Jackson 3
excluded with `spring-boot-jackson2` added, and the moved packages listed in
task 141. The MCP SDK 2.0.1 needs no change: `mcp-core` is framework-neutral
and `mcp-json-jackson2` stays.
