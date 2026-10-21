package parity.ccmcp.owner;

/**
 * The owner answered {@code "ok": false}. {@code code} is the owner's error code
 * ({@code not_found}, {@code bad_request}, {@code conflict}, {@code forbidden},
 * {@code internal}); the message is written for an AI agent (the owner contract, README.md).
 */
public class OwnerErrorException extends Exception {

    private final String owner;
    private final String code;

    public OwnerErrorException(String owner, String code, String message) {
        super(message);
        this.owner = owner;
        this.code = code;
    }

    public String owner() {
        return owner;
    }

    public String code() {
        return code;
    }
}
