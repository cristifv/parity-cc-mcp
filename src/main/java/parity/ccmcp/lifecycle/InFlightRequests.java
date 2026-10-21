package parity.ccmcp.lifecycle;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Counts the HTTP requests in progress, and once draining refuses new ones with 503 and
 * {@code Retry-After}, so the client's next call goes to another instance. The service is
 * stateless, so nothing is lost.
 */
@Component
public class InFlightRequests extends OncePerRequestFilter {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object idle = new Object();
    private volatile boolean draining;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (draining) {
            response.setHeader("Retry-After", "1");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "draining");
            return;
        }
        inFlight.incrementAndGet();
        try {
            chain.doFilter(request, response);
        } finally {
            if (inFlight.decrementAndGet() == 0) {
                synchronized (idle) {
                    idle.notifyAll();
                }
            }
        }
    }

    void startDraining() {
        draining = true;
    }

    int inFlight() {
        return inFlight.get();
    }

    /** True once no request is in progress, false if {@code timeout} ran out first. */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (idle) {
            while (inFlight.get() > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                idle.wait(Math.max(1, left / 1_000_000));
            }
        }
        return true;
    }
}
