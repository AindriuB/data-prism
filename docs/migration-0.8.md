---
title: Migrating to 0.8.0
description: What changes when upgrading from 0.7.x to 0.8.0. Sections for the key resolver change are here now; more are added as 0.8.0 is documented.
---

# Migrating from 0.7.x to 0.8.0

This page lists the changes in 0.8.0 that can stop something working. It is still being filled in; the
sections below cover the key-resolver change only.

## Breaking: provider references reach `resolveProviderReference`

`HmacKeyReferenceResolver` has a second method, `resolveProviderReference(keyId, reference)`. A key configured as a
`provider-reference` (the privacy HMAC key, the cluster key and its `next` and `previous`, and the name key) is read
through it. A key configured as an `environment-variable` is still read through `resolve`.

The default of `resolveProviderReference` refuses with `MISSING_KEY_PROVIDER` and names no reference, so a resolver
that reads only environment variables can never treat a provider reference as the name of a variable.

If you wrote a custom `HmacKeyReferenceResolver` that handled provider references inside `resolve()`, implement
`resolveProviderReference` as well. Until you do, a `provider-reference` fails at startup with
`MISSING_KEY_PROVIDER`. A resolver that only ever gets `environment-variable` configuration needs no change.

The packaged server reads environment variables only, so it refuses a `provider-reference` for any of these keys.

## Breaking: a schema-invalid tool call returns `TOOL_INPUT_INVALID`

Before 0.8.0 the MCP SDK checked a call's arguments against the tool's input schema before any Data Prism code
ran, and answered `Tool (<name>) input validation failed: ...`. Such a call was not authenticated, audited or
counted. Each tool now checks its own input, after authentication and authorisation, so:

- a schema-invalid call returns the one refusal `TOOL_INPUT_INVALID`, with the call's correlation id. It names no
  field and no value, and it is the same for every violation;
- on `list_sources`, a call that carries a `page` key and breaks the schema anywhere (a `page` that is empty,
  longer than `dataprism.links.max-length`, not a string or `null`, or any other argument) is answered as a forged
  link, `LINK_INVALID` ("The link is not valid."), and counts once toward the link-attempt limiter;
- every such call is audited once (`DENY:TOOL_INPUT_INVALID`, or the link refusal) and counted as denied;
- a role without the tool's capability still gets `TOOL_NOT_PERMITTED`, valid input or not.

A client that matches the old `input validation failed` text must match `TOOL_INPUT_INVALID` instead. A blank
`entityType` or `subjectId` on `get_entity_context` or `compare_entity_sources` no longer answers "entityType and
subjectId are both required"; it is `TOOL_INPUT_INVALID`. The served input schemas are unchanged. A request that is
not a valid `tools/call` (for example `arguments` that is not an object, or an unknown tool name) is still refused
by the SDK before any handler, and is not audited.
