# parity-cc-mcp: progress notes

Last updated 2026-10-21. **v1 built** (36 tests). This repository was
`parity-customer-context` until 2026-10-19 (history below). The protocol, the tools and the
owner contract are in `README.md`.

## Where we stopped

- **v1 built on 2026-10-21,** after the user's go-ahead ("ok lets start implementation in java
  of course"), to Anthropic's guidelines ("lets use anthropic guidelines to build the mcp";
  what they changed is below).
- **Nothing real behind the tools yet:** customer-context (§3.13) isn't built, so every tool
  answers that customer-context didn't answer. The tests use a fake one (`FakeCustomerContext`)
  that follows the owner contract in `README.md`.
- **Next, waiting for the user:**
  - the open questions below;
  - the architecture docs. `ARCHITECTURE.md` §3.13 still describes parity-customer-context as
    built, and §3.23 still says parity-context-mcp, read-only, with the LLM gateway as its
    client. The user agreed on 2026-10-19 that the docs are renamed and updated to match what
    gets built.
- **No git repository:** the lesson below still applies. Ask the user before a big change.

## What parity-cc-mcp is (the user's decisions, 2026-10-19)

1. "my intention is that the parity-consumer-context is renamed and is actually the
   implementation for parity-cc-mcp which implements a model context protocol".
2. **It is the architecture's parity-context-mcp (§3.23) under a new name.**
3. **Stateless:** it owns no data. Each tool reads from, or writes to, the service that owns
   the data.
4. **The AI runs outside, as an MCP client.** Any agentic AI that speaks MCP connects and
   calls the tools. This is what "should be able to use any agentic ai tool" meant. The
   service has no AI of its own.
5. **Tools may write:** open a case, put items in it, set its state and assessment, and hand
   it to the agent or supervisor with a question. The architecture said read-only; the user
   chose otherwise.
6. **The gathering and storing code is dropped.** customer-context (§3.13) is built later,
   from scratch. Until then the tools have nothing real behind them.
7. **Tenant tools** (2026-10-21): one generic tool, defined per tenant, because the platform
   serves tenants in every industry. "read-only for now, no admin-api usage for security
   reasons".
8. **Anthropic's guidelines** (2026-10-21): "lets use anthropic guidelines to build the mcp".
   Followed: the engineering post "Writing effective tools for agents" and the `mcp-builder`
   skill's MCP best practices (below).

## Kept from the parity-customer-context work (the user's words, still true)

- **What a signal is:** "a signal is some information or context we can get from emails,
  calls, messages, anything, any channel that might help reach a resolution of the problem
  to be solved for the customer".
- **Where signals come from:** "the information signals come from the channel, literally
  what the customer says and then if requested the ai agent will evaluate and asses it and
  it may go to the agent if necessary also the purpose of all of this is to gather context
  from the calls or channels".
- **How the context is organized:**
  - the case is the center: the customer's problem, open until it is solved;
  - one case per problem;
  - the AI assesses on demand ("on demand via api"); "ai should asses and update the
    state";
  - when the AI is asked, it groups the customer's new items into cases.
