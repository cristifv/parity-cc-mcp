package parity.ccmcp.tools;

/**
 * A tool call that can't be done. The message goes to the agent, so it says what was wrong
 * and what to do instead, never internals (Anthropic's tool guidelines). The code is the
 * audit event's outcome.
 */
public class ToolError extends RuntimeException {

    private final String code;

    public ToolError(String code, String message) {
        super(message);
        this.code = code;
    }

    public static ToolError invalid(String message) {
        return new ToolError("invalid_arguments", message);
    }

    public String code() {
        return code;
    }
}
