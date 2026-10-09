# 178 — Bounded AuditEventListeners.close(); json-directory read through a Condition

## Origin
Unplanned. Owner-relayed review findings on PR #124:
1. `AuditEventListeners.close()` logged its final drop warning on the closing thread, so a blocked appender hung shutdown.
2. The projection and its preflight used `@ConditionalOnExpression` with the json-directory inside a SpEL string literal, so a valid path containing an apostrophe prevented startup. This was a regression from 161.

## Outcome
- The final report now runs on a short-lived named daemon thread, joined for at most 1 s.
- `AuditSinkSelection.JsonDirectoryConfigured` is a Binder-based `SpringBootCondition` on both beans, semantically identical to the old expression for normal values.
- Tests (a hanging appender; apostrophe, spaces, `#{`, `${` and backslash paths) failed before the fix.
- Tester PASS 1711/0, 10 repeat runs, inventory test on JDK 25. Review APPROVE.
- Lesson: never interpolate configured values into an expression string.
