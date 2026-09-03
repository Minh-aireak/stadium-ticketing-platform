package com.aireak.catalog.adapter.out.search;

import com.aireak.catalog.config.ElasticsearchClientConfig;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the timeouts on the {@code elasticsearchRestClient} bean against a real socket that accepts
 * the connection and then never answers — a cluster that is wedged rather than down, since down
 * fails fast with a connection refusal.
 *
 * <p>Deliberately concurrent, and that is the whole point: one call was always bounded, because
 * this client (unlike Spring's {@code RestClient}) defaults to a 30s socket timeout. What was
 * unbounded was the wait for a connection out of a pool that leases ten per route. Callers queued
 * in waves of ten, each wave paying a full socket timeout, and the {@code catalog-read} bulkhead
 * admits 200 of them — so a single stalled cluster could hold permits for minutes and take
 * {@code getMatch}, {@code getShowtime} and the no-query browse down with it, none of which talk to
 * Elasticsearch. A single-threaded test cannot see any of that.
 *
 * <p>{@link ElasticsearchMatchSearchAdapterIntegrationTest} runs a real container that answers
 * promptly, so it cannot see it either.
 */
class ElasticsearchSearchStallTest {

    /** More than the pool's ten connections per route, so the queueing behaviour is exercised. */
    private static final int CONCURRENT_SEARCHES = 25;

    /**
     * Comfortably above the bean's 2s connection-request + 3s socket budget, and far below the
     * ~90s the same 25 calls took before those budgets existed.
     */
    private static final Duration GIVE_UP_AFTER = Duration.ofSeconds(20);

    private ServerSocket wedgedCluster;
    private final List<Socket> accepted = new ArrayList<>();
    private RestClient restClient;
    private ElasticsearchMatchSearchAdapter adapter;

    @BeforeEach
    void startWedgedCluster() throws IOException {
        wedgedCluster = new ServerSocket(0, CONCURRENT_SEARCHES, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            try {
                while (!wedgedCluster.isClosed()) {
                    // Held open, never written to. Closing would let the client fail fast and the
                    // test would stop describing a stall.
                    synchronized (accepted) {
                        accepted.add(wedgedCluster.accept());
                    }
                }
            } catch (IOException closed) {
                // Expected when the socket is closed in teardown.
            }
        }, "wedged-elasticsearch");
        acceptor.setDaemon(true);
        acceptor.start();

        ElasticsearchClientConfig config = new ElasticsearchClientConfig();
        restClient = config.elasticsearchRestClient(
                "127.0.0.1", wedgedCluster.getLocalPort(), "http", "catalog_service", "irrelevant");
        adapter = new ElasticsearchMatchSearchAdapter(
                config.elasticsearchClient(config.elasticsearchTransport(restClient)));
    }

    @AfterEach
    void stopWedgedCluster() throws IOException {
        restClient.close();
        wedgedCluster.close();
        synchronized (accepted) {
            for (Socket socket : accepted) {
                socket.close();
            }
        }
    }

    @Test
    void search_whenTheClusterAcceptsConnectionsButNeverAnswers_releasesEveryCallerInsteadOfQueueingThem()
            throws InterruptedException {
        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch allFinished = new CountDownLatch(CONCURRENT_SEARCHES);
        AtomicLong slowestMillis = new AtomicLong();

        for (int i = 0; i < CONCURRENT_SEARCHES; i++) {
            Thread caller = new Thread(() -> {
                try {
                    startTogether.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long startedAt = System.nanoTime();
                try {
                    adapter.search("anything", 0, 10);
                } catch (RuntimeException expected) {
                    // Giving up IS the pass condition. Which way it gives up depends on whether this
                    // caller got a connection (socket timeout, wrapped as MatchSearchException) or
                    // never did (the transport's own "error while performing request").
                }
                slowestMillis.accumulateAndGet(
                        (System.nanoTime() - startedAt) / 1_000_000, Math::max);
                allFinished.countDown();
            }, "stalled-search-" + i);
            caller.setDaemon(true);
            caller.start();
        }

        startTogether.countDown();
        boolean everyCallerGaveUp = allFinished.await(GIVE_UP_AFTER.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(everyCallerGaveUp)
                .as("all %d callers must release their bulkhead permit within %s; slowest so far %dms",
                        CONCURRENT_SEARCHES, GIVE_UP_AFTER, slowestMillis.get())
                .isTrue();
        assertThat(slowestMillis.get())
                .as("slowest of %d concurrent searches against a wedged cluster", CONCURRENT_SEARCHES)
                .isLessThan(GIVE_UP_AFTER.toMillis());
    }
}
