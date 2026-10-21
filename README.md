# parity-cc-mcp

The contact center's MCP (Model Context Protocol) server. AI agents connect to it as MCP clients
and call its tools:
- the context and cases of the customer a session works for;
- each tenant's own read-only tools into its systems.

It is `ARCHITECTURE.md` §3.23's parity-context-mcp, under a new name.

It owns no data. Every tool is one request, over Kafka, to the service that owns the data:
customer-context (§3.13). customer-context isn't built yet, so until it is, every tool answers
that customer-context didn't answer. The plan, the decisions and the open questions are in
`PROGRESS.md`.

**Status (2026-10-21): v1 built.** 36 tests: an MCP client from the SDK calls the server over
HTTP, and a fake customer-context answers on embedded Kafka.

## Build and run

```
mvn package
java -jar target/parity-cc-mcp.jar \
  --spring.kafka.bootstrap-servers=redpanda:9092 \
  --parity-cc-mcp.tenant-tools-file=/etc/parity-cc-mcp/tenant-tools.yml
```

- **Stack:** Java 21, Spring Boot 4.1.1, Spring AI 2.0.1's MCP server (WebMVC) on the MCP Java
  SDK 2.0.0, Spring Kafka, Maven, Lombok.
- **Built-in tools:** annotated methods (`@McpTool`) in `tools/CaseTools.java`.

| Property | Default | Meaning |
|---|---|---|
| `server.port` | `8080` | HTTP port of the MCP endpoint |
| `spring.ai.mcp.server.streamable-http.mcp-endpoint` | `/mcp` | The MCP endpoint |
| `parity-cc-mcp.topics.customer-context-requests` | `customer.context.requests` | Where customer-context takes requests |
| `parity-cc-mcp.topics.replies` | `context.mcp.replies` | Where the owners answer. Every instance reads all of it, in a consumer group of its own |
| `parity-cc-mcp.topics.audit` | `context.mcp.calls` | One event per tool call |
| `parity-cc-mcp.owner-timeout` | `3s` | How long a tool waits for customer-context |
| `parity-cc-mcp.result-character-limit` | `25000` | The longest tool result |
| `parity-cc-mcp.tenant-tools-file` | empty | The tenant tools file. Empty: none |
| `parity-cc-mcp.allowed-origins` | empty | Browser origins allowed to call `/mcp`. Empty: a request with an `Origin` header is refused |
| `parity-cc-mcp.lifecycle.*` | `/tmp/parity-cc-mcp.healthy`, `.drained`, `30s` | The health and drain files, and the drain timeout |

## Connecting

- **Transport:** MCP streamable HTTP, stateless. `POST /mcp` takes JSON-RPC and answers JSON.
  There are no sessions, so any instance answers any request.
- **Who is asking:** every request carries scope headers.
  - The LLM gateway sets them. The model never does, and no tool takes a customer argument.
  - v1 trusts the headers on a zone-internal connection. OAuth bearer tokens and mTLS come
    later (§3.23).
  - A request without a valid tenant, profile and person is refused with 401.
  - A request with an `Origin` that isn't allowed is refused with 403. Only browsers send
    `Origin`, so this stops a web page from calling the endpoint through someone's browser.

| Header | Required | Value |
|---|---|---|
| `X-Tenant-ID` | yes | The tenant |
| `X-Profile` | yes | `agent` or `supervisor`. `customer` (bots) isn't on yet |
| `X-Person-ID` | yes | The agent or supervisor the session acts for |
| `X-Customer-Key` | for the case and tenant tools | The customer of the interaction |
| `X-Interaction-ID` | no | The interaction |

The server's `instructions` (sent at `initialize`) tell the agent how the tools fit together.
They are set in `application.yml`.

## Tools

Built to Anthropic's guidelines for agent tools ("Writing effective tools for agents") and MCP
servers (the `mcp-builder` skill):
- **Names:** `parity_<action>_<resource>`, snake_case with a service prefix, because the
  server runs next to other MCP servers.
- **Arguments:** snake_case and precise (`case_id`). Fixed choices are JSON Schema enums.
- **Descriptions:** written for a new hire. Each says what the tool does, when to use it and
  where its ids come from.
- **Reading tools:**
  - they page, with `limit` (1 to 50, default 20) and `offset`;
  - in JSON they also return `total`, `count`, `has_more` and `next_offset`;
  - they take `response_format`: `markdown` (the default) or `json`.
- **Result size:** no result is longer than 25,000 characters. A longer one is cut (markdown)
  or refused (json), with a note on how to ask for less.
- **Annotations:** every tool has `readOnlyHint`, `destructiveHint`, `idempotentHint` and
  `openWorldHint`.
- **Errors:** errors are tool results with `isError`, not protocol errors. Each says what was
  wrong and what to do instead, and none shows internals.
- **Sensitive data:** card numbers (typed or spoken; brand, length and Luhn) and IBANs
  (mod 97) are removed from every result and from everything the agent writes. They keep
  their last four characters: `[card ending 1111]`.

| Tool | Hints | Does |
|---|---|---|
| `parity_get_customer_context` | read-only, idempotent | What the customer said and what happened on every channel, signal-detection's alerts and people's notes, newest first. Filters: `channel`, `since` |
| `parity_list_cases` | read-only, idempotent | The customer's cases: problem, state, assessment, items, a question to a person and its answer. `include_solved` |
| `parity_open_case` | writes, additive | A case for one new problem, with its `item_ids`, `summary`, `risk` and `action` |
| `parity_update_case` | writes, destructive, idempotent | Changes a case's `state`, `problem`, `summary`, `risk` or `action`, or adds items (`add_item_ids`) |
| `parity_ask_person` | writes, additive | Hands a case to the `agent` or the `supervisor` with a `question` and an optional `draft` |
| Tenant tools | read-only, idempotent, open world | Each tenant's own tools (below) |

