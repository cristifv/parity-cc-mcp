package parity.ccmcp.tenanttools;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import parity.ccmcp.config.CcMcpProperties;
import parity.ccmcp.scope.Profile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The tenants' own tools, read once at startup from the tenant tools file. The whole file is
 * checked first, and an invalid file stops the service with every problem listed. A changed
 * file takes effect on a rolling restart.
 */
@Slf4j
@Component
public class TenantToolCatalog {

    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9._:@-]{1,128}");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{2,63}");
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9._/-]{1,128}");
    /** Built-in tools start with it, so a tenant tool can never shadow one. */
    static final String RESERVED_PREFIX = "parity_";
    static final Set<String> CUSTOMER_REFERENCES = Set.of("crm_id", "account_number", "phone", "email", "customer_key");
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration MAX_TIMEOUT = Duration.ofSeconds(10);

    private final Map<String, Map<String, TenantTool>> byTenant;

    public TenantToolCatalog(CcMcpProperties properties, JsonSchemaValidator validator) {
        String file = properties.tenantToolsFile();
        if (!StringUtils.hasText(file)) {
            log.info("no tenant tools file; only the built-in tools are served");
            this.byTenant = Map.of();
            return;
        }
        this.byTenant = load(Path.of(file), validator);
        log.info("tenant tools from {}: {} tools for {} tenants", file,
                byTenant.values().stream().mapToInt(Map::size).sum(), byTenant.size());
    }

    /** The tenant's tools a profile may use, in the file's order. */
    public List<TenantTool> forScope(String tenantId, Profile profile) {
        return byTenant.getOrDefault(tenantId, Map.of()).values().stream()
                .filter(t -> t.profiles().contains(profile)).toList();
    }

    /** One of the tenant's tools, for a profile that may use it. */
    public Optional<TenantTool> find(String tenantId, Profile profile, String name) {
        return Optional.ofNullable(byTenant.getOrDefault(tenantId, Map.of()).get(name))
                .filter(t -> t.profiles().contains(profile));
    }

    static Map<String, Map<String, TenantTool>> load(Path file, JsonSchemaValidator validator) {
        TenantToolsFile parsed;
        try {
            parsed = YAMLMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build()
                    .readValue(Files.readString(file), TenantToolsFile.class);
        } catch (IOException e) {
            throw new InvalidTenantToolsException(file, List.of("can't read it: " + e));
        } catch (JacksonException e) {
            throw new InvalidTenantToolsException(file, List.of(e.getOriginalMessage()));
        }
        List<String> problems = new ArrayList<>();
        Map<String, Map<String, TenantTool>> tenants = new LinkedHashMap<>();
        if (parsed == null || parsed.tenants() == null) {
            problems.add("it has no tenants");
        } else {
            parsed.tenants().forEach((tenantId, tenant) -> {
                if (!TENANT.matcher(tenantId).matches()) {
                    problems.add("tenant " + tenantId + ": not a tenant id");
                    return;
                }
                Map<String, TenantTool> tools = new LinkedHashMap<>();
                List<TenantToolsFile.Tool> entries = tenant == null || tenant.tools() == null ? List.of() : tenant.tools();
                for (int i = 0; i < entries.size(); i++) {
                    TenantToolsFile.Tool entry = entries.get(i);
                    String where = "tenants." + tenantId + ".tools[" + i + "]" + (entry.name() == null ? "" : " (" + entry.name() + ")");
                    List<String> own = new ArrayList<>();
                    TenantTool tool = check(tenantId, entry, validator, own);
                    if (tool != null && tools.containsKey(tool.name())) {
                        own.add("the name is used twice for this tenant");
                    }
                    own.forEach(p -> problems.add(where + ": " + p));
                    if (own.isEmpty()) {
                        tools.put(tool.name(), tool);
                    }
                }
                tenants.put(tenantId, Map.copyOf(tools));
            });
        }
        if (!problems.isEmpty()) {
            throw new InvalidTenantToolsException(file, problems);
        }
        return Map.copyOf(tenants);
    }

    private static TenantTool check(String tenantId, TenantToolsFile.Tool t, JsonSchemaValidator validator, List<String> problems) {
        if (t.name() == null || !NAME.matcher(t.name()).matches()) {
            problems.add("name must be snake_case, 3 to 64 lowercase letters, digits or _, starting with a letter; "
                    + "start it with the system's name, as in shop_get_order");
        } else if (t.name().startsWith(RESERVED_PREFIX)) {
            problems.add("names starting with " + RESERVED_PREFIX + " are for the built-in tools");
        }
        if (!StringUtils.hasText(t.description())) {
            problems.add("description is required: what the tool returns and when to use it");
        } else if (t.description().length() > 2000) {
            problems.add("description can have at most 2000 characters");
        }
        if (t.title() != null && t.title().length() > 100) {
            problems.add("title can have at most 100 characters");
        }
        checkSchema(t.inputSchema(), validator, problems);
        Map<String, Object> call = checkCall(t.call(), problems);
        if (t.customerReference() == null || !CUSTOMER_REFERENCES.contains(t.customerReference())) {
            problems.add("customer_reference must be one of " + CUSTOMER_REFERENCES);
        }
        if (t.result() == null || !StringUtils.hasText(t.result().customerField())) {
            problems.add("result.customer_field is required: the result field that holds the customer reference");
        }
        if (t.result() == null || t.result().fields() == null || t.result().fields().isEmpty()
                || t.result().fields().stream().anyMatch(f -> !StringUtils.hasText(f))) {
            problems.add("result.fields is required: the result fields the agent may see");
        }
        Set<Profile> profiles = checkProfiles(t.profiles(), problems);
        Duration timeout = checkTimeout(t.timeout(), problems);
        if (!problems.isEmpty()) {
            return null;
        }
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(t.name())
                .title(t.title())
                .description(t.description().strip())
                .inputSchema(exposedSchema(t.inputSchema()))
                .annotations(new McpSchema.ToolAnnotations(t.title(), true, false, true, true, null))
                .build();
        return new TenantTool(tenantId, t.name(), Map.copyOf(t.inputSchema()), call, t.customerReference(),
                t.result().customerField(), List.copyOf(t.result().fields()), profiles, timeout, tool);
    }

    private static void checkSchema(Map<String, Object> schema, JsonSchemaValidator validator, List<String> problems) {
        if (schema == null) {
            problems.add("input_schema is required: a JSON Schema of type object");
            return;
        }
        if (!"object".equals(schema.get("type"))) {
            problems.add("input_schema must have type: object");
        }
        if (schema.get("properties") instanceof Map<?, ?> properties && properties.containsKey(TenantTool.RESPONSE_FORMAT)) {
            problems.add("input_schema can't have a " + TenantTool.RESPONSE_FORMAT + " property: every tenant tool gets it");
        }
        JsonSchemaValidator.ValidationResponse valid = validator.validateSchema(schema);
        if (!valid.valid()) {
            problems.add("input_schema isn't a valid JSON Schema: " + valid.errorMessage());
        }
    }

    private static Map<String, Object> checkCall(TenantToolsFile.Call call, List<String> problems) {
        if (call == null || (call.https() == null) == (call.crm() == null)) {
            problems.add("call needs exactly one of https or crm");
            return null;
        }
        if (call.https() != null) {
            String url = call.https().url();
            try {
                URI uri = url == null ? null : new URI(url);
                if (uri == null || !"https".equals(uri.getScheme()) || uri.getHost() == null) {
                    problems.add("call.https.url must be an https:// URL");
                }
            } catch (URISyntaxException e) {
                problems.add("call.https.url must be an https:// URL");
            }
            if (call.https().secret() == null || !REFERENCE.matcher(call.https().secret()).matches()) {
                problems.add("call.https.secret must name the secret with the credentials, never hold them");
            }
            return problems.isEmpty() ? Map.of("type", "https", "url", url, "secret", call.https().secret()) : null;
        }
        if (call.crm().connector() == null || !REFERENCE.matcher(call.crm().connector()).matches()
                || call.crm().operation() == null || !REFERENCE.matcher(call.crm().operation()).matches()) {
            problems.add("call.crm needs a connector and an operation");
            return null;
        }
        return Map.of("type", "crm", "connector", call.crm().connector(), "operation", call.crm().operation());
    }

    private static Set<Profile> checkProfiles(List<String> values, List<String> problems) {
        if (values == null || values.isEmpty()) {
            problems.add("profiles is required: agent, supervisor or both");
            return Set.of();
        }
        Set<Profile> profiles = EnumSet.noneOf(Profile.class);
        for (String value : new HashSet<>(values)) {
            Profile.fromWire(value).ifPresentOrElse(profiles::add,
                    () -> problems.add("profile " + value + " isn't agent or supervisor"
                            + ("customer".equals(value) ? " (the customer profile isn't on yet)" : "")));
        }
        return Set.copyOf(profiles);
    }

    private static Duration checkTimeout(String value, List<String> problems) {
        if (!StringUtils.hasText(value)) {
            return DEFAULT_TIMEOUT;
        }
        try {
            Duration timeout = DurationStyle.detectAndParse(value);
            if (timeout.toMillis() < 100 || timeout.compareTo(MAX_TIMEOUT) > 0) {
                problems.add("timeout must be between 100ms and " + MAX_TIMEOUT.toSeconds() + "s");
            }
            return timeout;
        } catch (IllegalArgumentException e) {
            problems.add("timeout must be a duration such as 3s or 500ms");
            return DEFAULT_TIMEOUT;
        }
    }

    /** The tenant's schema plus {@code response_format}, which every tool gets. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> exposedSchema(Map<String, Object> schema) {
        Map<String, Object> exposed = new LinkedHashMap<>(schema);
        Map<String, Object> properties = new LinkedHashMap<>(
                schema.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of());
        properties.put(TenantTool.RESPONSE_FORMAT, Map.of(
                "type", "string",
                "enum", List.of("markdown", "json"),
                "description", "markdown (default) to read; json for every returned field."));
        exposed.put("properties", properties);
        return exposed;
    }
}
