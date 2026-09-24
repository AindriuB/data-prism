---
title: Developer guide
description: An overview of Data Prism's extension points and where to start writing an adapter or identity resolver.
---

# Developer guide

Data Prism is a privacy layer, not a data source: everything it protects
comes from code an integrator writes and a reviewer signs off, plugged in
through a small set of Java SPIs. This guide is that code's own
documentation — what each extension point is for, and the order to learn
them in. [`docs/extending.md`](../extending.md) stays the full reference for
every detail below; this page is the map, not the manual.

If your source is a flat or shallow JSON REST API, you may not need to write
any of this at all — see
[`docs/protect-your-own-api.md`](../protect-your-own-api.md) for the
YAML-only path first.

## The extension points

- **`DataSourceAdapter<T>`** — one external system. It names itself
  (`sourceName()`), states the model it returns (`responseType()`), and
  fetches one record (`fetch(DataRequest request)`), never seeing anything
  a caller supplied beyond an entity type and a subject id
  ([`data-prism-core/src/main/java/io/github/aindriub/dataprism/core/DataSourceAdapter.java`](https://github.com/AindriuB/data-prism/blob/main/data-prism-core/src/main/java/io/github/aindriub/dataprism/core/DataSourceAdapter.java)).
  Tutorial 1, below, writes one from nothing. Full reference:
  [Implement `DataSourceAdapter`](../extending.md#implement-datasourceadapter).
- **`IdentityResolver`** — how a subject's per-source keys relate to one
  canonical identity. Most integrations use the shipped
  `PassThroughIdentityResolver` as-is; write your own only when your sources
  disagree about identity
  ([`data-prism-core/src/main/java/io/github/aindriub/dataprism/core/IdentityResolver.java`](https://github.com/AindriuB/data-prism/blob/main/data-prism-core/src/main/java/io/github/aindriub/dataprism/core/IdentityResolver.java)).
  Tutorial 2 covers writing one. Full reference:
  [Implement `IdentityResolver`](../extending.md#implement-identityresolver).
- **`AuditSink`** and the **classification annotations**
  (`@LlmExposedModel`, `@SensitiveData`, `@NonSensitive`, `@InternalIdentifier`,
  `@SubjectIdentifier`) are also extension points a reviewed integration
  touches — the first is where audit events go
  ([`data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditSink.java`](https://github.com/AindriuB/data-prism/blob/main/data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditSink.java)),
  the second is what makes a field safe to return through MCP at all. The
  classification annotations are used, not yet explained, in tutorial 1
  below; `AuditSink` does not appear there. A later part of this guide covers
  both on their own. Until then, see
  [Classify the model with `@LlmExposedModel`](../extending.md#classify-the-model-with-llmexposedmodel)
  in the full reference.

The diagram below shows where a `DataSourceAdapter` and an `IdentityResolver`
plug in: a `DataSourceAdapter` is always a bean an application's own
`@AutoConfiguration` registers, but an `IdentityResolver` can instead come
from a `dataprism.identity.resolver: pass-through` setting with no code at
all; either way, `DataPrismAutoConfiguration` refuses to start when either
bean is missing, and everything a `DataSourceAdapter` returns still passes
through the privacy engine before anything downstream sees it.

[![How a DataSourceAdapter and an IdentityResolver plug in: a DataSourceAdapter is always registered as a bean by an application's own @AutoConfiguration, an IdentityResolver can instead come from a pass-through configuration setting with no code, DataPrismAutoConfiguration's preflight refuses to start when either bean is missing, and the orchestrator's fan-out sends every fetched record on to the privacy engine's classification and scrubbing, with no path around it.](../assets/diagrams/extension-points.svg)](../assets/diagrams/extension-points.svg)

## Where to start

1. [Write a data-source adapter](write-an-adapter.md) — from an empty module
   to a pseudonymised MCP response, using `DataSourceAdapter` and the
   classification annotations.
2. [Write a custom identity resolver](custom-identity-resolver.md) — how one
   subject is recognised across sources, how `pass-through` differs, and how
   to register a resolver of your own.

Both walk through a real, compiled, tested module in this repository
(`data-prism-quickstart-extension`); every code block on those pages is
pulled directly from it, not hand-copied.