- **The AI side** (the user's words, 2026-10-08): "should be able to use any agentic ai tool
  or gather info from the supervisor and notes and prompt the supervisor, notify the
  supervisor or agent and the supervisor or agent would asses and solve".
- **Card numbers never leave a service** (§3.23: results have card numbers and IBANs
  removed). The card detection from parity-signal-detection (`DigitRuns`, `PaymentCards`:
  brand prefix, length, Luhn, spoken digits) fits that.

## v1 as built (2026-10-21)

- **Stack:** Java 21, Maven, Lombok, Spring Boot 4.1.1, Spring AI 2.0.1's MCP server (WebMVC)
  on the MCP Java SDK 2.0.0.
  - The built-in tools are `@McpTool` methods.
  - The tenant tools are added by a thin wrapper around the SDK's request handler
    (`TenantToolsHandler`). The SDK keeps one tool list for the whole server, so the wrapper
    lets `tools/list` show each tenant only its own tools. This replaced the fallback with two
    built-in tools.
- **Transport:** streamable HTTP at `/mcp`, stateless, which the `mcp-builder` guidance
  recommends for remote servers. There are no sessions, and any instance answers.
- **Who is asking:**
  - comes from the headers `X-Tenant-ID`, `X-Profile`, `X-Person-ID`, `X-Customer-Key` and
    `X-Interaction-ID`, checked before any request runs;
  - a missing tenant, profile or person gives 401;
  - a foreign `Origin` gives 403 (DNS rebinding);
  - the `customer` profile is refused for now.
- **Owners over Kafka:**
  - requests in the `cmd`/`id`/`reply_to` style, keyed by tenant;
  - one replies topic that every instance reads in a consumer group of its own;
  - a 3 s timeout.
- **Built-in tools (customer-context):** `parity_get_customer_context`, `parity_list_cases`,
  `parity_open_case`, `parity_update_case`, `parity_ask_person`.
- **Tenant tools:**
  - the YAML file at startup (`parity-cc-mcp.tenant-tools-file`), fully checked, and an
    invalid file stops the service;
  - arguments are checked against the tenant's JSON Schema;
  - calls go to customer-context's `call_tenant_tool`;
  - results for another customer are dropped, and only the listed fields are kept;
  - every tenant tool also gets `response_format`.
- **Masking:** card numbers (copied from parity-signal-detection) and IBANs (mod 97) are
  removed from every result and from everything the agent writes.
- **Audit:** `tool.called` on `context.mcp.calls`, without content.
- **Lifecycle:**
  - the healthy file, once the replies listener has its partitions;
  - on SIGTERM: 503 for new requests, a wait for the calls in progress, then the drained file.
- **Tests (36):**
  - an SDK MCP client against the server over HTTP, with a fake customer-context on embedded
    Kafka;
  - the catalog's checks;
  - masking;
  - the drain.

**What Anthropic's guidelines changed in the plan**

- **Names:** `parity_<action>_<resource>`, because the server runs next to other MCP servers.
  - `get_customer_context` → `parity_get_customer_context`;
  - `get_cases` → `parity_list_cases`;
  - `open_case`, `update_case` and `ask_person` got the prefix.
- **Arguments:** snake_case and precise (`case_id`, `add_item_ids`), with JSON Schema enums for
  fixed choices.
  - The schema generator takes names from Java parameter names, so the tool methods' parameters
    are snake_case.
  - It writes enum constants as they are, so the `Choices` enums are lowercase.
- **Reading tools:**
  - `limit` (1 to 50, default 20) and `offset`, plus `total`, `count`, `has_more` and
    `next_offset`;
  - `response_format`: `markdown` (default) or `json`;
  - a 25,000-character limit, and over it a note on how to ask for less.
- **Annotations:** every tool has its four hints. `parity_update_case` is marked destructive,
  because it replaces values.
- **Errors:**
  - errors are results with `isError`, with what was wrong, what to do and an example;
  - owner messages reach the agent only for `not_found`, `bad_request`, `conflict` and
    `forbidden`;
  - an unknown tool stays a JSON-RPC error, as the MCP spec has it.
- **Descriptions:** written as for a new hire, and the server's `instructions` say how the
  tools fit together.
- **Readable ids** (`C-12`, `IT-301`) instead of UUIDs, asked of customer-context in the owner
  contract.
- **Not done yet:**
  - `outputSchema` and `structuredContent`: the JSON format covers the structured case for now;
  - the evaluation: 10 realistic, read-only questions with answers that can be checked. It
    needs real data, so it waits for customer-context.

## Open questions

1. ~~**v1 tools:**~~ answered by the go-ahead on 2026-10-21: the five case tools and the tenant
   tools. §3.23's other tools wait for their owners: `get_customer`, `get_interaction`,
   `get_signals`, `search_knowledge`, `get_queue_status` and `get_calendar`.
2. **Who is asking:** headers now (built), OAuth tokens from Keycloak later. When?
3. **Supervisor scope:** a supervisor session reaches the customer in its `X-Customer-Key`,
   like an agent session. §3.23's "their teams' customers" needs team data. From where, if
   not admin-api?
4. **Customer profile (bots):** refused in v1 (built so). When does it come?
5. **Docs:** rename parity-context-mcp → parity-cc-mcp everywhere, rewrite §3.23 to what is
   built (writes, an outside MCP client, stateless HTTP, the scope headers), and correct
   §3.13. Now?
6. **Tenant tools:**
   - Is a YAML file at startup the right source? It's built that way.
   - Does "no admin-api" also cover the per-tenant switches for built-in tools (§3.9) and
     `get_calendar`?
7. **Audit when Kafka fails:** a call still works if its audit event can't be sent (it's
   logged). Should a write tool fail instead?
8. **One consumer group per instance** for the replies: each restart with a new instance id
   leaves an empty group behind in Kafka. Use fixed instance ids (the pod name), or a
   reply topic per instance?

## History: parity-customer-context (2026-10-08 → 2026-10-19)

- **2026-10-08 → 10-16:** built as customer-context: signals → Postgres → consultations on
  high severity → its own Claude agent (tool loop, structured output) or a person →
  assessment events. 20 tests, and a wire check with the real signal-detection.
- **2026-10-19:** the user defined a signal (above) and asked for a reassessment, then
  chosen:
  - the case at the center;
  - on-demand assessment;
  - the AI updates the state;
  - one case per problem;
  - the AI groups items into cases;
  - signals are literally what the customer says on each channel.

  A rework toward that was stopped halfway by the rename to parity-cc-mcp.
- **Lessons:**
  - the user's model is context-for-AI (MCP), not an AI loop inside the service;
  - this repo had no git, so dropped code is gone. Make a commit before a big change, if
    the user agrees to a git repo.
