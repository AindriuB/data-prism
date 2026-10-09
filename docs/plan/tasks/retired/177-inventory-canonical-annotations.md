# 177 — Make the bean inventory test independent of Annotation.toString()

## Origin
Unplanned. PR #124 CI failed on the build (21) and build (25) jobs: `AutoConfiguredBeanInventoryTest` compared `Annotation.toString()`, whose attribute order differs between JDK vendors (CI Temurin versus the local Oracle JDK).

## Outcome
- The test now uses a canonical renderer: attributes sorted by name, deterministic value rendering, recursive for nested annotations and arrays.
- `bean-methods.txt` was regenerated and proven a pure format change for all 53 beans.
- Passes on Oracle JDK 21 and OpenJDK 25. The mutation proof is retained.
- Lesson: snapshot tests must not depend on unspecified `toString` formats.
