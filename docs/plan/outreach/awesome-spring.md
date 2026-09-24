# Draft: the canonical Spring/Spring AI awesome list

## Which list is canonical

Searched at execution time (2026-09-23) for candidate "awesome Spring" lists via
`gh api search/repositories --raw-field 'q=awesome-spring in:name' -f sort=stars` and the equivalent
`awesome-spring-ai` query. The realistic candidates and their star counts on 2026-09-23:

| Repo | Stars | Scope |
|---|---|---|
| ityouknow/awesome-spring-boot | 4561 | Chinese-language blog/tutorial links; informal, issue-based contribution process; no CONTRIBUTING file; not project-listing in style |
| ThomasVitale/awesome-spring | 1301 | English; books/tutorials/courses for the Spring ecosystem; resource-listing, not a home for individual projects/tools |
| spring-ai-community/awesome-spring-ai | 852 | English; `awesome.re`-badged; maintained under the `spring-ai-community` GitHub organisation, whose contributors include Spring AI's own lead (Christian Tzolov) and a Spring developer advocate (Josh Long); has a dedicated "Model Context Protocol" section with an "MCP Servers for Spring Projects" subsection |
| danvega/awesome-spring-ai | 278 | Personal list, lower star count, not organisation-maintained |

`spring-ai-community/awesome-spring-ai` is the one identified as canonical here: it is the only candidate that
is (a) organisation-maintained by people directly affiliated with the Spring AI project itself, (b) formatted
as a registered Awesome list, and (c) has a section purpose-built for MCP servers in the Spring ecosystem,
which is where an MCP privacy layer for Java/Spring would actually be found by a reader. The other two
general-Spring lists are resource/tutorial curations, not places where a project like this one would be
listed at all.

Repo: <https://github.com/spring-ai-community/awesome-spring-ai>
Access date: 2026-09-23

## Contribution rules (quoted)

There is no `CONTRIBUTING.md` in this repository (`gh api repos/spring-ai-community/awesome-spring-ai/contents/CONTRIBUTING.md`
returned 404 on 2026-09-23). The only stated rule is the README's own Contributing section.

Source: <https://github.com/spring-ai-community/awesome-spring-ai/blob/main/README.md#contributing>, fetched
2026-09-23 via `gh api repos/spring-ai-community/awesome-spring-ai/contents/README.md --jq .content | base64 -d`.

> ## Contributing
>
> Your contributions are always welcome! Please read the contribution guidelines first.

There are no contribution guidelines to read; the link target does not exist. The repository does carry the
`awesome.re` badge, which conventionally implies following the general Awesome-list format (one line per
entry, factual description, correct section) even where a project has not spelled that out itself; the format
below is inferred from the existing entries in the target section rather than from a written rule.

The repository's own description is explicit about scope: "A curated list of awesome resources, tools,
tutorials, and projects for building generative AI applications using Spring AI." That is the list's own
framing, not this project's — see the verdict below for what it means here.

## Section and format

Section: `#### MCP Servers for Spring Projects`, under `### Model Context Protocol`, under `## Code & Examples`.

Existing entries in that subsection: `- [Name](url) - Description.` Checked two of its current entries against
their own build files at execution time: the Swagger→MCP bridge (`Neo1228/spring-boot-starter-swagger-mcp`)
depends on `org.springframework.ai:spring-ai-starter-mcp-server-webmvc`, and the JVM diagnostics MCP server
(`brunoborges/jvm-diagnostics-mcp`) depends on `org.springframework.ai:spring-ai-mcp-server-spring-boot-starter`.
Both build on that framework. This subsection is "MCP servers built with it," not merely "MCP servers that
happen to be Java/Spring" — which is exactly why this project doesn't fit yet (see the verdict below).

## Exact entry line

```markdown
- [Data Prism](https://github.com/AindriuB/data-prism) - Fail-closed MCP privacy layer for Java/Spring: pseudonymises personal data per privacy scope, refuses anything unclassified, and can keep a hash-chained audit trail. Spring Boot starter or standalone server.
```

## PR title

`Add Data Prism (MCP privacy layer) to MCP Servers for Spring Projects`

## PR body

```markdown
Adds one entry under "MCP Servers for Spring Projects" for Data Prism, a Java/Spring MCP privacy layer. It
pseudonymises personal data per privacy scope, refuses anything it cannot classify, and can keep a
hash-chained audit trail (opt-in). It ships two supported deployment surfaces: a standalone MCP server and a
Spring Boot starter for embedding.

Repo: https://github.com/AindriuB/data-prism
License: Apache-2.0

Disclosure: I am the maintainer of Data Prism.

Note for maintainers: this project does not build on top of the framework this list is named for — it is a
plain Spring Boot / standalone Java MCP server. Please close this without ceremony if the "MCP Servers for
Spring Projects" subsection is meant to stay scoped to servers built with that framework, as its current
entries suggest.
```

## Eligibility verdict: hold

This list's own description scopes it to "building generative AI applications using Spring AI" (the
framework), and checking the target subsection's own entries confirms that framing rather than
contradicting it: both examined entries in "MCP Servers for Spring Projects" declare a dependency on it in
their own build files. This project has no dependency on, or integration with, that framework — it is a
Java/Spring Boot MCP server that does not use it at all, and nothing in its entry may claim otherwise. Given
the goodwill cost of submitting to the wrong shelf of a list before eligibility is clear (see the design
spec's reputation risk), hold this one until this project takes on a real integration with the framework this
list covers, or a maintainer confirms otherwise. Do not post without that.

## Pre-post checklist

- [ ] Re-check whether a `CONTRIBUTING.md` has since been added, and whether the "MCP Servers for Spring
      Projects" subsection's scope has been clarified one way or the other.
- [ ] Re-confirm at post time that the entry text still does not, anywhere, state or imply that this project
      integrates with the framework this list covers.
- [ ] Site is not live yet (task 82). This draft has no site links to swap.
