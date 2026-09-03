package com.aireak.inventory.adapter.out.client;

import com.aireak.inventory.config.InfraConfig;
import com.aireak.inventory.domain.exception.ShowtimeCatalogUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the read timeout on the {@code catalogRestClient} bean against a real socket, because that
 * is the only way the bug it fixes is visible: {@code RestClient.builder().build()} — how this
 * bean was built — applies no read timeout at all, so a catalog-service that accepts the
 * connection and then never answers blocks the caller forever. {@link MockRestServiceServer},
 * which the sibling {@link ShowtimeCatalogRestAdapterTest} uses, never opens a socket and so can
 * never observe this.
 *
 * <p>Why it matters more than one lost request: {@code requireBookable} runs while holding a
 * {@code @Bulkhead(name = "seat-inventory")} permit, so stalled calls exhaust that semaphore and
 * take every seat hold and reservation down with them (see {@code InfraConfig}).
 */
class ShowtimeCatalogRestClientTimeoutTest {

    /** Longer than the bean's read timeout, short enough that a regression can't wedge the build. */
    private static final Duration SERVER_STALL = Duration.ofSeconds(20);
    /** Comfortably above the bean's 3s read timeout, far below {@link #SERVER_STALL}. */
    private static final Duration GIVE_UP_AFTER = Duration.ofSeconds(10);

    private HttpServer stallingCatalog;
    private ShowtimeCatalogRestAdapter adapter;

    @BeforeEach
    void startStallingCatalog() throws IOException {
        stallingCatalog = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stallingCatalog.createContext("/", exchange -> {
            try {
                Thread.sleep(SERVER_STALL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        stallingCatalog.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stalling-catalog");
            thread.setDaemon(true);
            return thread;
        }));
        stallingCatalog.start();

        adapter = new ShowtimeCatalogRestAdapter(new InfraConfig().catalogRestClient());
        ReflectionTestUtils.setField(adapter, "baseUrl",
                "http://127.0.0.1:" + stallingCatalog.getAddress().getPort());
    }

    @AfterEach
    void stopStallingCatalog() {
        stallingCatalog.stop(0);
    }

    @Test
    void requireBookable_whenCatalogAcceptsTheConnectionButNeverAnswers_givesUpInsteadOfBlockingForever() {
        assertTimeoutPreemptively(GIVE_UP_AFTER, () ->
                assertThatThrownBy(() -> adapter.requireBookable("showtime-1"))
                        .isInstanceOf(ShowtimeCatalogUnavailableException.class));
    }
}
