# 147 — Document the 0.5.0 platform: Spring Boot 4.1, Java 25 images, Java 21+ for consumers

**Repo:** `.`
**Release:** 0.5.0 (part of 139 and 140)
**Depends on:** 108, 111, 141, 143, 144
**Owns:**
- README.md *(the "Stack" paragraph at `:192` and the "Building and running" requirement at `:200` only)*
- CLAUDE.md *(the stack sentence at `:8-9` only)*
- examples/agent-config/stdio-fixture/run-fixture-server.sh *(the comment at `:20` only)*
- docs/extending.md *(the quoted pom comment at `:92-100` and the extension pom snippet with its version prose at `:462-540` only)*
- server.json *(the "verified ... against Spring Boot 3.5.16" clause in the description at `:106` only)*
- docs/architecture.md *(two new entries under "Decisions worth knowing" only)*
- CHANGELOG.md *(the `[Unreleased]` section only)*

## Goal

Tasks 141-146 change what a consumer needs and what an operator runs. The
starter and auto-configuration now need Spring Boot 4.1. The library jars
still run on Java 21 or newer. The images ship Java 25. The classpath is
deliberately Jackson 2. Every place that states the stack or a version
must say this, and it must say it only after the code does it.

## Context

- Current stack statements: `README.md:192` ("Java 21, Spring Boot 3.x"),
  `README.md:200` ("Requires Java 21 ..."), `CLAUDE.md:8-9` ("Java 21,
  Spring Boot 3"), `run-fixture-server.sh:20` ("a JDK 21").
- `docs/extending.md:471` pins `spring-boot.version` `3.5.16` in the
  extension pom snippet, and `:512` and `:537` name `3.5.16` and `spring-web`
  `6.2.19`. Task 141 moves the server to Boot 4.1.1 / Spring Framework 7.0.9.
  `docs/extending.md:92-100` quotes `data-prism-quickstart-extension/pom.xml:18-26`,
  which names `spring-boot-starter-web`. Quote whatever task 141 left in that
  pom, verbatim.
- `server.json:106` says the relaxed binding of
  `DATAPRISM_SECURITYPOLICY_ROLES_<ROLE>` was verified against Boot 3.5.16
  (task 38). The claim must be verified again against 4.1.1, not edited
  into truth.
- HISTORY, tasks 49-51: a guide's pom snippet once "verified" by an external
  build that silently added a property. Check snippets by extracting the
  fenced block programmatically, never by retyping it.
- Decisions to record, from this plan: D-139-A (Jackson 2 kept on Boot 4,
  why, and that Boot marks its Jackson 2 support deprecated for removal),
  D-139-B (Boot 3 consumers stay on 0.4.x), and Java 25 images with
  `--release 21` bytecode gated at class major 65 (task 144).
- `docs/conventions.md#documentation` — "supports", never "compliant".
- Tasks 108 and 111 each add one section or paragraph to `docs/extending.md`.
  This task depends on both and edits only the regions named in `Owns`.

## Acceptance

- [ ] `README.md` states: the library modules require Java 21 or newer and
      Spring Boot 4.1.x; the published images run Java 25; building needs
      JDK 21 or newer (CI builds on 21 and 25). "Spring Boot 3" no longer
      appears in `README.md` or `CLAUDE.md`.
- [ ] `CLAUDE.md:8-9` reads "Java 21 bytecode (Java 25 images), Spring Boot
      4", or equivalent words. No other line of `CLAUDE.md` changes
      (`git diff` shows one hunk).
- [ ] `run-fixture-server.sh:20` says "a JDK 21 or newer". The script's code
      is unchanged.
- [ ] `docs/extending.md`'s snippet pins `spring-boot.version` `4.1.1`. The
      prose names `spring-web` `7.0.9` and `spring-boot-autoconfigure`
      `4.1.1`. A programmatically extracted copy of the snippet's pom
      resolves those versions with `mvn -B -f <extracted> dependency:tree`.
      The command and output are in the commit body. `data-prism.version` in
      the snippet is left for the release cut, and the commit body says so.
- [ ] `server.json`'s clause names Spring Boot 4.1.1, and the commit body
      shows a `Binder` run against 4.1.1 binding
      `DATAPRISM_SECURITYPOLICY_ROLES_INVESTIGATOR` to
      `dataprism.security-policy.roles.investigator`. If it does not bind,
      stop and report: the listing would then be wrong.
- [ ] `docs/architecture.md` gains the two decision entries named in Context.
      The Jackson entry says the enforcer still bans `tools.jackson.core:*`.
- [ ] `CHANGELOG.md` `[Unreleased]` gains a `### Breaking` entry (Spring
      Boot 4.1 required for the starter and auto-configuration; Boot 3
      users stay on 0.4.x) and a `### Changed` entry (images on Java 25;
      library bytecode still Java 21). No other section changes.
- [ ] `mkdocs build --strict` exits 0.
- [ ] `grep -rnE 'compliant|tamper-proof' README.md CLAUDE.md docs/architecture.md`
      finds no new occurrence.

## Out of scope

- `docs/configuration.md`, `docs/tools.md`, `docs/audit.md`,
  `docs/log-shipping.md` and `mkdocs.yml`. Tasks 113, 110 and 116 own them.
- The release version cut (`0.5.0` literals across poms, `server.json`
  versions and Dockerfile `ARG VERSION`).
- `docs/pack.md` and `docs/development-plan.md`. They are historical
  specifications and are not edited.
- `.github/**` and `docker/**`.
