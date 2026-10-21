package parity.ccmcp.scope;

import java.util.Optional;

/**
 * Who is asking: the tenant, the profile and person the session acts for, and the customer
 * and interaction they work on. It comes from the session (HTTP headers set by the LLM
 * gateway), never from tool arguments, and every tool reaches only data inside it.
 *
 * @param customerKey   the customer of the interaction; empty when the session has none
 * @param interactionId the interaction the person works on; empty when there is none
 */
public record Scope(String tenantId, Profile profile, String personId, String customerKey, String interactionId) {

    public Optional<String> customer() {
        return Optional.ofNullable(customerKey);
    }

    public Optional<String> interaction() {
        return Optional.ofNullable(interactionId);
    }
}
