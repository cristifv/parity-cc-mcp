package parity.ccmcp.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import parity.ccmcp.config.CcMcpProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tool results as the agent gets them. Errors are results with {@code isError}, not protocol
 * errors, so the agent reads them and can act. No result is longer than the character limit.
 */
@Component
@RequiredArgsConstructor
public class ToolResults {

    private final CcMcpProperties properties;
    private final JsonMapper json;

    /** Markdown or plain text, cut at the limit with a note on how to ask for less. */
    public CallToolResult text(String text, String howToAskForLess) {
        int limit = properties.resultCharacterLimit();
        if (text.length() > limit) {
            text = text.substring(0, limit) + "\n\n[Cut at " + limit + " characters. " + howToAskForLess + "]";
        }
        return CallToolResult.builder().addTextContent(text).isError(false).build();
    }

    /**
     * JSON. Cut JSON can't be parsed, so a result over the limit is an error that says how to
     * ask for less.
     */
    public CallToolResult json(JsonNode node, String howToAskForLess) {
        String text = json.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        int limit = properties.resultCharacterLimit();
        if (text.length() > limit) {
            throw new ToolError("result_too_long", "The result is " + text.length() + " characters, more than the "
                    + limit + " a result may have. " + howToAskForLess);
        }
        return CallToolResult.builder().addTextContent(text).isError(false).build();
    }

    public static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }
}
