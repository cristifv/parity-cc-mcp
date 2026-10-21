package parity.ccmcp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code parity-cc-mcp.*}.
 *
 * @param topics               the Kafka topics it sends to and reads from
 * @param ownerTimeout         how long a tool waits for the owner's answer before it tells the
 *                             agent the data isn't available
 * @param resultCharacterLimit the most characters one tool result may hold; longer results
 *                             are cut and say how to ask for less
 * @param tenantToolsFile      the YAML file with the tenants' own tools; empty means none
 * @param allowedOrigins       browser origins allowed to call the MCP endpoint. Empty: any
 *                             request with an {@code Origin} header is refused
 * @param lifecycle            the healthy and drained files and the drain timeout
 */
@Validated
@ConfigurationProperties("parity-cc-mcp")
public record CcMcpProperties(
        @NotNull @Valid Topics topics,
        @NotNull Duration ownerTimeout,
        @Min(1000) int resultCharacterLimit,
        String tenantToolsFile,
        List<String> allowedOrigins,
        @NotNull @Valid Lifecycle lifecycle) {

    public CcMcpProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }

    /**
     * @param customerContextRequests where customer-context takes its requests
     * @param replies                 where the owners answer; every instance reads all of it
     * @param audit                   one event per tool call, never its content
     */
    public record Topics(
            @NotBlank String customerContextRequests,
            @NotBlank String replies,
            @NotBlank String audit) {
    }

    /**
     * Startup and graceful shutdown. The two files are empty markers for the orchestrator
     * (Kubernetes or Docker Swarm). {@code instanceId} names this instance in request ids
     * and audit events; empty means host name and process id.
     */
    public record Lifecycle(
            String instanceId,
            @NotBlank String healthyFile,
            @NotBlank String drainedFile,
            @NotNull Duration drainTimeout) {
    }
}
