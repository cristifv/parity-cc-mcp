package parity.ccmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The contact center's MCP server. AI agents connect as MCP clients and call its tools: the
 * context and cases of the customer a session works for, and each tenant's own read-only
 * tools. It owns no data; every tool forwards to the service that owns it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ParityCcMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ParityCcMcpApplication.class, args);
    }
}
