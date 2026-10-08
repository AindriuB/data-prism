# 175 — Interface and abstract map key types

**Repo:** .
**Base:** `release/0.6.0-jackson3`. Unplanned, follow-up to 173.

## Origin
External review P2, and PLAN follow-up (i) from 173. A record with a `Map<CharSequence|Comparable|Serializable, V>` component passed startup but every request was refused (`SOURCE_MODEL_NOT_A_RECORD`), because the key modifier rejected the declared interface before the per-key runtime check ran.

## Outcome
- A declared interface or abstract key type is deferred to `CheckedKey`'s per-key check at write time. JDK key classes on the allowlist pass and match Jackson 2; user classes are refused at any depth, including proxies, lambdas and records implementing a sealed interface.
- Concrete user key classes are still refused at startup.
- `CheckedKey` forwards `resolve`, `createContextual` and `handledType`, which closes 173 follow-up (ii). It is package-private so a unit test can reach it; there is no public API change.
- Known fail-closed differences from Jackson 2: JDK `StringBuilder` and `CharBuffer` keys are refused (Jackson 2 wrote their text).
- Note for 162 migration docs: a `Date` key declared as `Comparable` is written by `Date.toString()`, which is time-zone dependent. Jackson 2 did the same.
- Verified: tester PASS at f1f5430f (full reactor JDK 21, 1656 tests, 0 failed, HEAD unchanged during the run; release-profile package green). b97b1d25 is a Javadoc-only follow-up. Review APPROVE.

## Open follow-up
A component-level `@JsonSerialize(keyUsing=<user serializer>)` can write a user key by `toString()`; a probe showed a personal-data-looking string emitted as a field name. D-173-2 allows author-chosen serializers, but field names are not classified like values. Either refuse `keyUsing` on source records or document that the engine must treat the map keys of such components as data.
