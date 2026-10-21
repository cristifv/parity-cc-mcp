package parity.ccmcp.tenanttools;

import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.List;
import java.util.function.UnaryOperator;
import reactor.core.publisher.Mono;

/**
 * The transport the MCP server is built on: the WebMVC streamable HTTP transport, with the
 * server's handler wrapped in {@link TenantToolsHandler} when the server hands it over.
 */
public class TenantToolsTransport implements McpStatelessServerTransport {

    private final McpStatelessServerTransport http;
    private final UnaryOperator<McpStatelessServerHandler> wrap;

    public TenantToolsTransport(McpStatelessServerTransport http, UnaryOperator<McpStatelessServerHandler> wrap) {
        this.http = http;
        this.wrap = wrap;
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        http.setMcpHandler(wrap.apply(handler));
    }

    @Override
    public void close() {
        http.close();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return http.closeGracefully();
    }

    @Override
    public List<String> protocolVersions() {
        return http.protocolVersions();
    }
}
