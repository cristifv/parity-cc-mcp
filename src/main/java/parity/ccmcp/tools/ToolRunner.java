package parity.ccmcp.tools;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import parity.ccmcp.audit.AuditPublisher;
import parity.ccmcp.config.CcMcpProperties;
import parity.ccmcp.mask.SensitiveData;
import parity.ccmcp.owner.OwnerErrorException;
import parity.ccmcp.owner.OwnerUnavailableException;
import parity.ccmcp.scope.Scope;
import parity.ccmcp.scope.ScopeHeaders;

/**
 * Runs every tool call: finds the scope, turns every failure into an error result the agent
 * can act on, and writes the audit event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRunner {

    static final String NO_SCOPE = "This session has no tenant, profile or person, so no tool can run. "
            + "The application that opened the session has to send them.";
    static final String NO_CUSTOMER = "This session has no customer, so there is no context or case to work on. "
            + "The tools act on the customer of the interaction the session was opened for; ask the person to open "
            + "that interaction first.";
    static final String INTERNAL = "parity-cc-mcp failed on this call. Try once more; if it fails again, continue "
            + "without this tool and tell the person.";

    private final AuditPublisher audit;
    private final CcMcpProperties properties;

    @FunctionalInterface
    public interface Body {
        CallToolResult run(Scope scope) throws OwnerUnavailableException, OwnerErrorException;
    }

    public CallToolResult run(McpTransportContext context, String tool, Body body) {
        long started = System.nanoTime();
        Scope scope = ScopeHeaders.from(context).orElse(null);
        String outcome = AuditPublisher.OK;
        try {
            if (scope == null) {
                outcome = "no_scope";
                return ToolResults.error(NO_SCOPE);
            }
            return body.run(scope);
        } catch (ToolError e) {
            outcome = e.code();
            return ToolResults.error(e.getMessage());
        } catch (OwnerUnavailableException e) {
            outcome = "owner_unavailable";
            log.warn("{}: {}", tool, e.getMessage());
            return ToolResults.error(e.owner() + " didn't answer within " + properties.ownerTimeout().toSeconds()
                    + " s, so this data isn't available right now. Try once more; if it fails again, continue "
                    + "without it and tell the person it couldn't be loaded.");
        } catch (OwnerErrorException e) {
            outcome = "owner_" + e.code();
            return ToolResults.error(ownerError(e));
        } catch (RuntimeException e) {
            outcome = "internal";
            log.error("{} failed", tool, e);
            return ToolResults.error(INTERNAL);
        } finally {
            audit.toolCalled(scope, tool, outcome, (System.nanoTime() - started) / 1_000_000);
        }
    }

    /** The scope's customer, or an error that says the session has none. */
    public static String requireCustomer(Scope scope) {
        return scope.customer().orElseThrow(() -> new ToolError("no_customer", NO_CUSTOMER));
    }

    /**
     * The owner's own message for errors the agent can act on; it's written for an agent (the
     * owner contract). Internal errors say nothing about internals.
     */
    private static String ownerError(OwnerErrorException e) {
        return switch (e.code()) {
            case "not_found", "bad_request", "conflict", "forbidden" -> {
                String message = SensitiveData.mask(e.getMessage());
                yield e.owner() + " refused the request (" + e.code() + "): "
                        + (message.length() > 500 ? message.substring(0, 500) + "…" : message);
            }
            default -> e.owner() + " failed on this request. Try once more; if it fails again, continue without "
                    + "it and tell the person.";
        };
    }
}
