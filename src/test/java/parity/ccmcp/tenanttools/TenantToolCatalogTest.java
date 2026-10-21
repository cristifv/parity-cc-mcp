package parity.ccmcp.tenanttools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import parity.ccmcp.scope.Profile;

class TenantToolCatalogTest {

    @TempDir
    Path dir;

    private final DefaultJsonSchemaValidator validator = new DefaultJsonSchemaValidator();

    @Test
    @SuppressWarnings("unchecked")
    void theTestFileLoads() {
        Map<String, Map<String, TenantTool>> tools = TenantToolCatalog.load(Path.of("src/test/resources/tenant-tools.yml"), validator);

        assertThat(tools).containsOnlyKeys("acme", "globex");
        TenantTool order = tools.get("acme").get("shop_get_order");
        assertThat(order.profiles()).containsExactlyInAnyOrder(Profile.AGENT, Profile.SUPERVISOR);
        assertThat(order.timeout().toMillis()).isEqualTo(2000);
        assertThat(order.call()).containsEntry("type", "https").containsEntry("secret", "acme-shop-orders");
        assertThat((Map<String, Object>) order.tool().inputSchema().get("properties")).containsKeys("order_number", "response_format");
        assertThat(order.inputSchema()).doesNotContainKey("response_format");
        assertThat(tools.get("acme").get("shop_list_invoices").timeout()).isEqualTo(TenantToolCatalog.DEFAULT_TIMEOUT);
    }

    @Test
    void everyProblemIsListed() throws IOException {
        Path file = write("""
                tenants:
                  acme:
                    tools:
                      - name: parity_get_order
                        description: Gets an order.
                        input_schema: {type: object, properties: {response_format: {type: string}}}
                        call: {https: {url: "http://api.acme.example/orders", secret: acme}}
                        customer_reference: passport
                        result: {customer_field: customer_id, fields: [status]}
                        profiles: [agent, customer]
                        timeout: 1h
                      - name: Get-Order
                        input_schema: {type: string}
                        call: {crm: {connector: x, operation: y}, https: {url: "https://a.example", secret: s}}
                        result: {fields: []}
                """);

        assertThatThrownBy(() -> TenantToolCatalog.load(file, validator))
                .isInstanceOf(InvalidTenantToolsException.class)
                .satisfies(e -> assertThat(((InvalidTenantToolsException) e).problems()).anySatisfy(p -> assertThat(p).contains("for the built-in tools"))
                        .anySatisfy(p -> assertThat(p).contains("can't have a response_format property"))
                        .anySatisfy(p -> assertThat(p).contains("must be an https:// URL"))
                        .anySatisfy(p -> assertThat(p).contains("customer_reference must be one of"))
                        .anySatisfy(p -> assertThat(p).contains("the customer profile isn't on yet"))
                        .anySatisfy(p -> assertThat(p).contains("timeout must be between"))
                        .anySatisfy(p -> assertThat(p).contains("tools[1]").contains("name must be snake_case"))
                        .anySatisfy(p -> assertThat(p).contains("description is required"))
                        .anySatisfy(p -> assertThat(p).contains("must have type: object"))
                        .anySatisfy(p -> assertThat(p).contains("exactly one of https or crm"))
                        .anySatisfy(p -> assertThat(p).contains("result.customer_field is required"))
                        .anySatisfy(p -> assertThat(p).contains("profiles is required")));
    }

    @Test
    void aNameUsedTwiceIsAProblem() throws IOException {
        String tool = """
                      - name: shop_get_order
                        description: Gets one of the customer's orders.
                        input_schema: {type: object, properties: {}}
                        call: {crm: {connector: c, operation: o}}
                        customer_reference: crm_id
                        result: {customer_field: customer_id, fields: [status]}
                        profiles: [agent]
                """;
        Path file = write("tenants:\n  acme:\n    tools:\n" + tool + tool);

        assertThatThrownBy(() -> TenantToolCatalog.load(file, validator)).hasMessageContaining("the name is used twice");
    }

    @Test
    void anUnknownKeyIsAProblem() throws IOException {
        Path file = write("""
                tenants:
                  acme:
                    tools:
                      - name: shop_get_order
                        description: Gets one of the customer's orders.
                        input_schema: {type: object}
                        call: {crm: {connector: c, operation: o}}
                        customer_reference: crm_id
                        result: {customer_field: customer_id, fields: [status]}
                        profiles: [agent]
                        writes: true
                """);

        assertThatThrownBy(() -> TenantToolCatalog.load(file, validator))
                .isInstanceOf(InvalidTenantToolsException.class).hasMessageContaining("writes");
    }

    private Path write(String yaml) throws IOException {
        Path file = dir.resolve("tenant-tools.yml");
        Files.writeString(file, yaml);
        return file;
    }
}