`state` is `open`, `waiting_customer`, `waiting_us` or `solved`. `risk` is `low`,
`medium` or `high`. `channel` is `voice`, `web_chat`, `sms`, `whatsapp`, `messenger`,
`instagram` or `email`.

## Tenant tools

One generic tool for every industry: each tenant defines the lookups its systems offer, such as
a shop's orders, a clinic's appointments or an insurer's policies.
- **Where they're defined:** a YAML file, given with `parity-cc-mcp.tenant-tools-file`, that
  operators review and deploy with the service.
  - It doesn't go through admin-api, for security.
  - An invalid file stops the service, with every problem listed.
  - A changed file takes effect on a rolling restart.
- **Read-only for now.**
- **Format and checks:** see `src/test/resources/tenant-tools.yml` and
  `tenanttools/TenantToolsFile.java`. The checks:
  - names are snake_case, unique per tenant, and never start with `parity_`;
  - `description` is required;
  - `input_schema` is a valid JSON Schema of type `object`, without `response_format`;
  - `call` is exactly one of `https` (an `https://` URL and the name of a secret) or `crm`
    (a connector and an operation). Credentials are never in the file;
  - `customer_reference` is `crm_id`, `account_number`, `phone`, `email` or `customer_key`;
  - `result.customer_field` and `result.fields` are set;
  - `profiles` are `agent` and/or `supervisor`;
  - `timeout` is 100 ms to 10 s, 3 s by default.
- **Who sees them:** `tools/list` shows a session the built-in tools plus its own tenant's tools
  for its profile. Another tenant's tool doesn't exist for it.
- **What a call does:**
  1. the arguments are checked against the tenant's `input_schema`;
  2. `call_tenant_tool` goes to customer-context, which adds the customer's reference and calls
     the tenant's system through its connectors and webhook-proxy;
  3. every result whose `customer_field` isn't that reference is dropped;
  4. only `result.fields` are kept, and cards and IBANs are removed.

## The owner contract (customer-context)

What parity-cc-mcp sends, so customer-context can implement it when it's built.

- **Requests** on `customer.context.requests`, keyed by tenant. Every request has:
  - `cmd`;
  - `id`, which is echoed back;
  - `reply_to`;
  - the scope: `tenant_id`, `profile`, `person_id`, `customer_key` and `interaction_id`.
- **Answers** on `reply_to`: `{"id", "ok": true, "result"}` or
  `{"id", "ok": false, "error": {"code", "message"}}`.
- **Error codes:** `not_found`, `bad_request`, `conflict`, `forbidden` and `internal`.
  - The message of the first four reaches the agent, so it's written for one: what was wrong
    and what to do.
  - `internal` messages never reach it.
- **Answering in time:** customer-context answers within `owner-timeout` (3 s), or the tool
  tells the agent the data isn't available.
- **Customer scope:** customer-context only ever reads or changes the `customer_key`'s own
  data.

| cmd | Fields | Result |
|---|---|---|
| `get_context` | `limit`, `offset`, optional `channel`, `since` (ISO-8601) | `customer {customer_key, name?}`, `total`, `items [{item_id, at, channel, kind: said\|signal\|note, who, text?, signal? {type, severity}, case_id?}]`, newest first |
| `list_cases` | `limit`, `offset`, `include_solved` | `total`, `cases [case]`, newest first |
| `open_case` | `problem`, `item_ids [..]`, optional `summary`, `risk`, `action` | `case` |
| `update_case` | `case_id`, any of `state`, `problem`, `summary`, `risk`, `action`, `add_item_ids [..]` | `case`. Adding an item twice changes nothing |
| `ask_person` | `case_id`, `ask: agent\|supervisor`, `question`, optional `draft` | `question_id`, `case_id`, `ask`, `asked_at` |
| `call_tenant_tool` | `tool`, `call {type: https, url, secret}` or `{type: crm, connector, operation}`, `customer_reference`, `arguments {..}`, `timeout_ms` | `customer_reference` (the customer's resolved value), `data`: one object or a list |

**A case** has:
- `case_id`, `problem`, `state`, `risk`, `summary`, `action`, `item_count`, `opened_at` and
  `updated_at`;
- `question`, if there is one: `{question_id, ask, question, asked_at, answer?, answered_by?,
  answered_at?}`.

Ids are short and readable (`C-12`, `IT-301`). Anthropic's guidelines recommend that over UUIDs,
because models copy readable ids more reliably.

## Audit

- **What is sent:** one `tool.called` event per call on `context.mcp.calls`, keyed by tenant.
  - Fields: `at`, `instance`, `tool`, `outcome` (`ok` or an error code), `latency_ms`,
    `tenant_id`, `profile`, `person_id`, `customer_key` and `interaction_id`.
  - Never the arguments or the result.
- **When sending fails:** publishing doesn't wait. A failed send is logged, and the call still
  works (`PROGRESS.md`, open questions).

## Health and drain

- **Healthy:** `/tmp/parity-cc-mcp.healthy` is created once the service has started and its
  replies listener has its partitions.
- **On SIGTERM:**
  1. it removes the healthy file;
  2. it answers new MCP requests with 503 and `Retry-After: 1`;
  3. it waits up to 30 s for the calls in progress;
  4. it creates `/tmp/parity-cc-mcp.drained`.
