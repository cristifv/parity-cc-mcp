package parity.ccmcp.tenanttools;

import java.util.List;
import java.util.Map;

/**
 * The tenant tools file, as written: YAML in snake_case. Operators review it and deploy it
 * with the service; it doesn't go through admin-api, for security (the user, 2026-10-21).
 * {@link TenantToolCatalog} checks it. Example:
 *
 * <pre>
 * tenants:
 *   acme-shop:
 *     tools:
 *       - name: shop_get_order
 *         title: Get an order
 *         description: >
 *           Get one of the current customer's orders from Acme's shop: status, items,
 *           shipping date and expected delivery. Use the order number the customer gives.
 *         input_schema:
 *           type: object
 *           properties:
 *             order_number: {type: string, pattern: "^[0-9]{5,10}$", description: The order number, digits only.}
 *           required: [order_number]
 *           additionalProperties: false
 *         call:
 *           https: {url: "https://api.acme.example/cc/orders", secret: acme-shop-orders}
 *         customer_reference: crm_id
 *         result:
 *           customer_field: customer_id
 *           fields: [order_number, status, items, shipped_at, expected_at]
 *         profiles: [agent, supervisor]
 *         timeout: 3s
 * </pre>
 */
public record TenantToolsFile(Map<String, Tenant> tenants) {

    public record Tenant(List<Tool> tools) {
    }

    public record Tool(
            String name,
            String title,
            String description,
            Map<String, Object> inputSchema,
            Call call,
            String customerReference,
            Result result,
            List<String> profiles,
            String timeout) {
    }

    /** Exactly one of the two. */
    public record Call(Https https, Crm crm) {
    }

    /** An HTTPS endpoint of the tenant's system; {@code secret} names its credentials. */
    public record Https(String url, String secret) {
    }

    /** An operation of one of the tenant's CRM connectors in customer-context. */
    public record Crm(String connector, String operation) {
    }

    /**
     * @param customerField the field of each result that holds the customer reference
     * @param fields        the fields the agent gets; every other field is dropped
     */
    public record Result(String customerField, List<String> fields) {
    }
}
