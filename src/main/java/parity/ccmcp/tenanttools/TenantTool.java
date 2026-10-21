package parity.ccmcp.tenanttools;

import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import parity.ccmcp.scope.Profile;

/**
 * One tenant tool, checked. It's read-only (the user, 2026-10-21): it reads the tenant's own
 * system for the session's customer.
 *
 * @param inputSchema       the tenant's JSON Schema for the arguments, which they are
 *                          checked against
 * @param call              what customer-context calls: {@code {type: https, url, secret}}
 *                          or {@code {type: crm, connector, operation}}
 * @param customerReference which of the customer's identifiers the tenant's system uses
 *                          ({@code crm_id}, {@code account_number}, {@code phone},
 *                          {@code email}, {@code customer_key}); customer-context adds it
 * @param customerField     the result field that must hold that reference
 * @param fields            the result fields the agent gets
 * @param tool              the tool as {@code tools/list} shows it
 */
public record TenantTool(
        String tenantId,
        String name,
        Map<String, Object> inputSchema,
        Map<String, Object> call,
        String customerReference,
        String customerField,
        List<String> fields,
        Set<Profile> profiles,
        Duration timeout,
        McpSchema.Tool tool) {

    /** The argument every tenant tool gets on top of the tenant's own. */
    public static final String RESPONSE_FORMAT = "response_format";

    public String title() {
        return tool.title() != null ? tool.title() : name;
    }
}
