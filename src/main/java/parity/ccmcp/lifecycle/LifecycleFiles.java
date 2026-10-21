package parity.ccmcp.lifecycle;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import parity.ccmcp.config.CcMcpProperties;
import parity.ccmcp.owner.OwnerClient;

/**
 * Startup and graceful shutdown, signalled to the orchestrator with two empty files:
 * <ul>
 *   <li>the healthy file, created once the service has started and its replies listener has
 *       its partitions;</li>
 *   <li>the drained file, created once the service has drained and can be stopped.</li>
 * </ul>
 * On {@code SIGTERM} this stops first (the highest phase), while the replies listener still
 * runs: it removes the healthy file, refuses new MCP requests, waits for the calls in progress
 * within the drain timeout, and creates the drained file. If the time runs out, there is no
 * drained file.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LifecycleFiles implements SmartLifecycle {

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(30);

    private final CcMcpProperties properties;
    private final InFlightRequests requests;
    private final OwnerClient owner;
    private volatile boolean running;

    /** Files left by an earlier run would tell the orchestrator the wrong thing. */
    @PostConstruct
    void removeStaleFiles() throws IOException {
        for (Path file : new Path[] {healthyFile(), drainedFile()}) {
            if (Files.deleteIfExists(file)) {
                log.info("removed {}, left by an earlier run", file);
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void started() throws IOException, InterruptedException {
        if (!owner.awaitReady(READY_TIMEOUT)) {
            log.error("the replies listener has no partitions after {} s; not healthy", READY_TIMEOUT.toSeconds());
            return;
        }
        touch(healthyFile());
        log.info("started; created {}", healthyFile());
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        long startedAt = System.nanoTime();
        try {
            Files.deleteIfExists(healthyFile());
            requests.startDraining();
            log.info("draining: removed {}, {} requests in progress", healthyFile(), requests.inFlight());
            if (requests.awaitIdle(properties.lifecycle().drainTimeout())) {
                touch(drainedFile());
                log.info("drained {} ms after the shutdown began; created {}",
                        (System.nanoTime() - startedAt) / 1_000_000, drainedFile());
            } else {
                log.error("not drained within {} ms: {} requests still in progress; no {}",
                        properties.lifecycle().drainTimeout().toMillis(), requests.inFlight(), drainedFile());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.error("graceful shutdown: {}", e.toString());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private Path healthyFile() {
        return Path.of(properties.lifecycle().healthyFile());
    }

    private Path drainedFile() {
        return Path.of(properties.lifecycle().drainedFile());
    }

    private static void touch(Path file) throws IOException {
        Files.deleteIfExists(file);
        Files.createFile(file);
    }
}
