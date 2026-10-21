package parity.ccmcp.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InFlightRequestsTest {

    private final InFlightRequests requests = new InFlightRequests();

    @Test
    void whileDrainingNewRequestsAreSentElsewhere() throws Exception {
        requests.startDraining();
        MockHttpServletResponse response = new MockHttpServletResponse();

        requests.doFilter(new MockHttpServletRequest("POST", "/mcp"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void theDrainWaitsForTheRequestsInProgress() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> call = pool.submit(() -> {
                requests.doFilter(new MockHttpServletRequest("POST", "/mcp"), new MockHttpServletResponse(),
                        (request, response) -> {
                            inside.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
                return null;
            });
            inside.await();
            requests.startDraining();

            assertThat(requests.awaitIdle(Duration.ofMillis(100))).isFalse();
            release.countDown();
            assertThat(requests.awaitIdle(Duration.ofSeconds(5))).isTrue();
            call.get();
        } finally {
            pool.shutdownNow();
        }
    }
}
