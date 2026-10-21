package parity.ccmcp.tenanttools;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import parity.ccmcp.mask.SensitiveData;
import parity.ccmcp.owner.OwnerClient;
import parity.ccmcp.scope.Scope;
import parity.ccmcp.tools.ToolError;
import parity.ccmcp.tools.ToolResults;
import parity.ccmcp.tools.ToolRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs every tenant tool the same way:
 * <ol>
 *   <li>checks the arguments against the tenant's input schema;</li>
 *   <li>sends {@code call_tenant_tool} to customer-context, which adds the customer's
 *       reference and calls the tenant's system through its connectors and webhook-proxy;</li>
 *   <li>drops every result whose customer reference isn't the session customer's;</li>
 *   <li>keeps only the listed fields, and removes card numbers and IBANs.</li>
 * </ol>
 * The model never passes the customer: it comes from the session.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantToolExecutor {

    /** customer-context gets the tool's timeout for the tenant's system, and this much more to answer. */
    static final Duration OWNER_MARGIN = Duration.ofSeconds(1);

    private final OwnerClient owner;
    private final ToolRunner runner;
    private final ToolResults results;
    private final JsonSchemaValidator validator;
    private final JsonMapper json;

    public CallToolResult call(McpTransportContext context, TenantTool tool, Map<String, Object> arguments) {
        return runner.run(context, tool.name(), scope -> {
            ToolRunner.requireCustomer(scope);
            Map<String, Object> args = arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments);
            boolean asJson = "json".equals(responseFormat(args.remove(TenantTool.RESPONSE_FORMAT)));
            JsonSchemaValidator.ValidationResponse valid = validator.validate(tool.inputSchema(), args);
            if (!valid.valid()) {
                throw ToolError.invalid("The arguments don't fit " + tool.name() + ": " + valid.errorMessage()
                        + ". Its input schema is " + json.writeValueAsString(tool.inputSchema()) + ".");
            }
            ObjectNode fields = json.createObjectNode();
            fields.put("tool", tool.name());
            fields.set("call", json.valueToTree(tool.call()));
            fields.put("customer_reference", tool.customerReference());
            fields.set("arguments", json.valueToTree(args));
            fields.put("timeout_ms", tool.timeout().toMillis());
            JsonNode answer = owner.customerContext(scope, "call_tenant_tool", fields, tool.timeout().plus(OWNER_MARGIN));
            JsonNode data = SensitiveData.mask(keep(scope, tool, answer));
            String howToAskForLess = "Ask the tenant's system for less: narrower arguments, if " + tool.name() + " has them.";
            if (asJson) {
                ObjectNode out = json.createObjectNode();
                out.put("source", "the tenant's system");
                out.put("read_at", Instant.now().toString());
                out.set("data", data);
                return results.json(out, howToAskForLess);
            }
            return results.text(markdown(tool, data), howToAskForLess);
        });
    }

    /**
     * The answer's data without other customers' results or unlisted fields. {@code data} is
     * one object or a list of them; each must hold the customer reference customer-context
     * resolved for the session's customer.
     */
    private JsonNode keep(Scope scope, TenantTool tool, JsonNode answer) {
        String reference = answer.path("customer_reference").asString("");
        JsonNode data = answer.path("data");
        if (reference.isEmpty() || !(data.isObject() || data.isArray())) {
            log.warn("{} for tenant {}: customer-context's answer has no customer_reference or data", tool.name(), scope.tenantId());
            throw new ToolError("bad_owner_answer", tool.name() + " couldn't read the tenant's system right now. "
                    + "Continue without it and tell the person.");
        }
        if (data.isObject()) {
            if (!matches(data, tool, reference)) {
                throw dropped(scope, tool);
            }
            return listed(data, tool);
        }
        ArrayNode kept = json.createArrayNode();
        int dropped = 0;
        for (JsonNode element : data) {
            if (element.isObject() && matches(element, tool, reference)) {
                kept.add(listed(element, tool));
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            log.warn("{} for tenant {}: dropped {} results that weren't the session customer's", tool.name(), scope.tenantId(), dropped);
            if (kept.isEmpty()) {
                throw dropped(scope, tool);
            }
        }
        return kept;
    }

    private static boolean matches(JsonNode element, TenantTool tool, String reference) {
        JsonNode value = element.path(tool.customerField());
        return value.isValueNode() && reference.equals(value.asString());
    }

    private ObjectNode listed(JsonNode element, TenantTool tool) {
        ObjectNode out = json.createObjectNode();
        for (String field : tool.fields()) {
            if (element.has(field)) {
                out.set(field, element.get(field));
            }
        }
        return out;
    }

    private static ToolError dropped(Scope scope, TenantTool tool) {
        return new ToolError("result_dropped", tool.name() + " returned data that isn't the current customer's, so "
                + "it was dropped. Don't try other arguments to reach another customer's data; tell the person.");
    }

    private static String responseFormat(Object value) {
        if (value == null || "markdown".equals(value) || "json".equals(value)) {
            return value == null ? "markdown" : (String) value;
        }
        throw ToolError.invalid(TenantTool.RESPONSE_FORMAT + " must be markdown or json; it was " + value + ".");
    }

    private String markdown(TenantTool tool, JsonNode data) {
        StringBuilder md = new StringBuilder("# ").append(tool.title()).append("\n\nFrom the tenant's system, read ")
                .append(Instant.now().toString(), 0, 16).append(" UTC.\n\n");
        if (data.isArray()) {
            if (data.isEmpty()) {
                return md.append("Nothing found for the current customer.\n").toString();
            }
            int n = 1;
            for (JsonNode element : data) {
                md.append("## ").append(n++).append("\n");
                fields(md, element);
                md.append("\n");
            }
            return md.toString();
        }
        fields(md, data);
        return md.toString();
    }

    private void fields(StringBuilder md, JsonNode element) {
        for (Map.Entry<String, JsonNode> field : element.properties()) {
            JsonNode value = field.getValue();
            md.append("- ").append(field.getKey()).append(": ")
                    .append(value.isValueNode() ? value.asString() : json.writeValueAsString(value)).append("\n");
        }
    }
}
