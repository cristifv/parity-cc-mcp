package parity.ccmcp.owner;

/** The owner didn't answer in time, or the request couldn't be sent. */
public class OwnerUnavailableException extends Exception {

    private final String owner;

    public OwnerUnavailableException(String owner, String message, Throwable cause) {
        super(message, cause);
        this.owner = owner;
    }

    public String owner() {
        return owner;
    }
}
