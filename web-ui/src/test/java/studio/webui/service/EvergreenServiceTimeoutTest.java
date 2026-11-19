/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A GitHub that does not answer must not hang the version and announce checks.
 *
 * <p>{@code EvergreenService} used to send its three GitHub requests without any timeout. On a
 * network where GitHub is unreachable but the connection is not refused — a firewall that drops
 * packets, a captive portal, a proxy that accepts and stalls — the request stayed pending forever,
 * and with it the future the controller was waiting on. The frontend then sat on its "fetching"
 * toast for the whole session, because nothing ever came back to turn it into an error.
 *
 * <p>These tests aim the service at a local server that deliberately never answers, through the
 * package-private constructor, and assert that every future <em>completes</em> — failed, with a
 * {@link TimeoutException} as the cause — within a bound. The controller already turns a failed
 * future into a 500, and the frontend already turns that into a logged error or an error toast, so
 * failing fast is the whole fix: the built-in announce is deliberately <em>not</em> used as a
 * fallback here, since popping a 2020 announce on every offline start would be a regression of
 * its own. Which request stalls varies across the tests, because {@code announce()} chains two
 * requests and the second must be bounded too.
 *
 * <p>The nominal two-hop path is pinned as a control, against the same local server answering
 * properly: it proves the seam still routes both hosts. The controls test the routing, not the
 * speed, so they get their own service with the production timeout: given the short timeout of the
 * stalling cases, they would race the first requests of a cold JVM and fail on a slow machine.
 * The fake answers with literal bodies, so it costs nothing to encode them either.
 */
class EvergreenServiceTimeoutTest {

    /** The timeout of the stalling cases: short, so a stalled request is a wait of milliseconds. */
    private static final long STALL_TIMEOUT_MS = 300;
    /** How long a test waits for the future: above both timeouts, far below "forever". */
    private static final long BOUND_MS = 2 * EvergreenService.HTTP_TIMEOUT_MS;

    private Vertx vertx;
    private HttpServer server;
    private int port;
    /** Paths the server must never answer; every other path gets a plausible GitHub response. */
    private final Set<String> stalledPaths = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void startFakeGitHub() throws Exception {
        vertx = Vertx.vertx();
        CompletableFuture<HttpServer> started = new CompletableFuture<>();
        vertx.createHttpServer()
                .requestHandler(this::answerLikeGitHub)
                .listen(0, "127.0.0.1", ar -> {
                    if (ar.succeeded()) {
                        started.complete(ar.result());
                    } else {
                        started.completeExceptionally(ar.cause());
                    }
                });
        server = started.get(10, TimeUnit.SECONDS);
        port = server.actualPort();
    }

    @AfterEach
    void stopFakeGitHub() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close(ar -> closed.complete(null));
        closed.get(10, TimeUnit.SECONDS);
    }

    private void answerLikeGitHub(HttpServerRequest request) {
        if (stalledPaths.contains(request.path())) {
            // Never end the response: the connection stays open and the client sees nothing.
            return;
        }
        if (request.path().equals(EvergreenService.GITHUB_API_LATEST_RELEASE)) {
            request.response().end("{\"name\":\"9.9.9\"}");
        } else if (request.path().equals(EvergreenService.GITHUB_API_ANNOUNCE_COMMIT.replaceAll("\\?.*", ""))) {
            request.response().end("[{\"commit\":{\"committer\":{\"date\":\"2021-01-02T03:04:05Z\"}}}]");
        } else if (request.path().equals(EvergreenService.GITHUB_API_ANNOUNCE_CONTENT)) {
            request.response().end("# Hello");
        } else {
            request.response().setStatusCode(404).end();
        }
    }

    /** A service whose requests to the fake are failed after the short timeout. */
    private EvergreenService stallingService() {
        return service(STALL_TIMEOUT_MS);
    }

    /** A service with the production timeout, for the controls, which must not race the clock. */
    private EvergreenService answeringService() {
        return service(EvergreenService.HTTP_TIMEOUT_MS);
    }

    private EvergreenService service(long timeoutMs) {
        // Both GitHub hosts point at the fake, which tells them apart by path.
        return new EvergreenService(vertx, "127.0.0.1", "127.0.0.1", port, false, timeoutMs);
    }

    /** Waits for the Vert.x future within the bound, failing the test if it never completes. */
    private static AsyncResult<JsonObject> awaitWithinBound(Future<JsonObject> future) throws Exception {
        CompletableFuture<AsyncResult<JsonObject>> done = new CompletableFuture<>();
        future.setHandler(done::complete);
        try {
            return done.get(BOUND_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return fail("the future never completed within " + BOUND_MS + " ms — this is the hang the timeout exists to prevent");
        }
    }

    private static void assertTimedOut(AsyncResult<JsonObject> result) {
        assertTrue(result.failed(), "a stalled GitHub must fail the request, got " + result.result());
        Throwable cause = result.cause();
        while (cause != null && !(cause instanceof TimeoutException)) {
            cause = cause.getCause();
        }
        assertTrue(cause != null, "the failure must be the timeout, got " + result.cause());
    }

    @Test
    @DisplayName("latest() fails within the bound when GitHub never answers")
    void latestFailsFastWhenGitHubStalls() throws Exception {
        stalledPaths.add(EvergreenService.GITHUB_API_LATEST_RELEASE);

        assertTimedOut(awaitWithinBound(stallingService().latest()));
    }

    @Test
    @DisplayName("announce() fails within the bound when the commit lookup never answers")
    void announceFailsFastWhenCommitLookupStalls() throws Exception {
        stalledPaths.add(EvergreenService.GITHUB_API_ANNOUNCE_COMMIT.replaceAll("\\?.*", ""));

        assertTimedOut(awaitWithinBound(stallingService().announce()));
    }

    @Test
    @DisplayName("announce() fails within the bound when only the content fetch never answers")
    void announceFailsFastWhenContentFetchStalls() throws Exception {
        // The second hop: the commit lookup answers, the raw content does not. Bounding only the first
        // request would leave this path hanging exactly as before.
        stalledPaths.add(EvergreenService.GITHUB_API_ANNOUNCE_CONTENT);

        assertTimedOut(awaitWithinBound(stallingService().announce()));
    }

    @Test
    @DisplayName("announce() still chains both requests when GitHub answers")
    void announceStillWorksWhenGitHubAnswers() throws Exception {
        AsyncResult<JsonObject> result = awaitWithinBound(answeringService().announce());

        assertTrue(result.succeeded(), "got " + result.cause());
        assertEquals("2021-01-02T03:04:05Z", result.result().getString("date"));
        assertEquals("# Hello", result.result().getString("content"));
    }

    @Test
    @DisplayName("latest() still returns the release when GitHub answers")
    void latestStillWorksWhenGitHubAnswers() throws Exception {
        AsyncResult<JsonObject> result = awaitWithinBound(answeringService().latest());

        assertTrue(result.succeeded(), "got " + result.cause());
        assertEquals("9.9.9", result.result().getString("name"));
    }
}
