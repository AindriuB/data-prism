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

# Re-identification smoke test

`reidentification-smoke.sh` runs the re-identification walkthrough of
`docs/quickstart.md` against the quickstart Compose stack and exits 0 only when
all of these hold:

- `get_entity_context` for subject `1001` returns a pseudonym, not `1001`;
- the requester approving their own request is refused with `SELF_APPROVAL`
  (409), and collecting before approval with `APPROVAL_NOT_APPROVED`;
- a second principal approves, and the requester's collect resolves to `1001`;
- the `reidentify` audit decisions, in order, are exactly `ALLOW:REQUESTED`,
  `DENY:SELF_APPROVAL`, `DENY:APPROVAL_NOT_APPROVED`, `ALLOW:APPROVED`,
  `ALLOW:RESOLVED`, and no `reidentify` audit line carries the real subject id.

Run it from anywhere, needing Docker with Compose v2 (2.24.4 or later), `curl`
and `python3`:

    bash docker/smoke/reidentification-smoke.sh

By default it builds the four quickstart images from this working tree
(`compose.build.yaml`), which takes a few minutes the first time. Set
`SMOKE_IMAGES=published` to use the published `0.8.0` images instead; it pulls
them first, so an image of the same tag built from a working tree on your
machine is replaced rather than used.

The script honours `QUICKSTART_OPERATOR_AUDIENCE` as compose does (default
`data-prism-quickstart-operator`) and exports it to the stack it starts.

The stack runs under its own project name (`data-prism-reid-smoke-<pid>`) on
ephemeral loopback ports, so a quickstart you have running on 8080, 8081 or
8544 is untouched. Every wait is bounded (240 seconds for the stack, 3 seconds
per probe, 20 seconds per request). The stack and its volumes are removed on
exit, including on failure. The tokens and the cluster and name keys exist only
in the script's memory and the containers' environment; nothing is written to
the repository.
