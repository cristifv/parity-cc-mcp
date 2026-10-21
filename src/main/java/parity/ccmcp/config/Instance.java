package parity.ccmcp.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * This instance's name: the configured instance id, or host name and process id. It
 * prefixes the ids of requests to the owners, and names the consumer group that reads the
 * replies, so every instance reads every reply and keeps its own.
 */
@Component
public class Instance {

    private final String id;

    public Instance(CcMcpProperties properties) {
        String configured = properties.lifecycle().instanceId();
        this.id = StringUtils.hasText(configured) ? configured : hostName() + "-" + ProcessHandle.current().pid();
    }

    public String id() {
        return id;
    }

    /** The consumer group for the replies topic: one per instance. */
    public String replyGroupId() {
        return "parity-cc-mcp-replies-" + id;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
