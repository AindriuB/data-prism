# 1.0 roadmap: draft ideas

Draft, not agreed. Nothing here is scheduled; tasks are planned only when the owner picks an item.

This is a proposed path from 0.5.0 to 1.0.0. It lives under `docs/plan/`, which mkdocs excludes, so it is internal planning and is not published.

## 0.6.0, hardening

- **Hazelcast cluster security.** Open-source Hazelcast has no member TLS or authentication. The options are a network-isolation reference deployment, a service mesh with mTLS, or Hazelcast Enterprise. This was previously deferred as the "secure cluster option" in PLAN.
- **Jackson 3 port.** The project is on Jackson 2 by decision D-139; this item revisits that.
- **Public API cleanup ahead of a freeze.** Remove or complete deprecations, and collapse the constructor overload sprawl in the MCP tools.
- **Load and soak testing.** Measure throughput, the read budget, and the cluster under load.
- **Supply chain.** SBOM, signed images and provenance, plus a dependency review.

## 0.9.0, release candidate

- Freeze the public API and the config properties.
- An external security review.
- A pilot deployment with real feedback.
- A production deployment and operations guide.

## 1.0.0

- A stability commitment: semver for the public API, the config properties and the audit format.
- A support and upgrade policy.
- Docs that say "supports" and never "compliant".

## Open questions

- Is cluster security a 1.0 blocker?
- Does the Jackson 3 port go before or after 1.0?
- What counts as a sufficient pilot?
