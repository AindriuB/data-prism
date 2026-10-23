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
