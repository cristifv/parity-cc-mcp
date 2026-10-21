package parity.ccmcp.tenanttools;

import java.nio.file.Path;
import java.util.List;

/** The tenant tools file is invalid; the service doesn't start. */
public class InvalidTenantToolsException extends RuntimeException {

    private final List<String> problems;

    public InvalidTenantToolsException(Path file, List<String> problems) {
        super("invalid tenant tools file " + file + ":\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
