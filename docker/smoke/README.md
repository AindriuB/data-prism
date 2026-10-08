# Java runtime smoke test

`java-runtime-smoke.sh` proves, against images built from this working tree:

- the distribution and server images run Java 25 (`java -version` reports `25.`);
- the distribution image refuses to start with no configuration, with the same
  named `MISSING_*` refusal `publish-image.yml` checks;
- two server members from `docker/multi-instance` form one Hazelcast cluster
  (both log `Members {size:2`) within 180 seconds;
- every `sun.misc.Unsafe` warning line from both members is written to
  `docker/smoke/target/unsafe-warnings.txt`, and the count is printed.

Run it from anywhere, needing only Docker with Compose v2 (2.24.4 or later):

    bash docker/smoke/java-runtime-smoke.sh

The stack runs under a unique compose project name (`data-prism-smoke-<pid>`),
so teardown never touches a `data-prism-multi-instance` stack you have running.
The stack, its volumes, its locally built images and the two temporary images
are removed on exit, including on failure.

Limits: it covers only the architecture of the machine it runs on (amd64 is
covered by the publish-image CI legs). It proves the runtime starts and the
cluster forms, not that privacy behaviour is correct under JDK 25; that is the
test suite on 25 (task 146).

Hazelcast triggers a `sun.misc.Unsafe` warning on JDK 24 and later (JEP 471 and
498). It is expected, behaviour is unchanged, and it is recorded on purpose
rather than hidden: no image passes `--sun-misc-unsafe-memory-access=allow`
(decision D-140-C). A count of 0 is also acceptable.
