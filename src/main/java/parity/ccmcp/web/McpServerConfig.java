package parity.ccmcp.web;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import parity.ccmcp.config.CcMcpProperties;
import parity.ccmcp.scope.ScopeHeaders;
import parity.ccmcp.scope.ScopeValidator;
import parity.ccmcp.tenanttools.TenantToolCatalog;
import parity.ccmcp.tenanttools.TenantToolExecutor;
import parity.ccmcp.tenanttools.TenantToolsHandler;
import parity.ccmcp.tenanttools.TenantToolsTransport;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP server's transport: streamable HTTP, stateless (Anthropic's guidance for remote
 * servers), at {@code spring.ai.mcp.server.streamable-http.mcp-endpoint}. Spring AI builds
 * the server and registers the {@code @McpTool} methods; this replaces its transport with
 * one that:
 * <ul>
 *   <li>refuses requests with a foreign {@code Origin} or without a scope
 *       ({@link ScopeValidator});</li>
 *   <li>hands the scope to the tools in the transport context;</li>
 *   <li>adds each tenant's tools ({@link TenantToolsHandler}).</li>
 * </ul>
 */
@Configuration
public class McpServerConfig {

    @Bean
    public WebMvcStatelessServerTransport webMvcStatelessServerTransport(JsonMapper json,
            McpServerStreamableHttpProperties http, CcMcpProperties properties) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(json))
                .messageEndpoint(http.getMcpEndpoint())
                .securityValidator(new ScopeValidator(properties.allowedOrigins()))
                .contextExtractor(request -> {
                    ScopeHeaders.Result scope = ScopeHeaders.read(request.headers().asHttpHeaders());
                    return scope.valid() ? ScopeHeaders.context(scope.scope()) : McpTransportContext.EMPTY;
                })
                .build();
    }

    @Bean
    @Primary
    public McpStatelessServerTransport tenantToolsTransport(WebMvcStatelessServerTransport http,
            TenantToolCatalog catalog, TenantToolExecutor executor, JsonMapper json) {
        McpJsonMapper mapper = new JacksonMcpJsonMapper(json);
        return new TenantToolsTransport(http, server -> new TenantToolsHandler(server, catalog, executor, mapper));
    }

    @Bean
    public JsonSchemaValidator jsonSchemaValidator(JsonMapper json) {
        return new DefaultJsonSchemaValidator(json);
    }
}
