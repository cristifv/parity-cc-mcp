package parity.ccmcp;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * customer-context as the owner contract (README.md) describes it, with canned data. It
 * keeps every request it got. Customer {@code cust-slow} never gets an answer.
 */
@TestComponent
public class FakeCustomerContext {

    static final String CARD = "4111 1111 1111 1111";

    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    public FakeCustomerContext(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    @KafkaListener(topics = "customer.context.requests", groupId = "fake-customer-context")
    void onRequest(String value) {
        JsonNode request = json.readTree(value);
        requests.add(request);
        if ("cust-slow".equals(request.path("customer_key").asString())) {
            return;
        }
        ObjectNode reply = json.createObjectNode();
        reply.put("id", request.path("id").asString());
        try {
            reply.set("result", answer(request));
            reply.put("ok", true);
        } catch (OwnerError e) {
            reply.put("ok", false);
            reply.putObject("error").put("code", e.code).put("message", e.getMessage());
        }
        kafka.send(request.path("reply_to").asString(), request.path("tenant_id").asString(), json.writeValueAsString(reply));
    }

    List<JsonNode> requests(String cmd) {
        return requests.stream().filter(r -> cmd.equals(r.path("cmd").asString())).toList();
    }

    private JsonNode answer(JsonNode request) throws OwnerError {
        return switch (request.path("cmd").asString()) {
            case "get_context" -> context(request.path("limit").asInt(20), request.path("offset").asInt(0));
            case "list_cases" -> {
                ObjectNode result = json.createObjectNode();
                result.put("total", 1);
                result.putArray("cases").add(aCase("C-12"));
                yield result;
            }
            case "open_case" -> {
                ObjectNode result = json.createObjectNode();
                ObjectNode c = aCase("C-13");
                c.put("problem", request.path("problem").asString());
                result.set("case", c);
                yield result;
            }
            case "update_case" -> {
                if (!"C-12".equals(request.path("case_id").asString())) {
                    throw new OwnerError("not_found", "no case " + request.path("case_id").asString());
                }
                ObjectNode result = json.createObjectNode();
                ObjectNode c = aCase("C-12");
                if (request.has("state")) {
                    c.put("state", request.path("state").asString());
                }
                result.set("case", c);
                yield result;
            }
            case "ask_person" -> {
                ObjectNode result = json.createObjectNode();
                result.put("question_id", "Q-5");
                result.put("case_id", request.path("case_id").asString());
                result.put("ask", request.path("ask").asString());
                result.put("asked_at", "2026-10-21T14:06:00Z");
                yield result;
            }
            case "call_tenant_tool" -> tenantTool(request);
            default -> throw new OwnerError("bad_request", "unknown command");
        };
    }

    /** Three items, of which the page asked for. */
    private ObjectNode context(int limit, int offset) {
        ObjectNode result = json.createObjectNode();
        result.putObject("customer").put("customer_key", "cust-77").put("name", "Ana Pop");
        result.put("total", 3);
        ArrayNode items = result.putArray("items");
        items.addObject().put("item_id", "IT-301").put("at", "2026-10-21T14:03:00Z").put("channel", "voice")
                .put("kind", "said").put("who", "customer").put("case_id", "C-12")
                .put("text", "My refund hasn't arrived, my card is " + CARD + ".");
        items.addObject().put("item_id", "IT-302").put("at", "2026-10-21T14:04:00Z").put("channel", "voice")
                .put("kind", "signal").put("who", "signal-detection")
                .putObject("signal").put("type", "escalation-risk").put("severity", "high");
        items.addObject().put("item_id", "IT-288").put("at", "2026-10-20T09:12:00Z").put("channel", "email")
                .put("kind", "note").put("who", "agent").put("text", "Promised a callback.");
        ArrayNode page = json.createArrayNode();
        for (int i = offset; i < Math.min(items.size(), offset + limit); i++) {
            page.add(items.get(i));
        }
        result.set("items", page);
        return result;
    }

    private ObjectNode aCase(String id) {
        ObjectNode c = json.createObjectNode();
        c.put("case_id", id).put("problem", "Refund for order 48213 hasn't arrived").put("state", "open")
                .put("risk", "high").put("summary", "Second contact about the refund.").put("item_count", 2)
                .put("opened_at", "2026-10-20T09:12:00Z").put("updated_at", "2026-10-21T14:05:00Z");
        return c;
    }

    /** Order 99999 belongs to another customer. */
    private ObjectNode tenantTool(JsonNode request) {
        ObjectNode result = json.createObjectNode();
        result.put("customer_reference", "CRM-889");
        String order = request.path("arguments").path("order_number").asString();
        result.putObject("data")
                .put("customer_id", "99999".equals(order) ? "CRM-123" : "CRM-889")
                .put("order_number", order)
                .put("status", "shipped")
                .put("note_to_customer", "Refund to card " + CARD + " is on its way.")
                .put("internal_note", "VIP, don't charge shipping");
        return result;
    }

    static class OwnerError extends Exception {
        final String code;

        OwnerError(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
