package parity.ccmcp.scope;

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;

/**
 * Checks every MCP request before it reaches the server:
 * <ul>
 *   <li>an {@code Origin} header, which only browsers send, must be an allowed origin. This
 *       stops a web page from calling the endpoint through a person's browser (DNS
 *       rebinding);</li>
 *   <li>the scope headers must name a tenant, a profile and a person.</li>
 * </ul>
 */
public class ScopeValidator implements ServerTransportSecurityValidator {

    private final List<String> allowedOrigins;

    public ScopeValidator(List<String> allowedOrigins) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
    }

    @Override
    public void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
        HttpHeaders http = new HttpHeaders();
        headers.forEach(http::addAll);
        String origin = http.getOrigin();
        if (origin != null && !allowedOrigins.contains(origin)) {
            throw new ServerTransportSecurityException(403, "origin not allowed: " + origin);
        }
        ScopeHeaders.Result scope = ScopeHeaders.read(http);
        if (!scope.valid()) {
            throw new ServerTransportSecurityException(401, "no valid scope: " + String.join("; ", scope.problems()));
        }
    }
}
