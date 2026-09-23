---
title: Keep one customer recognisable across systems without exposing identity
description: How Data Prism gives one customer one consistent pseudonym across systems, while surfacing disagreements between them as findings instead of hiding them.
---

# Keep one customer recognisable across systems without exposing identity

An LLM agent investigating one customer across several internal systems
needs to tell that it is looking at the same person each time, or the
investigation falls apart. Redacting every identifying field is the obvious
privacy fix, but it destroys exactly that: once three systems' names for one
person are all replaced with the same generic marker, the model cannot tell
whether it is looking at one person or three.

## What Data Prism does

Data Prism gives one subject one synthetic pseudonym, derived
deterministically from the privacy scope, the subject, the namespace, the
algorithm version and an HMAC key. The same customer reads as the same
pseudonym in every source's data for a given case, so an agent can follow
one identity across systems without ever seeing a raw name, email or
account number.

Collapsing several sources' values onto one pseudonym would normally hide
that those sources actually disagreed about what to call the person — a
"Patrick Murphy" in one system and a "Pat Murphy" in another both render as
the same pseudonym, and nothing in that pseudonym says the underlying values
differed. Data Prism's [pseudonym
collapse](../tools.md#the-pseudonym-collapse) is exactly this effect, and it
is handled by comparing the raw values before pseudonymisation and attaching
the result as a `ConsistencyFinding` rather than by making the difference
disappear. [Consistency
findings](../tools.md#consistency-findings) report which sources agreed with
which — never what any of them actually held — across six kinds, from an
outright disagreement to a same-value formatting difference to one source
appearing to hold an abbreviated form of another's value. A [worked
example](../tools.md#worked-example-the-pseudonym-collapse-demonstrated)
shows three sources' three different spellings of one name collapsing to one
pseudonym, with the `PERSON_NAME` finding as the only place that
disagreement is still visible.

Pseudonyms are also scoped to the investigation, not just to the subject.
[Scope isolation](../tools.md#scope-isolation) means the same customer
renders as two different, unrelated-looking pseudonyms under two different
case ids — nothing about one investigation's pseudonym lets a caller in a
different case tell it refers to the same underlying subject.

## What it does not do

Consistency across systems here means one pseudonym per subject, not
automatic entity resolution: correlating a subject across sources requires
an identifier the sources already share, resolved by a reviewed
`IdentityResolver` (see [`docs/extending.md`](../extending.md)) — Data Prism
does not infer that two different identifiers across two systems belong to
the same person. It is not anonymisation: the pseudonym is reproducible from
the same scope and key, and GDPR Art. 4(5) treats that as personal data
still.

## Where to go next

- [`docs/tools.md`](../tools.md) has the full worked examples for both
  shipped tools, `get_entity_context` and `compare_entity_sources`, and the
  complete list of consistency-finding kinds.
- [`docs/quickstart.md`](../quickstart.md) proves a pseudonymised
  `get_entity_context` call end to end against fixture data with one
  command; it does not exercise `compare_entity_sources`, so see
  `docs/tools.md` above for the pseudonym collapse itself.
- [`docs/extending.md`](../extending.md) covers writing the `IdentityResolver`
  that decides which sources' identifiers refer to the same subject.
- [`docs/configuration.md`](../configuration.md) is the contract for the
  scope and key configuration pseudonym consistency depends on.
