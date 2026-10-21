package parity.ccmcp.audit;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import parity.ccmcp.config.CcMcpProperties;
import parity.ccmcp.config.Instance;
import parity.ccmcp.scope.Scope;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One {@code tool.called} event per tool call on the audit topic, keyed by tenant: who called
 * which tool on whose behalf, the outcome and the latency. Never the arguments or the result
 * (ARCHITECTURE.md §3.23, retention as in §5.2).
 *
 * <p>Publishing doesn't wait: a tool call never fails because the audit event couldn't be
 * sent. A failed send is logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditPublisher {

    /** The outcome of a call that worked. Other outcomes are error codes ({@code ToolError}). */
    public static final String OK = "ok";

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final CcMcpProperties properties;
    private final Instance instance;

    public void toolCalled(Scope scope, String tool, String outcome, long latencyMillis) {
        ObjectNode event = json.createObjectNode();
        event.put("event", "tool.called");
        event.put("at", Instant.now().toString());
        event.put("instance", instance.id());
        event.put("tool", tool);
        event.put("outcome", outcome);
        event.put("latency_ms", latencyMillis);
        String key = null;
        if (scope != null) {
            key = scope.tenantId();
            event.put("tenant_id", scope.tenantId());
            event.put("profile", scope.profile().wire());
            event.put("person_id", scope.personId());
            scope.customer().ifPresent(c -> event.put("customer_key", c));
            scope.interaction().ifPresent(i -> event.put("interaction_id", i));
        }
        kafka.send(properties.topics().audit(), key, json.writeValueAsString(event))
                .whenComplete((sent, error) -> {
                    if (error != null) {
                        log.error("audit event for {} not sent: {}", tool, error.toString());
                    }
                });
    }
}
