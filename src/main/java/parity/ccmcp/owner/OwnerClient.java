package parity.ccmcp.owner;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;
import parity.ccmcp.config.CcMcpProperties;
import parity.ccmcp.config.Instance;
import parity.ccmcp.scope.Scope;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sends a tool's request to the service that owns the data and waits for the answer, in the
 * platform's request/reply style over Kafka:
 * <ul>
 *   <li>request: {@code cmd}, {@code id}, {@code reply_to}, the scope ({@code tenant_id},
 *       {@code profile}, {@code person_id}, {@code customer_key}, {@code interaction_id})
 *       and the command's fields, keyed by tenant;</li>
 *   <li>answer on {@code reply_to}: {@code {id, ok, result}} or
 *       {@code {id, ok: false, error: {code, message}}}.</li>
 * </ul>
 * Every instance reads the whole replies topic (a consumer group of its own) and keeps the
 * answers to its own requests, whose ids start with its instance id. It reads from the
 * latest offset, so it is {@linkplain #awaitReady ready} once it has its partitions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OwnerClient implements ConsumerSeekAware {

    public static final String CUSTOMER_CONTEXT = "customer-context";

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final CcMcpProperties properties;
    private final Instance instance;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final CountDownLatch assigned = new CountDownLatch(1);

    /** A request to customer-context, answered within the owner timeout. */
    public JsonNode customerContext(Scope scope, String cmd, ObjectNode fields)
            throws OwnerUnavailableException, OwnerErrorException {
        return call(CUSTOMER_CONTEXT, properties.topics().customerContextRequests(), scope, cmd, fields,
                properties.ownerTimeout());
    }

    /** A request to customer-context with a timeout of its own (a tenant tool's). */
    public JsonNode customerContext(Scope scope, String cmd, ObjectNode fields, Duration timeout)
            throws OwnerUnavailableException, OwnerErrorException {
        return call(CUSTOMER_CONTEXT, properties.topics().customerContextRequests(), scope, cmd, fields, timeout);
    }

    /** The command's result, the owner's error, or unavailable after {@code timeout}. */
    JsonNode call(String owner, String topic, Scope scope, String cmd, ObjectNode fields, Duration timeout)
            throws OwnerUnavailableException, OwnerErrorException {
        String id = instance.id() + ":" + UUID.randomUUID();
        ObjectNode request = json.createObjectNode();
        request.put("cmd", cmd);
        request.put("id", id);
        request.put("reply_to", properties.topics().replies());
        request.put("tenant_id", scope.tenantId());
        request.put("profile", scope.profile().wire());
        request.put("person_id", scope.personId());
        scope.customer().ifPresent(c -> request.put("customer_key", c));
        scope.interaction().ifPresent(i -> request.put("interaction_id", i));
        request.setAll(fields);

        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        pending.put(id, answer);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            kafka.send(topic, scope.tenantId(), json.writeValueAsString(request)).get(left(deadline), TimeUnit.NANOSECONDS);
            return result(owner, answer.get(left(deadline), TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            throw new OwnerUnavailableException(owner, owner + " didn't answer " + cmd + " within " + timeout.toMillis() + " ms", e);
        } catch (ExecutionException e) {
            throw new OwnerUnavailableException(owner, "couldn't send " + cmd + " to " + owner + ": " + e.getCause(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OwnerUnavailableException(owner, "interrupted while waiting for " + owner, e);
        } finally {
            pending.remove(id);
        }
    }

    @KafkaListener(id = "owner-replies", topics = "${parity-cc-mcp.topics.replies}",
            groupId = "#{@instance.replyGroupId()}",
            properties = "auto.offset.reset=latest")
    void onReply(String value) {
        JsonNode reply;
        try {
            reply = json.readTree(value);
        } catch (JacksonException e) {
            log.warn("ignored a reply that isn't JSON: {}", e.getOriginalMessage());
            return;
        }
        String id = reply.path("id").asString("");
        CompletableFuture<JsonNode> answer = pending.get(id);
        if (answer != null) {
            answer.complete(reply);
        }
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        if (!assignments.isEmpty()) {
            assigned.countDown();
        }
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
    }

    /** True once the replies listener has its partitions, so no answer is missed. */
    public boolean awaitReady(Duration timeout) throws InterruptedException {
        return assigned.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Requests waiting for their answer. */
    public int pending() {
        return pending.size();
    }

    private static JsonNode result(String owner, JsonNode reply) throws OwnerErrorException {
        if (reply.path("ok").asBoolean(false)) {
            return reply.path("result");
        }
        JsonNode error = reply.path("error");
        throw new OwnerErrorException(owner, error.path("code").asString("internal"), error.path("message").asString(""));
    }

    private static long left(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }
}
