package parity.ccmcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.test.context.EmbeddedKafka;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "parity-cc-mcp.owner-timeout=1s",
        "parity-cc-mcp.tenant-tools-file=src/test/resources/tenant-tools.yml",
        "parity-cc-mcp.lifecycle.instance-id=test-1",
        "parity-cc-mcp.lifecycle.healthy-file=target/parity-cc-mcp.healthy",
        "parity-cc-mcp.lifecycle.drained-file=target/parity-cc-mcp.drained"})
@EmbeddedKafka(partitions = 1, topics = {"customer.context.requests", "context.mcp.replies", "context.mcp.calls"})
@Import({FakeCustomerContext.class, ParityCcMcpIntegrationTest.AuditEvents.class})
class ParityCcMcpIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    FakeCustomerContext owner;

    @Autowired
    AuditEvents audit;

    @Autowired
    JsonMapper json;

    private final List<McpSyncClient> clients = new ArrayList<>();

    @TestConfiguration
    static class AuditEvents {
        final List<JsonNode> events = new CopyOnWriteArrayList<>();
        private final JsonMapper json;

        AuditEvents(JsonMapper json) {
            this.json = json;
        }

        @KafkaListener(topics = "context.mcp.calls", groupId = "test-audit")
        void onEvent(String value) {
            events.add(json.readTree(value));
        }
    }

    @AfterEach
    void closeClients() {
        clients.forEach(McpSyncClient::closeGracefully);
    }

    // --- the tool list ------------------------------------------------------------------

    @Test
    void anAgentSeesTheBuiltInToolsAndItsTenantsToolsForAgents() {
        List<String> names = names(agent("acme", "cust-77").listTools().tools());

        assertThat(names).containsExactlyInAnyOrder("parity_get_customer_context", "parity_list_cases", "parity_open_case",
                "parity_update_case", "parity_ask_person", "shop_get_order");
        assertThat(names).allMatch(n -> n.matches("[a-z][a-z0-9_]+"));
    }

    @Test
    void aSupervisorAlsoSeesTheSupervisorsTenantTools() {
        assertThat(names(client("acme", "supervisor", "sup-1", "cust-77").listTools().tools()))
                .contains("shop_get_order", "shop_list_invoices");
    }

    @Test
    void anotherTenantSeesOnlyItsOwnTools() {
        assertThat(names(agent("globex", "cust-9").listTools().tools()))
                .contains("crm_get_policy").doesNotContain("shop_get_order", "shop_list_invoices");
    }

    @Test
    void everyToolDescribesItselfWithAnnotations() {
        Map<String, Tool> tools = byName(agent("acme", "cust-77").listTools().tools());

        assertThat(tools.get("parity_list_cases").annotations().readOnlyHint()).isTrue();
        assertThat(tools.get("parity_list_cases").annotations().openWorldHint()).isFalse();
        assertThat(tools.get("parity_open_case").annotations().readOnlyHint()).isFalse();
        assertThat(tools.get("parity_open_case").annotations().destructiveHint()).isFalse();
        assertThat(tools.get("parity_update_case").annotations().destructiveHint()).isTrue();
        assertThat(tools.get("parity_update_case").annotations().idempotentHint()).isTrue();
        assertThat(tools.get("shop_get_order").annotations().readOnlyHint()).isTrue();
        assertThat(tools.get("shop_get_order").annotations().openWorldHint()).isTrue();
        assertThat(tools.values()).allMatch(t -> t.description() != null && t.description().length() > 50);
    }

    @Test
    @SuppressWarnings("unchecked")
    void argumentsAreSnakeCaseWithLowercaseChoicesAndNoCustomer() {
        Map<String, Tool> tools = byName(agent("acme", "cust-77").listTools().tools());

        Map<String, Object> context = properties(tools.get("parity_get_customer_context"));
        assertThat(context).containsOnlyKeys("channel", "since", "limit", "offset", "response_format");
        assertThat(json.valueToTree(context.get("channel")).path("enum").toString()).contains("web_chat", "whatsapp");
        assertThat(json.valueToTree(context.get("response_format")).path("enum").toString()).contains("markdown", "json");
        assertThat(properties(tools.get("parity_update_case"))).containsKeys("case_id", "add_item_ids");
        assertThat((List<Object>) tools.get("parity_update_case").inputSchema().get("required")).containsExactly("case_id");
        assertThat(properties(tools.get("shop_get_order"))).containsKeys("order_number", "response_format");
        tools.values().forEach(t -> assertThat(properties(t)).doesNotContainKeys("customer_key", "customer"));
    }

    // --- the case tools -----------------------------------------------------------------

    @Test
    void theContextIsTheSessionCustomersReadableAndWithoutCardNumbers() {
        CallToolResult result = call(agent("acme", "cust-77"), "parity_get_customer_context", Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(text(result))
                .contains("# Context of Ana Pop (cust-77)", "Items 1–3 of 3", "2026-10-21 14:03 UTC · voice · customer said",
                        "(item IT-301, case C-12)", "signal escalation-risk, high", "note by agent", "[card ending 1111]")
                .doesNotContain(FakeCustomerContext.CARD, "4111111111111111");
        JsonNode request = owner.requests("get_context").getLast();
        assertThat(request.path("tenant_id").asString()).isEqualTo("acme");
        assertThat(request.path("customer_key").asString()).isEqualTo("cust-77");
        assertThat(request.path("person_id").asString()).isEqualTo("agent-1");
        assertThat(request.path("profile").asString()).isEqualTo("agent");
        assertThat(request.path("limit").asInt()).isEqualTo(20);
        assertThat(request.path("reply_to").asString()).isEqualTo("context.mcp.replies");
    }

    @Test
    void jsonHasEveryFieldAndThePagingFields() {
        CallToolResult result = call(agent("acme", "cust-77"), "parity_get_customer_context",
                Map.of("response_format", "json", "limit", 2, "channel", "voice", "since", "2026-10-01"));

        JsonNode body = json.readTree(text(result));
        assertThat(body.path("total").asInt()).isEqualTo(3);
        assertThat(body.path("has_more").asBoolean()).isTrue();
        assertThat(body.path("count").asInt()).isEqualTo(2);
        assertThat(body.path("next_offset").asInt()).isEqualTo(2);
        assertThat(body.path("items").get(0).path("item_id").asString()).isEqualTo("IT-301");
        JsonNode request = owner.requests("get_context").getLast();
        assertThat(request.path("channel").asString()).isEqualTo("voice");
        assertThat(request.path("since").asString()).isEqualTo("2026-10-01T00:00:00Z");
    }

    @Test
    void aBadArgumentSaysWhatToDoInstead() {
        CallToolResult result = call(agent("acme", "cust-77"), "parity_get_customer_context", Map.of("limit", 80));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("limit must be between 1 and 50; it was 80", "limit=20");
    }

    @Test
    void aSessionWithoutACustomerHasNothingToWorkOn() {
        int before = owner.requests.size();

        CallToolResult result = call(client("acme", "agent", "agent-1", null), "parity_list_cases", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("This session has no customer");
        assertThat(owner.requests).hasSize(before);
    }

    @Test
    void casesAreListedReadably() {
        CallToolResult result = call(agent("acme", "cust-77"), "parity_list_cases", Map.of());

        assertThat(text(result)).contains("## C-12: Refund for order 48213 hasn't arrived", "State: open · risk high · 2 items");
        assertThat(owner.requests("list_cases").getLast().path("include_solved").asBoolean()).isFalse();
    }

    @Test
    void openingACaseRemovesCardNumbersBeforeTheyAreStored() {
        CallToolResult result = call(agent("acme", "cust-77"), "parity_open_case", Map.of(
                "problem", "Refund for order 48213 hasn't arrived",
                "item_ids", List.of("IT-301", "IT-302"),
                "summary", "Customer read their card " + FakeCustomerContext.CARD + " aloud.",
                "risk", "high"));

        assertThat(text(result)).contains("Opened the case.", "# C-13");
        JsonNode request = owner.requests("open_case").getLast();
        assertThat(request.path("summary").asString()).contains("[card ending 1111]").doesNotContain("4111");
        assertThat(request.path("item_ids").toString()).isEqualTo("[\"IT-301\",\"IT-302\"]");
        assertThat(request.path("risk").asString()).isEqualTo("high");
    }

    @Test
    void anUnknownCaseSaysWhereCaseIdsComeFrom() {
        CallToolResult result = call(agent("acme", "cust-77"), "parity_update_case",
                Map.of("case_id", "C-404", "state", "solved"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("Case C-404 isn't one of this customer's cases", "parity_list_cases");
    }

    @Test
    void anUpdateNeedsSomethingToChange() {
        CallToolResult result = call(agent("acme", "cust-77"), "parity_update_case", Map.of("case_id", "C-12"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("Nothing to change", "state=waiting_customer");
    }

    @Test
    void anUpdateChangesOnlyWhatIsPassed() {
        CallToolResult result = call(agent("acme", "cust-77"), "parity_update_case",
                Map.of("case_id", "C-12", "state", "waiting_customer"));

        assertThat(text(result)).contains("Updated the case.", "State: waiting_customer");
        JsonNode request = owner.requests("update_case").getLast();
        assertThat(request.path("state").asString()).isEqualTo("waiting_customer");
        assertThat(request.has("summary")).isFalse();
    }

    @Test
    void askingAPersonSaysWhereTheAnswerWillBe() {
        CallToolResult result = call(agent("acme", "cust-77"), "parity_ask_person", Map.of(
                "case_id", "C-12", "ask", "supervisor", "question", "May I offer a 10 EUR goodwill credit?"));

        assertThat(text(result)).contains("Asked the supervisor about case C-12 (question Q-5", "parity_list_cases");
    }

    @Test
    void anOwnerThatDoesntAnswerIsAnErrorTheAgentCanActOn() {
        CallToolResult result = call(agent("acme", "cust-slow"), "parity_list_cases", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("customer-context didn't answer", "Try once more");
    }

    // --- tenant tools -------------------------------------------------------------------

    @Test
    void aTenantToolReturnsOnlyItsListedFieldsWithoutCardNumbers() {
        CallToolResult result = call(agent("acme", "cust-77"), "shop_get_order", Map.of("order_number", "48213"));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("# Get an order", "From the tenant's system", "- status: shipped",
                "[card ending 1111]").doesNotContain("internal_note", "VIP", "4111");
        JsonNode request = owner.requests("call_tenant_tool").getLast();
        assertThat(request.path("tool").asString()).isEqualTo("shop_get_order");
        assertThat(request.path("call").path("type").asString()).isEqualTo("https");
        assertThat(request.path("call").path("secret").asString()).isEqualTo("acme-shop-orders");
        assertThat(request.path("customer_reference").asString()).isEqualTo("crm_id");
        assertThat(request.path("customer_key").asString()).isEqualTo("cust-77");
        assertThat(request.path("arguments").toString()).isEqualTo("{\"order_number\":\"48213\"}");
        assertThat(request.path("timeout_ms").asInt()).isEqualTo(2000);
    }

    @Test
    void anotherCustomersResultIsDropped() {
        CallToolResult result = call(agent("acme", "cust-77"), "shop_get_order", Map.of("order_number", "99999"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("isn't the current customer's, so it was dropped").doesNotContain("shipped");
    }

    @Test
    void tenantToolArgumentsAreCheckedAgainstTheTenantsSchema() {
        int before = owner.requests("call_tenant_tool").size();

        CallToolResult result = call(agent("acme", "cust-77"), "shop_get_order", Map.of("order_number", "abc"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("The arguments don't fit shop_get_order", "order_number");
        assertThat(owner.requests("call_tenant_tool")).hasSize(before);
    }

    @Test
    void anotherTenantsToolDoesntExistForThisSession() {
        McpSyncClient acme = agent("acme", "cust-77");

        assertThatThrownBy(() -> call(acme, "crm_get_policy", Map.of("policy_id", "P-1")))
                .hasMessageContaining("Unknown tool");
    }

    // --- the transport ------------------------------------------------------------------

    @Test
    void aRequestWithoutAScopeIsRefused() throws Exception {
        HttpResponse<String> response = post(Map.of());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("X-Tenant-ID is missing");
    }

    @Test
    void aBrowserFromAnotherOriginIsRefused() throws Exception {
        HttpResponse<String> response = post(Map.of("X-Tenant-ID", "acme", "X-Profile", "agent", "X-Person-ID", "a-1",
                "Origin", "https://evil.example"));

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void theCustomerProfileIsntOnYet() throws Exception {
        HttpResponse<String> response = post(Map.of("X-Tenant-ID", "acme", "X-Profile", "customer", "X-Person-ID", "c-1"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("X-Profile must be agent or supervisor");
    }

    // --- audit and lifecycle ------------------------------------------------------------

    @Test
    void everyCallIsAuditedWithoutItsContent() {
        call(agent("acme", "cust-77"), "parity_open_case", Map.of("problem", "Secret problem text"));

        await().atMost(Duration.ofSeconds(10)).until(() -> audit.events.stream()
                .anyMatch(e -> "parity_open_case".equals(e.path("tool").asString())));
        JsonNode event = audit.events.stream().filter(e -> "parity_open_case".equals(e.path("tool").asString())).findFirst().orElseThrow();
        assertThat(event.path("event").asString()).isEqualTo("tool.called");
        assertThat(event.path("outcome").asString()).isEqualTo("ok");
        assertThat(event.path("tenant_id").asString()).isEqualTo("acme");
        assertThat(event.path("person_id").asString()).isEqualTo("agent-1");
        assertThat(event.path("customer_key").asString()).isEqualTo("cust-77");
        assertThat(event.path("instance").asString()).isEqualTo("test-1");
        assertThat(event.toString()).doesNotContain("Secret problem text");
    }

    @Test
    void theHealthyFileExistsOnceStarted() {
        await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(Path.of("target/parity-cc-mcp.healthy")));
    }

    // --- helpers ------------------------------------------------------------------------

    private McpSyncClient agent(String tenant, String customer) {
        return client(tenant, "agent", "agent-1", customer);
    }

    private McpSyncClient client(String tenant, String profile, String person, String customer) {
        HttpRequest.Builder headers = HttpRequest.newBuilder()
                .header("X-Tenant-ID", tenant).header("X-Profile", profile).header("X-Person-ID", person)
                .header("X-Interaction-ID", "int-1");
        if (customer != null) {
            headers.header("X-Customer-Key", customer);
        }
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp")
                .requestBuilder(headers)
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
        client.initialize();
        clients.add(client);
        return client;
    }

    private static CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(new CallToolRequest(tool, arguments));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Tool tool) {
        return (Map<String, Object>) tool.inputSchema().get("properties");
    }

    private static List<String> names(List<Tool> tools) {
        return tools.stream().map(Tool::name).toList();
    }

    private static Map<String, Tool> byName(List<Tool> tools) {
        return tools.stream().collect(java.util.stream.Collectors.toMap(Tool::name, t -> t));
    }

    private HttpResponse<String> post(Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"));
        headers.forEach(request::header);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
