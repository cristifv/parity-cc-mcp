package parity.ccmcp.scope;

import io.modelcontextprotocol.common.McpTransportContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;

/**
 * The scope headers. v1 trusts them on a zone-internal connection from the LLM gateway; OAuth
 * bearer tokens and mTLS (ARCHITECTURE.md §3.23) come later.
 */
public final class ScopeHeaders {

    public static final String TENANT = "X-Tenant-ID";
    public static final String PROFILE = "X-Profile";
    public static final String PERSON = "X-Person-ID";
    public static final String CUSTOMER = "X-Customer-Key";
    public static final String INTERACTION = "X-Interaction-ID";

    /** Where the scope is kept in the MCP transport context. */
    static final String CONTEXT_KEY = "parity.scope";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:@-]{1,128}");

    private ScopeHeaders() {
    }

    /**
     * The scope in these headers, or the problems that keep it from being one. A missing
     * customer or interaction is not a problem: some sessions have none.
     */
    public static Result read(HttpHeaders headers) {
        List<String> problems = new ArrayList<>();
        String tenant = required(headers, TENANT, problems);
        String person = required(headers, PERSON, problems);
        String profileValue = required(headers, PROFILE, problems);
        Profile profile = null;
        if (profileValue != null) {
            profile = Profile.fromWire(profileValue).orElse(null);
            if (profile == null) {
                problems.add(PROFILE + " must be agent or supervisor, not " + profileValue);
            }
        }
        String customer = optional(headers, CUSTOMER, problems);
        String interaction = optional(headers, INTERACTION, problems);
        if (!problems.isEmpty()) {
            return new Result(null, List.copyOf(problems));
        }
        return new Result(new Scope(tenant, profile, person, customer, interaction), List.of());
    }

    /** The transport context that carries this scope to the tools. */
    public static McpTransportContext context(Scope scope) {
        return McpTransportContext.create(Map.of(CONTEXT_KEY, scope));
    }

    public static Optional<Scope> from(McpTransportContext context) {
        return context == null ? Optional.empty() : Optional.ofNullable((Scope) context.get(CONTEXT_KEY));
    }

    private static String required(HttpHeaders headers, String name, List<String> problems) {
        String value = headers.getFirst(name);
        if (value == null || value.isBlank()) {
            problems.add(name + " is missing");
            return null;
        }
        return checked(name, value, problems);
    }

    private static String optional(HttpHeaders headers, String name, List<String> problems) {
        String value = headers.getFirst(name);
        return value == null || value.isBlank() ? null : checked(name, value, problems);
    }

    private static String checked(String name, String value, List<String> problems) {
        if (!ID.matcher(value).matches()) {
            problems.add(name + " must be 1 to 128 letters, digits or . _ : @ -");
            return null;
        }
        return value;
    }

    public record Result(Scope scope, List<String> problems) {

        public boolean valid() {
            return scope != null;
        }
    }
}
