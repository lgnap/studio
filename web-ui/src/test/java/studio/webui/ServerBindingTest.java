/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Which interfaces the web server accepts connections on.
 *
 * <p>These are <strong>specifications</strong>, not characterization. The behaviour they describe
 * replaced an earlier one: {@code listen(8080)} uses Vert.x's default host, {@code 0.0.0.0}, so
 * every interface accepted connections to an API that has no authentication and that lists,
 * downloads, uploads, converts and deletes in the library, and drives a connected device. Anyone on
 * the same network segment reached it, and since the launcher opens a browser on {@code localhost}
 * the user had no reason to suspect otherwise.
 *
 * <p>The rule is one sentence: <strong>the server listens on the loopback unless it is told
 * otherwise</strong>. Widening it stays possible, because someone may genuinely want to reach their
 * own library from a tablet, but it is now a decision someone made rather than the default.
 *
 * <p>What these tests deliberately do not assert:
 *
 * <ul>
 *   <li><em>That a failed bind is logged.</em> The message is not a contract, and asserting on log
 *       output would break every time it is reworded. What is asserted is the consequence that
 *       matters: an occupied port leaves the existing occupant untouched and does not take the
 *       application down.
 *   <li><em>That the browser is not opened.</em> It goes through {@link java.awt.Desktop}, which
 *       has no seam here. The tests run with {@code studio.open=false} for that reason.
 *   <li><em>Anything about a real device.</em> They run with {@code env=dev}, so the mock story
 *       teller service is used and libusb is never touched.
 * </ul>
 */
@DisplayName("Where the web server accepts connections")
class ServerBindingTest {

    /** Every system property these tests write, saved and restored around each one. */
    private static final List<String> TOUCHED_PROPERTIES = List.of(
            "studio.host", "studio.port", "studio.open", "env",
            "studio.library", "studio.tmpdir", "studio.db.official", "studio.db.unofficial");

    private final Map<String, String> savedProperties = new HashMap<>();

    @TempDir
    Path studioHome;

    private Vertx vertx;

