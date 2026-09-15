#!/usr/bin/env bash
# Launches data-prism-example's stdio MCP server (ExampleApplication) for a
# local agent client.
#
# What this is: the fixture-development transport described in
# docs/configuration.md ("Fixture development" row) and docs/agents/stdio.md.
# It carries one fixed development principal, holding only
# GET_ENTITY_CONTEXT, against three in-memory stub adapters
# (StubCustomerAdapter, StubAccountAdapter, StubOrderAdapter — see
# data-prism-example/src/main/java/.../Stub*Adapter.java). Nothing here
# reaches a network, a real API, or a real credential.
#
# What this is not: this is not how a protected deployment is reached. It
# refuses to run under `spring.profiles.active=production` (ExampleApplication
# checks this itself) and there is no configuration that turns stdio into an
# authenticated transport — see "Configuration rules" in docs/configuration.md.
#
# An agent client with a "command"/"args" style stdio MCP configuration runs
# this script directly; see docs/agents/stdio.md for a config snippet. It
# takes no arguments and needs nothing beyond a JDK 21 and network access to a
# Maven repository the first time it runs (subsequent runs use the local
# repository cache, same as any other Maven build in this repository).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
module="data-prism-example"
classpath_file="$(mktemp)"
trap 'rm -f "$classpath_file"' EXIT

mvn -q -f "$repo_root/pom.xml" -pl "$module" -am compile dependency:build-classpath \
  -Dmdep.outputFile="$classpath_file"

classpath="$(cat "$classpath_file"):$repo_root/$module/target/classes"

exec java -cp "$classpath" io.github.aindriub.dataprism.example.ExampleApplication
