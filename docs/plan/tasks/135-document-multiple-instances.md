# 135 — Correct the cluster claims and document running multiple instances

**Repo:** `.`
**Release:** 0.4.1
**Base branch:** `claude/release-0.4.1` (not `main`)
**Depends on:** 132, 133
**Owns:**
- docs/multiple-instances.md *(new, with its own front matter)*
- mkdocs.yml *(one nav entry under "Reference" only)*
- docs/configuration.md *(the `dataprism.hazelcast` row at :77, a new `### dataprism.hazelcast` section, and the paragraph at :281-282 only)*
- docs/eu-ai-act.md *(the pause, approval and failing-closed paragraphs, :83-110, only)*
- docs/architecture.md *(:126-130, :184-192, :250-264, and one new entry under "Decisions worth knowing")*
- docs/reidentification.md *(:64-70 and :178-179 only)*

## Goal

The docs say more than v0.4.0 does. They claim that embedded "shares the read
budget across every member", that pause is cluster-wide, and that it refuses
"invalid member/TLS settings", although nothing read those settings. Make them
say exactly what 0.4.1 does. Add one page on running several instances:
- which state is shared once they join, and which is not otherwise;
- the cluster properties and their refusals;
- ports, Compose and Kubernetes examples;
- the network isolation the deployer must supply, because member traffic is
  neither encrypted nor authenticated in OSS Hazelcast.

## Context

- task 132's "Shape" and Acceptance give every property, default and refusal
  code.
- task 131 "Settled facts": Kubernetes discovery is OSS core; member TLS and
  member authentication are Enterprise. Owner decision D-0.4.1-A is in PLAN.md
  "Release 0.4.1".
- docker/multi-instance/compose.yaml and kubernetes.yaml from task 133, with
  snippet sections `cluster-env`, `k8s-service`, `k8s-networkpolicy` and
  `k8s-join`.
- docs-site/page-meta.yml:1-10: new pages carry their own front matter, as
  docs/faq.md:1-4 does.
- data-prism-hazelcast PrivacyCluster.java: map names, backup count 1, and
  eviction NONE on oversight and caller-rate.

## Acceptance

- [ ] `docs/multiple-instances.md` exists, is in the nav under "Reference"
      after `configuration.md`, and has a `title` and a `description` of 155
      characters or fewer. It covers:
      - **When you need it.** `single-node` gives a per-process budget, pause,
        approvals, rate limits and re-identification. State each consequence
        with N instances: the budget is N times larger, a pause on one
        instance does not stop the others, an approval is usable only where it
        was made, and so on.
      - **Shared state**, as a table: map name (`dataprism.*`), what it holds,
        whether keys hold raw subject ids (`IDENTITY_MAP`, `BUDGET_MAP` and
        `REIDENTIFICATION_MAP` do), and backup count.
      - **Configuration**: every `dataprism.hazelcast` cluster property and
        every refusal code from task 132, including `join.mode: none` as the
        explicit single-member choice.
      - **Ports**: 8080 MCP, 5701 member, and the operator port being whatever
        `dataprism.operator.port` says.
      - **Docker Compose** and **Kubernetes**, included by snippet from
        `docker/multi-instance/`, never hand-copied. Include DNS mode versus
        API mode for Kubernetes, and the RBAC that API mode needs.
      - **Network isolation is your responsibility.** Member traffic is
        plaintext and unauthenticated in OSS Hazelcast 5.7.0. The cluster name
        is a label, not a secret. Use a private network or `internal`
        network, a Kubernetes `NetworkPolicy`, or an mTLS service mesh. Never
        publish 5701. Anyone who can reach 5701 can read raw subject ids from
        the maps.
      - **Failure behaviour**: losing the owner and the backup of an entry
        together loses it. For a pause flag, that reopens a paused path.
        Budgets and rate-limit windows reset when their holders leave. Say
        what the operator should do, such as re-asserting the pause after a
        multi-member loss, and do not claim more than task 134 proves.
- [ ] `docs/configuration.md`:
      - the `dataprism.hazelcast` row lists the new properties and codes;
      - "invalid member/TLS settings" is replaced by the actual codes,
        including `HAZELCAST_TLS_UNSUPPORTED`;
      - "Cluster/TLS credentials are yes, by reference" is removed;
      - `embedded` is described as sharing state "across members that have
        joined one cluster";
      - :281-282 says the same and links the new page.
- [ ] `docs/eu-ai-act.md`: where pause, approvals or rate limits are
      described, it states that they are shared across instances only with
      `embedded` and a join mode, and are per instance otherwise. It links the
      new page.
- [ ] `docs/architecture.md`:
      - :126-130 and :184-192 match the new behaviour;
      - :250-264 keeps the 2026-09-09 decision;
      - a new dated entry "2026-10-06 — Cluster membership is explicit; member
        transport security is the deployer's" records D-0.4.1-A and D-0.4.1-B,
        and the rejected options with their costs.
- [ ] `docs/reidentification.md`:
      - :64-70 says the reverse map is shared only across joined members;
      - :178-179 says that `server.json` and the image now declare the
        operator variables and port, and that end-to-end operator use in the
        Compose quickstart is not yet done.
- [ ] `grep -nE 'compliant|tamper-proof|encrypted' docs/multiple-instances.md`
      finds no claim about member traffic other than that it is **not**
      encrypted. Wording is "supports" throughout.
- [ ] `mkdocs build --strict` (venv `/Users/Andrew/workspace/data-prism/.venv-docs`),
      `python3 docs-site/hooks/check_site.py` and
      `python3 docs-site/hooks/check_snippet_markers.py` exit 0.

## Out of scope

- `CHANGELOG.md`, version literals, README and `docs/faq.md`. Those are task
  136.
- The `dataprism.correlation` and `dataprism.audit.output` sections of
  `docs/configuration.md`, which are task 113 (0.5.0), and `docs/audit.md`,
  which is task 116.
- Editing anything under `docker/`. If a snippet section is missing, report
  it to task 133's owner; do not add it here.
