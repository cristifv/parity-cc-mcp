package parity.ccmcp.tenanttools;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import parity.ccmcp.scope.Scope;
import parity.ccmcp.scope.ScopeHeaders;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Wraps the MCP server's request handler. The SDK keeps one tool list for the whole server,
 * but each tenant's tools are for that tenant only, so:
 * <ul>
 *   <li>{@code tools/list} answers the built-in tools plus the session tenant's tools for
 *       its profile;</li>
 *   <li>{@code tools/call} of one of those runs it ({@link TenantToolExecutor}). A tool of
 *       another tenant, or of another profile, stays unknown, as if it didn't exist.</li>
 * </ul>
 * Everything else goes to the server as it is, except the client's
 * {@code notifications/initialized}, which a stateless server ignores.
 */
public class TenantToolsHandler implements McpStatelessServerHandler {

    private final McpStatelessServerHandler server;
    private final TenantToolCatalog catalog;
    private final TenantToolExecutor executor;
    private final McpJsonMapper mapper;

    public TenantToolsHandler(McpStatelessServerHandler server, TenantToolCatalog catalog, TenantToolExecutor executor,
            McpJsonMapper mapper) {
        this.server = server;
        this.catalog = catalog;
        this.executor = executor;
        this.mapper = mapper;
    }

    @Override
    public Mono<JSONRPCResponse> handleRequest(McpTransportContext context, McpSchema.JSONRPCRequest request) {
        Optional<Scope> scope = ScopeHeaders.from(context);
        if (scope.isEmpty()) {
            return server.handleRequest(context, request);
        }
        return switch (request.method()) {
            case McpSchema.METHOD_TOOLS_LIST -> server.handleRequest(context, request)
                    .map(response -> withTenantTools(response, scope.get()));
            case McpSchema.METHOD_TOOLS_CALL -> {
                CallToolRequest call = mapper.convertValue(request.params(), CallToolRequest.class);
                Optional<TenantTool> tool = catalog.find(scope.get().tenantId(), scope.get().profile(), call.name());
                yield tool.isEmpty()
                        ? server.handleRequest(context, request)
                        : Mono.fromCallable(() -> executor.call(context, tool.get(), call.arguments()))
                                .subscribeOn(Schedulers.boundedElastic())
                                .map(result -> JSONRPCResponse.result(request.id(), result));
            }
            default -> server.handleRequest(context, request);
        };
    }

    /**
     * A stateless server has nothing to do when a client says it's initialized; the SDK's
     * handler would log a warning for every client.
     */
    @Override
    public Mono<Void> handleNotification(McpTransportContext context, McpSchema.JSONRPCNotification notification) {
        if (McpSchema.METHOD_NOTIFICATION_INITIALIZED.equals(notification.method())) {
            return Mono.empty();
        }
        return server.handleNotification(context, notification);
    }

    private JSONRPCResponse withTenantTools(JSONRPCResponse response, Scope scope) {
        if (response.error() != null || response.result() == null) {
            return response;
        }
        List<TenantTool> own = catalog.forScope(scope.tenantId(), scope.profile());
        if (own.isEmpty()) {
            return response;
        }
        ListToolsResult builtIn = response.result() instanceof ListToolsResult r ? r
                : mapper.convertValue(response.result(), ListToolsResult.class);
        List<McpSchema.Tool> tools = new ArrayList<>(builtIn.tools());
        own.forEach(t -> tools.add(t.tool()));
        return JSONRPCResponse.result(response.id(), new ListToolsResult(tools, builtIn.nextCursor(), builtIn.meta()));
    }
}
