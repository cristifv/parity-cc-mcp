package parity.ccmcp.scope;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Whom a session acts for. The architecture's third profile, {@code customer} (bots), is off
 * in v1.
 */
public enum Profile {
    AGENT,
    SUPERVISOR;

    /** {@code agent}: the value in the header, the tenant tools file and the owner's requests. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Profile> fromWire(String value) {
        return Arrays.stream(values()).filter(p -> p.wire().equals(value)).findFirst();
    }
}
