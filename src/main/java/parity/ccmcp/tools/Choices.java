package parity.ccmcp.tools;

/**
 * The fixed choices of the tools' arguments. The schema generator writes enum constants into
 * the tool schemas as they are, so the constants are spelled as the model, and the owners'
 * requests, see them: lowercase snake_case.
 */
public final class Choices {

    private Choices() {
    }

    public enum ResponseFormat {
        /** For reading: headers and lists, readable times, ids in parentheses. The default. */
        markdown,
        /** Every field, ids and timestamps included, for further processing. */
        json
    }

    public enum Channel {
        voice, web_chat, sms, whatsapp, messenger, instagram, email
    }

    public enum CaseState {
        open, waiting_customer, waiting_us, solved
    }

    public enum Risk {
        low, medium, high
    }

    public enum Person {
        agent, supervisor
    }
}
