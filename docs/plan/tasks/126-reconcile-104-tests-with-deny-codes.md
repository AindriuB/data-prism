# 126 — Update task 104's tests to the `DENY:<code>` audit form from task 123

**Release:** 0.4.0
**Depends on:** 104 and 123, both merged on the planning branch, which is red

## Why

Tasks 104 and 123 each passed against base 2c32884. After both merged, the
full build fails. 104's
`OversightConfigurationTest.a_second_pending_request_over_the_cap_is_refused_and_audited`
(~:250) asserts the audit `policyDecision` equals the bare code
`TOO_MANY_PENDING`. Since 123, every denial is recorded as `DENY:<code>`, and
the first event is `DENY:APPROVAL_REQUIRED`.

## Owns

- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/OversightConfigurationTest.java *(expected `policyDecision` values only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ReidentificationConfigurationTest.java *(expected `policyDecision` values only)*
- Any other test file that asserts a bare refusal code or a plain `"DENY"` as an audit `policyDecision` *(expected values only; list each one in the close-out)*

## Acceptance

- [ ] Every assertion on an audit `policyDecision` expects the exact `DENY:<code>` value now written. Exact equality only: no `startsWith` or `contains`. No assertion is weakened or removed.
- [ ] Repo-wide grep of test sources for `policyDecision` assertions with a bare code or `"DENY"`. Each hit is updated or justified in the close-out. Example: a synthetic pre-0.4.0 record kept on purpose.
- [ ] No production code changes.
- [ ] `mvn clean verify` over the full reactor from a clean tree exits 0. Report the real exit code. Do not run install.