    @BeforeEach
    void setUp() throws IOException {
        for (String property : TOUCHED_PROPERTIES) {
            savedProperties.put(property, System.getProperty(property));
        }

        // Point every path at the temporary directory. The official database in particular: when
        // the file is missing the service falls back to fetching it over the network, and a test
        // that quietly downloads a database is a test that fails on a train.
        Path officialDb = studioHome.resolve("official.json");
        Files.write(officialDb, "{}".getBytes(StandardCharsets.UTF_8));
        System.setProperty("studio.db.official", officialDb.toString());
        System.setProperty("studio.db.unofficial", studioHome.resolve("unofficial.json").toString());
        System.setProperty("studio.library",
                Files.createDirectories(studioHome.resolve("library")) + "/");
        System.setProperty("studio.tmpdir",
                Files.createDirectories(studioHome.resolve("tmp")) + "/");

        System.setProperty("env", "dev");           // mock story teller, no libusb
        System.setProperty("studio.open", "false"); // no browser

        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (vertx != null) {
            CountDownLatch closed = new CountDownLatch(1);
            vertx.close(ar -> closed.countDown());
            closed.await(30, TimeUnit.SECONDS);
        }
        savedProperties.forEach((property, value) -> {
            if (value == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, value);
            }
        });
    }

    @Nested
    @DisplayName("By default")
    class ByDefault {

        @Test
        @DisplayName("it accepts connections on the loopback")
        void acceptsLoopback() throws Exception {
            int port = freePort();
            System.setProperty("studio.port", Integer.toString(port));

            deployMainVerticle();

            assertTrue(waitForConnection("127.0.0.1", port),
                    "the server should be reachable on the loopback");
        }

        @Test
        @DisplayName("it refuses connections on every other interface")
        void refusesNonLoopback() throws Exception {
            String externalAddress = nonLoopbackAddress().orElse(null);
            assumeTrue(externalAddress != null,
                    "no non-loopback IPv4 address on this machine, nothing to refuse");

            int port = freePort();
            System.setProperty("studio.port", Integer.toString(port));

            deployMainVerticle();

            // The loopback check first: once it answers, the socket is bound and a refusal on the
            // other address is a real refusal rather than a server that has not started yet.
            assertTrue(waitForConnection("127.0.0.1", port), "the server should have started");
            assertFalse(connectsOnce(externalAddress, port),
                    "the server should not be reachable on " + externalAddress);
        }
    }

    @Nested
    @DisplayName("When told otherwise")
    class WhenTold {

        @Test
        @DisplayName("studio.port chooses the port")
        void portIsHonoured() throws Exception {
            int port = freePort();
            System.setProperty("studio.port", Integer.toString(port));

            deployMainVerticle();

            assertTrue(waitForConnection("127.0.0.1", port),
                    "the server should listen on the port it was given");
        }

        @Test
        @DisplayName("studio.host widens the binding, which is what makes the default a choice")
        void hostCanWidenTheBinding() throws Exception {
            String externalAddress = nonLoopbackAddress().orElse(null);
            assumeTrue(externalAddress != null,
                    "no non-loopback IPv4 address on this machine, nothing to widen onto");

            int port = freePort();
            System.setProperty("studio.port", Integer.toString(port));
            System.setProperty("studio.host", "0.0.0.0");

            deployMainVerticle();

            assertTrue(waitForConnection(externalAddress, port),
                    "an explicit 0.0.0.0 should accept connections on " + externalAddress);
        }
    }

    @Nested
    @DisplayName("When the port is already in use")
    class PortInUse {

        @Test
        @DisplayName("the occupant keeps it, and the application stays up")
        void occupiedPortIsSurvived() throws Exception {
            try (ServerSocket occupant = new ServerSocket()) {
                occupant.bind(new InetSocketAddress("127.0.0.1", 0));
                int port = occupant.getLocalPort();
                System.setProperty("studio.port", Integer.toString(port));

                // Deployment itself must not fail: `listen` is asynchronous, and the point of the
                // handler added alongside these tests is that its failure is dealt with rather
                // than discarded. Before that handler existed this test still passed — what it
                // guards is that the failure never becomes an exception nobody catches.
                deployMainVerticle();

                assertTrue(occupant.isBound() && !occupant.isClosed(),
                        "the socket that already held the port should still hold it");
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private void deployMainVerticle() throws InterruptedException {
        CountDownLatch deployed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        vertx.deployVerticle(new MainVerticle(), ar -> {
            if (ar.failed()) {
                failure.set(ar.cause());
            }
            deployed.countDown();
        });
        assertTrue(deployed.await(60, TimeUnit.SECONDS), "deploying MainVerticle timed out");
        if (failure.get() != null) {
            fail("deploying MainVerticle failed", failure.get());
        }
    }

    /**
     * A port nothing is listening on. Racy by nature — anything may take it between the close and
     * the bind under test — but the window is small and the alternative, a fixed port, collides
     * with whatever else the machine is running.
     */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** The server binds asynchronously, so give it a moment to appear before concluding. */
    private static boolean waitForConnection(String host, int port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        do {
            if (connectsOnce(host, port)) {
                return true;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    private static boolean connectsOnce(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1_000);
            return true;
        } catch (IOException refusedOrUnreachable) {
            return false;
        }
    }

    /**
     * An IPv4 address of this machine that is not the loopback, if it has one. A CI runner
     * normally does; a machine with no network does not, and the cases needing one are skipped
     * rather than failed.
     */
    private static Optional<String> nonLoopbackAddress() throws Exception {
        List<NetworkInterface> interfaces =
                new ArrayList<>(Collections.list(NetworkInterface.getNetworkInterfaces()));
        for (NetworkInterface networkInterface : interfaces) {
            if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                if (address.getAddress().length == 4 && !address.isLoopbackAddress()) {
                    return Optional.of(address.getHostAddress());
                }
            }
        }
        return Optional.empty();
    }
}
