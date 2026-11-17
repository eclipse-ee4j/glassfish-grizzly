/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.grizzly.http2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.Filter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.server.AddOn;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.server.http2.PushBuilder;
import org.junit.After;
import org.junit.Test;

/**
 * Tests the mitigation of HTTP/2 Rapid Reset (CVE-2023-44487): a client that opens streams and resets them right away
 * must not make the server do more request processing than {@code maxConcurrentStreams} allows.
 */
public class Http2RapidResetTest extends AbstractHttp2Test {

    private static final int PORT = 18907;
    private static final int MAX_CONCURRENT_STREAMS = 2;
    private static final int FLOOD_SIZE = 400;
    private static final int PACED_FLOOD_SIZE = 150;
    private static final int HANDLER_DURATION_MILLIS = 150;
    private static final int RESET_BUDGET = 50;

    private HttpServer httpServer;
    private ExecutorService http2Executor;
    private int maxConcurrentStreams = MAX_CONCURRENT_STREAMS;
    private int resetBudget = RESET_BUDGET;
    private boolean holdSlot = true;
    private boolean singleWorkerThread;

    @After
    public void tearDown() {
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
        if (http2Executor != null) {
            http2Executor.shutdownNow();
        }
    }

    /**
     * The attack as it is usually mounted: the streams are reset before the server has had the time to do anything with
     * them. Most of the requests are dropped from the queue without ever reaching the handler, but whatever does reach it
     * must not exceed the limit, and the connection is told to calm down.
     */
    @Test
    public void testResetFloodDoesNotExceedMaxConcurrentStreams() throws Exception {
        final SlowHandler handler = new SlowHandler();
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            flood(client, true);

            // Let the handlers, which the server has dispatched (or queued), run.
            Thread.sleep(1500);

            assertEnhanceYourCalm(client);
        }

        assertWithinLimit(handler);
    }

    /**
     * The same, but each stream is reset only after its handler has started, so the requests aren't dropped from the queue
     * and it is only the held slots that keep the number of running handlers within the limit.
     */
    @Test
    public void testResetFloodAfterDispatchDoesNotExceedMaxConcurrentStreams() throws Exception {
        final SlowHandler handler = new SlowHandler();
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            floodPaced(client, true, handler);
            Thread.sleep(1500);

            assertEnhanceYourCalm(client);
        }

        assertTrue("service() was never entered", handler.peak.get() > 0);
        assertWithinLimit(handler);
    }

    @Test
    public void testResetFloodAfterDispatchWithoutEndStreamDoesNotExceedMaxConcurrentStreams() throws Exception {
        final SlowHandler handler = new SlowHandler();
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            floodPaced(client, false, handler);
            Thread.sleep(1500);

            assertEnhanceYourCalm(client);
        }

        assertTrue("service() was never entered", handler.peak.get() > 0);
        assertWithinLimit(handler);
    }

    @Test
    public void testResetFloodAfterDispatchWithHttp2ExecutorDoesNotExceedMaxConcurrentStreams() throws Exception {
        final SlowHandler handler = new SlowHandler();
        http2Executor = Executors.newCachedThreadPool();
        startServer(handler, http2Executor, null);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            floodPaced(client, true, handler);
            Thread.sleep(1500);

            assertEnhanceYourCalm(client);
        }

        assertTrue("service() was never entered", handler.peak.get() > 0);
        assertWithinLimit(handler);
    }

    @Test
    public void testSlotIsReleasedWhenHandlerThrows() throws Exception {
        final SlowHandler handler = new SlowHandler();
        handler.fail = true;
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            // Fill all the slots with requests which are being served, and reset them.
            for (int i = 0; i < MAX_CONCURRENT_STREAMS; i++) {
                final int streamId = client.sendRequest(true);
                client.flush();
                assertTrue("the request did not reach the handler", awaitAtLeast(handler.calls, i + 1));
                client.sendRstStream(streamId, RawHttp2Client.ERROR_CANCEL);
                client.flush();
            }
            Thread.sleep(HANDLER_DURATION_MILLIS * 4);

            final int streamId = client.sendRequest(true);
            client.flush();
            assertTrue("a slot of a reset stream, whose handler threw, was not released", client.awaitResponse(streamId, 5000));
        }
    }

    @Test
    public void testSlotIsReleasedWhenRequestIsRejectedBeforeHandler() throws Exception {
        final SlowHandler handler = new SlowHandler();
        final RejectingFilter rejectingFilter = new RejectingFilter();
        http2Executor = Executors.newCachedThreadPool();
        startServer(handler, http2Executor, rejectingFilter);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            // Fill all the slots with requests which the filter is holding, and reset them.
            for (int i = 0; i < MAX_CONCURRENT_STREAMS; i++) {
                final int streamId = client.sendRequest(true);
                client.flush();
                assertTrue("the request did not reach the filter", awaitAtLeast(rejectingFilter.seen, i + 1));
                client.sendRstStream(streamId, RawHttp2Client.ERROR_CANCEL);
                client.flush();
            }
            assertTrue("the filter has not dropped the requests", awaitAtLeast(rejectingFilter.rejected, MAX_CONCURRENT_STREAMS));
            Thread.sleep(100);

            rejectingFilter.reject.set(false);
            final int streamId = client.sendRequest(true);
            client.flush();
            assertTrue("a slot of a reset stream, whose request was rejected before the handler, was not released",
                    client.awaitResponse(streamId, 5000));
        }
    }

    @Test
    public void testClientResetsAloneExhaustTheBudget() throws Exception {
        // No stream is ever refused here, so it is the RST_STREAM frames of the client that are counted.
        maxConcurrentStreams = FLOOD_SIZE * 2;
        startServer(new SlowHandler());

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            flood(client, true);

            assertEnhanceYourCalm(client);
            assertEquals("no stream should have been refused", 0, client.getServerResetCount());
        }
    }

    @Test
    public void testWindowUpdateWithZeroDeltaClosesTheStreamLocally() throws Exception {
        final BlockingHandler handler = new BlockingHandler(1);
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            final int streamId = client.sendRequest(false);
            client.flush();
            assertTrue("the request did not reach the handler", handler.entered.await(5, TimeUnit.SECONDS));
            final Http2Stream stream = handler.stream;
            assertNotNull(stream);

            // RFC 9113, section 6.9: a stream error. The server resets the stream, so the peer takes it for closed.
            client.sendWindowUpdate(streamId, 0);
            client.flush();
            awaitServerReset(client, streamId, RawHttp2Client.ERROR_PROTOCOL_ERROR);

            // The handler is still blocked, the stream must be closed regardless, and no longer be counted as a stream of
            // the session (the closed stream's entry in the session's map is cleaned up lazily, that's how it always was).
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((stream.isOpen() || !stream.isDeregistered) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse("the server reset the stream, but kept it open", stream.isOpen());
            assertTrue("the server reset the stream, but kept it registered", stream.isDeregistered);
        } finally {
            handler.release.countDown();
        }
    }

    @Test
    public void testResetsCausedByProtocolErrorsAreCounted() throws Exception {
        resetBudget = 1;
        startServer(new SlowHandler());

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            final int first = client.sendRequest(false);
            client.sendWindowUpdate(first, 0);
            client.flush();
            awaitServerReset(client, first, RawHttp2Client.ERROR_PROTOCOL_ERROR);
            assertEquals("a single reset is within the budget", -1, client.getGoAwayErrorCode());

            final int second = client.sendRequest(false);
            client.sendWindowUpdate(second, 0);
            client.flush();

            assertEnhanceYourCalm(client);
        }
    }

    @Test
    public void testQueuedRequestsOfResetStreamsNeverReachTheHandler() throws Exception {
        singleWorkerThread = true;
        maxConcurrentStreams = 100;
        final BlockingHandler handler = new BlockingHandler(1);
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            // The only worker thread is busy with this one...
            client.sendRequest(true);
            client.flush();
            assertTrue("the first request did not reach the handler", handler.entered.await(5, TimeUnit.SECONDS));

            // ... so these wait in the queue, and are cancelled there.
            for (int i = 0; i < 20; i++) {
                final int streamId = client.sendRequest(true);
                client.sendRstStream(streamId, RawHttp2Client.ERROR_CANCEL);
            }
            client.flush();
            Thread.sleep(500);

            handler.release.countDown();
            Thread.sleep(1000);
        } finally {
            handler.release.countDown();
        }

        assertEquals("service() was called for requests that had been cancelled while they were queued", 1, handler.calls.get());
    }

    @Test
    public void testQueuedRequestsOfClosedConnectionNeverReachTheHandler() throws Exception {
        singleWorkerThread = true;
        maxConcurrentStreams = 100;
        final BlockingHandler handler = new BlockingHandler(1);
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            client.sendRequest(true);
            client.flush();
            assertTrue("the first request did not reach the handler", handler.entered.await(5, TimeUnit.SECONDS));

            for (int i = 0; i < 20; i++) {
                client.sendRequest(true);
            }
            client.flush();
            Thread.sleep(500);

            // the connection goes away, e.g. closed by the server after it has told the client to calm down
        }
        Thread.sleep(500);
        try {
            handler.release.countDown();
            Thread.sleep(1000);
        } finally {
            handler.release.countDown();
        }

        assertEquals("service() was called for requests of a connection that had been closed while they were queued", 1, handler.calls.get());
    }

    @Test
    public void testRequestsQueuedInHttp2ExecutorAreNotDispatchedAfterReset() throws Exception {
        maxConcurrentStreams = 100;
        http2Executor = Executors.newSingleThreadExecutor();
        final BlockingFilter blockingFilter = new BlockingFilter();
        startServer(new SlowHandler(), http2Executor, blockingFilter);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            client.sendRequest(true);
            client.flush();
            assertTrue("the first request did not reach the filter", blockingFilter.entered.await(5, TimeUnit.SECONDS));

            for (int i = 0; i < 20; i++) {
                final int streamId = client.sendRequest(true);
                client.sendRstStream(streamId, RawHttp2Client.ERROR_CANCEL);
            }
            client.flush();
            Thread.sleep(500);

            blockingFilter.release.countDown();
            Thread.sleep(1000);
        } finally {
            blockingFilter.release.countDown();
        }

        assertEquals("requests that had been cancelled while they were queued were dispatched", 1, blockingFilter.calls.get());
    }

    @Test
    public void testSlotHoldingCanBeSwitchedOff() throws Exception {
        holdSlot = false;
        resetBudget = -1;
        final SlowHandler handler = new SlowHandler();
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            floodPaced(client, true, handler);
            Thread.sleep(1500);
        }

        assertTrue("with the slots not held, the handler concurrency should not be bounded by the limit of " + MAX_CONCURRENT_STREAMS
                + ", the opt-out has no effect (peak " + handler.peak.get() + ")", handler.peak.get() > MAX_CONCURRENT_STREAMS);
    }

    @Test
    public void testResetBudgetCanBeSwitchedOff() throws Exception {
        resetBudget = -1;
        final SlowHandler handler = new SlowHandler();
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            flood(client, true);
            Thread.sleep(1500);

            assertEquals("the connection was told to calm down although the budget is off", -1, client.getGoAwayErrorCode());
            assertFalse(client.isClosedByServer());
        }
        assertTrue("up to " + handler.peak.get() + " service() calls ran at the same time, the limit is " + MAX_CONCURRENT_STREAMS,
                handler.peak.get() <= MAX_CONCURRENT_STREAMS);
    }

    @Test
    public void testRefusedStreamFloodIsAnEnhanceYourCalm() throws Exception {
        final BlockingHandler handler = new BlockingHandler(MAX_CONCURRENT_STREAMS);
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            // Occupy all the slots with requests which are not reset.
            for (int i = 0; i < MAX_CONCURRENT_STREAMS; i++) {
                client.sendRequest(true);
            }
            client.flush();
            assertTrue("the requests did not reach the handler", handler.entered.await(5, TimeUnit.SECONDS));

            // Every one of these is refused: no handler work, but still a header block to decode and a RST_STREAM to write.
            try {
                for (int i = 0; i < FLOOD_SIZE; i++) {
                    client.sendRequest(true);
                }
                client.flush();
            } catch (IOException e) {
                // the server has closed the connection
            }

            assertEnhanceYourCalm(client);
        } finally {
            handler.release.countDown();
        }
    }

    @Test
    public void testCancellationsWithinTheBudgetAreNotPenalized() throws Exception {
        final SlowHandler handler = new SlowHandler();
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            // What a browser does when the user navigates away: a few requests cancelled while they are being served.
            for (int i = 0; i < 5; i++) {
                final int streamId = client.sendRequest(true);
                client.flush();
                Thread.sleep(20);
                client.sendRstStream(streamId, RawHttp2Client.ERROR_CANCEL);
                client.flush();
                Thread.sleep(HANDLER_DURATION_MILLIS + 50);
            }

            assertEquals("the connection was told to calm down", -1, client.getGoAwayErrorCode());
            assertFalse(client.isClosedByServer());

            final int streamId = client.sendRequest(true);
            client.flush();
            assertTrue("a request after the cancellations got no response", client.awaitResponse(streamId, 5000));
            assertEquals(-1, client.getGoAwayErrorCode());
        }
    }

    /**
     * A well-behaved client takes a stream it has reset for closed, and may open a new one in its place right away
     * (RFC 9113, section 5.1.2), while the server is still busy with the request of the reset stream. The new stream must
     * neither be refused, nor charged to the reset budget: its request waits until the server is done with the old one.
     */
    @Test
    public void testStreamsOpenedInPlaceOfResetOnesWaitInsteadOfBeingRefused() throws Exception {
        // the two cancellations fit into the budget, two refusals on top of them would not
        resetBudget = MAX_CONCURRENT_STREAMS + 1;
        final BlockingHandler handler = new BlockingHandler(MAX_CONCURRENT_STREAMS);
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            for (int i = 0; i < MAX_CONCURRENT_STREAMS; i++) {
                client.sendRequest(true);
            }
            client.flush();
            assertTrue("the requests did not reach the handler", handler.entered.await(5, TimeUnit.SECONDS));

            // The user navigates away: both requests are cancelled while they are being served...
            for (int streamId = 1; streamId < 2 * MAX_CONCURRENT_STREAMS; streamId += 2) {
                client.sendRstStream(streamId, RawHttp2Client.ERROR_CANCEL);
            }
            // ... and the next page is requested.
            final int[] next = new int[MAX_CONCURRENT_STREAMS];
            for (int i = 0; i < next.length; i++) {
                next[i] = client.sendRequest(true);
            }
            client.flush();
            Thread.sleep(500);

            assertEquals("a stream opened in place of a reset one was refused", 0, client.getServerResetCount());
            assertEquals("the requests in progress exceeded the limit", MAX_CONCURRENT_STREAMS, handler.calls.get());

            handler.release.countDown();
            for (final int streamId : next) {
                assertTrue("no response for stream " + streamId, client.awaitResponse(streamId, 5000));
            }
            assertEquals("the connection was told to calm down", -1, client.getGoAwayErrorCode());
            assertFalse(client.isClosedByServer());
        } finally {
            handler.release.countDown();
        }
    }

    /**
     * The same with the requests waiting for their turn reset as well: they are dropped without reaching the handler.
     */
    @Test
    public void testWaitingRequestsOfResetStreamsNeverReachTheHandler() throws Exception {
        maxConcurrentStreams = 1;
        final BlockingHandler handler = new BlockingHandler(1);
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            final int first = client.sendRequest(true);
            client.flush();
            assertTrue("the first request did not reach the handler", handler.entered.await(5, TimeUnit.SECONDS));
            client.sendRstStream(first, RawHttp2Client.ERROR_CANCEL);

            final int waiting = client.sendRequest(true);
            client.sendRstStream(waiting, RawHttp2Client.ERROR_CANCEL);
            final int last = client.sendRequest(true);
            client.flush();
            Thread.sleep(300);

            handler.release.countDown();
            assertTrue("no response for the request after the reset ones", client.awaitResponse(last, 5000));
            assertFalse("a response for a stream reset while its request was waiting", client.awaitResponse(waiting, 200));
        } finally {
            handler.release.countDown();
        }

        assertEquals("service() was called for a request reset while it was waiting", 2, handler.calls.get());
    }

    /**
     * Frames for a stream which is already closed (here: DATA after the response has been completed, as a client
     * uploading a body which the server has answered early would send) are answered with RST_STREAM, but they cost the
     * server nothing else and must not get the client disconnected.
     */
    @Test
    public void testFramesForClosedStreamsAreNotCharged() throws Exception {
        resetBudget = 1;
        startServer(new SlowHandler());

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            final int streamId = client.sendRequest(true);
            client.flush();
            assertTrue("no response", client.awaitResponse(streamId, 5000));
            Thread.sleep(200);

            for (int i = 0; i < 10; i++) {
                client.sendData(streamId, new byte[16], false);
            }
            client.flush();
            awaitServerReset(client, streamId, RawHttp2Client.ERROR_STREAM_CLOSED);
            Thread.sleep(300);

            assertEquals("the connection was told to calm down", -1, client.getGoAwayErrorCode());
            assertFalse(client.isClosedByServer());

            final int next = client.sendRequest(true);
            client.flush();
            assertTrue("no response for a request after the stray frames", client.awaitResponse(next, 5000));
        }
    }

    /**
     * A protocol error of the peer, which makes the server close a stream it is still serving, is charged (MadeYouReset,
     * CVE-2025-8671), also when the stream is closed before the RST_STREAM is sent.
     */
    @Test
    public void testDataAfterEndStreamOnStreamBeingServedIsCharged() throws Exception {
        resetBudget = 1;
        final BlockingHandler handler = new BlockingHandler(1);
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            final int first = client.sendRequest(true);
            client.flush();
            assertTrue("the request did not reach the handler", handler.entered.await(5, TimeUnit.SECONDS));
            client.sendData(first, new byte[16], false);
            client.flush();
            awaitServerReset(client, first, RawHttp2Client.ERROR_STREAM_CLOSED);
            assertEquals("a single reset is within the budget", -1, client.getGoAwayErrorCode());

            final int second = client.sendRequest(false);
            client.sendWindowUpdate(second, 0);
            client.flush();

            assertEnhanceYourCalm(client);
        } finally {
            handler.release.countDown();
        }
    }

    /**
     * The request objects are pooled: once the request of a stream has been recycled, it may already belong to another
     * stream, even of another connection. A stream which is closed late, e.g. along with its connection, must not mark
     * it cancelled, or the other stream's request would be dropped without being served.
     */
    @Test
    public void testClosingFinishedStreamDoesNotCancelItsRecycledRequest() throws Exception {
        final BlockingHandler handler = new BlockingHandler(1);
        handler.release.countDown();
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            final int streamId = client.sendRequest(true);
            client.flush();
            assertTrue("no response", client.awaitResponse(streamId, 5000));
        }

        final Http2Stream stream = handler.stream;
        assertNotNull(stream);
        final HttpHeader request = stream.getRequest();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Http2Stream.getStreamFor(request) != null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("the request was not recycled", null, Http2Stream.getStreamFor(request));

        // what the connection close listener, or a GOAWAY, does with every stream still in the session's map
        stream.closedRemotely();

        assertFalse("a stream marked a request cancelled after it had been recycled", request.getProcessingState().isCancelled());
    }

    /**
     * A suspended request (long poll, server-sent events, an async handler) has no thread working on it. When the client
     * resets its stream, the request has to be cancelled, so that the handler learns about it and the request stops
     * counting toward the requests in progress. Otherwise every cancelled long poll would take a slot of the connection
     * for good, and once all of them were taken, new requests would wait forever.
     */
    @Test
    public void testResetOfSuspendedRequestReleasesItsSlot() throws Exception {
        maxConcurrentStreams = 1;
        final SuspendingHandler handler = new SuspendingHandler();
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            final int suspended = client.sendRequest(true);
            client.flush();
            assertTrue("the request was not suspended", handler.suspended.await(5, TimeUnit.SECONDS));

            client.sendRstStream(suspended, RawHttp2Client.ERROR_CANCEL);
            client.flush();
            assertTrue("the suspended request was not cancelled when its stream was reset", handler.cancelled.await(5, TimeUnit.SECONDS));

            final int next = client.sendRequest(true);
            client.flush();
            assertTrue("no response for a request after the reset of a suspended one, its slot was not released", client.awaitResponse(next, 5000));
            assertEquals("the connection was told to calm down", -1, client.getGoAwayErrorCode());
        }
    }

    /**
     * A client which doesn't open its flow-control window gets the response HEADERS, but none of the body. The handler
     * has completed, the body is still waiting in the server's queue: resetting the stream now cancels a response the
     * server has produced, and must be charged like any other cancellation, or the client could keep the handlers busy
     * without ever reading a response and without ever being told to calm down.
     */
    @Test
    public void testResetOfResponseHeldByFlowControlIsCharged() throws Exception {
        resetBudget = 3;
        final FastHandler handler = new FastHandler();
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT, 0)) {
            try {
                for (int i = 0; i < 10 && client.getGoAwayErrorCode() == -1 && !client.isClosedByServer(); i++) {
                    final int streamId = client.sendRequest(true);
                    client.flush();
                    if (!awaitAtLeast(handler.served, i + 1)) {
                        // the GOAWAY is on its way
                        break;
                    }
                    // let the response complete on the server side, its body stays queued
                    Thread.sleep(50);
                    client.sendRstStream(streamId, RawHttp2Client.ERROR_CANCEL);
                    client.flush();
                }
            } catch (IOException e) {
                // the server has closed the connection
            }

            assertEnhanceYourCalm(client);
        }
    }

    /**
     * The peer considers a stream closed as soon as it has reset it, so the server has to as well, even if it has closed
     * the stream on its side already and the end of the response is only waiting for flow-control window, which the peer
     * is never going to open for a reset stream. Otherwise each such stream would take a slot of the concurrent streams
     * limit for good, and the connection would end up refusing every new stream.
     */
    @Test
    public void testResetOfResponseHeldByFlowControlClosesTheStream() throws Exception {
        resetBudget = -1;
        final FastHandler handler = new FastHandler();
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT, 0)) {
            for (int i = 0; i < 3 * MAX_CONCURRENT_STREAMS; i++) {
                final int streamId = client.sendRequest(true);
                client.flush();
                assertTrue("request " + (i + 1) + " did not reach the handler", awaitAtLeast(handler.served, i + 1));
                Thread.sleep(50);
                client.sendRstStream(streamId, RawHttp2Client.ERROR_CANCEL);
                client.flush();
            }
            assertEquals("a stream was refused", 0, client.getServerResetCount());
        }
    }

    /**
     * The other way round: a stream reset after the whole response has been sent costs the server nothing, even if the
     * client's side of the stream is still open.
     */
    @Test
    public void testResetAfterResponseHasBeenSentIsNotCharged() throws Exception {
        resetBudget = 1;
        startServer(new FastHandler());

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            for (int i = 0; i < 5; i++) {
                final int streamId = client.sendRequest(false);
                client.flush();
                assertTrue("no complete response for stream " + streamId, client.awaitComplete(streamId, 5000));
                client.sendRstStream(streamId, RawHttp2Client.ERROR_CANCEL);
                client.flush();
            }
            Thread.sleep(300);

            assertEquals("the connection was told to calm down", -1, client.getGoAwayErrorCode());
            assertFalse(client.isClosedByServer());
        }
    }

    /**
     * Frames for closed streams aren't charged (see {@link #testFramesForClosedStreamsAreNotCharged()}), but answering
     * every one of them with a RST_STREAM would let the client make the server write without limit. Past the budget,
     * such frames are ignored, as RFC 9113 (section 5.4.2) allows, and the connection stays up.
     */
    @Test
    public void testResetsForFramesOnClosedStreamsAreLimited() throws Exception {
        resetBudget = 2;
        startServer(new FastHandler());

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            final int streamId = client.sendRequest(true);
            client.flush();
            assertTrue("no response", client.awaitComplete(streamId, 5000));
            Thread.sleep(200);

            for (int i = 0; i < 50; i++) {
                client.sendData(streamId, new byte[1], false);
            }
            client.flush();
            awaitServerReset(client, streamId, RawHttp2Client.ERROR_STREAM_CLOSED);
            Thread.sleep(500);

            assertTrue("the server sent " + client.getServerResetFrameCount() + " RST_STREAM frames for frames on a closed stream, the budget is "
                    + resetBudget, client.getServerResetFrameCount() <= resetBudget);
            assertEquals("the connection was told to calm down", -1, client.getGoAwayErrorCode());
            assertFalse(client.isClosedByServer());

            final int next = client.sendRequest(true);
            client.flush();
            assertTrue("no response for a request after the stray frames", client.awaitResponse(next, 5000));
        }
    }

    /**
     * Once the connection is told to calm down, a request which is still waiting for its turn must not be passed to the
     * handler any more, neither while the GOAWAY is being written, nor once the connection has been closed.
     */
    @Test
    public void testWaitingRequestsAreDroppedWhenTheConnectionIsToldToCalmDown() throws Exception {
        resetBudget = 1;
        final BlockingHandler handler = new BlockingHandler(MAX_CONCURRENT_STREAMS);
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            final int first = client.sendRequest(true);
            client.sendRequest(true);
            client.flush();
            assertTrue("the requests did not reach the handler", handler.entered.await(5, TimeUnit.SECONDS));

            // The first request goes on counting after its stream has been reset (which uses up the budget), so the next
            // one waits for its turn, its stream open.
            client.sendRstStream(first, RawHttp2Client.ERROR_CANCEL);
            client.sendRequest(true);
            client.flush();
            Thread.sleep(300);
            assertEquals("the connection was told to calm down too early", -1, client.getGoAwayErrorCode());

            // Over the advertised limit: refused, which exceeds the budget.
            client.sendRequest(true);
            client.flush();

            assertEnhanceYourCalm(client);
        } finally {
            handler.release.countDown();
        }

        Thread.sleep(500);
        assertEquals("service() was called for a request which was waiting when the connection was told to calm down", MAX_CONCURRENT_STREAMS,
                handler.calls.get());
    }

    /**
     * Server push is the server's idea: a client cancelling a pushed stream (e.g. because it has the resource cached
     * already) hasn't cost the server anything it didn't choose to spend, and must not be charged.
     */
    @Test
    public void testCancellingPushedStreamsIsNotCharged() throws Exception {
        resetBudget = 1;
        startServer(new PushingHandler());

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            for (int i = 0; i < 3; i++) {
                final int streamId = client.sendRequest(true);
                client.flush();
                final int promised = client.awaitPushPromise(5000);
                assertTrue("no PUSH_PROMISE", promised > 0);
                client.sendRstStream(promised, RawHttp2Client.ERROR_CANCEL);
                client.flush();
                assertTrue("no response for stream " + streamId, client.awaitComplete(streamId, 5000));
            }
            Thread.sleep(300);

            assertEquals("the connection was told to calm down", -1, client.getGoAwayErrorCode());
            assertFalse(client.isClosedByServer());
        }
    }

    /**
     * Streams over the limit are refused and charged to the reset budget, which is only fair if the client has been told
     * about the limit: without SETTINGS_MAX_CONCURRENT_STREAMS, a client may assume there is none (RFC 9113, section
     * 6.5.2), and would be told to calm down for opening a few hundred streams at once. So the server has to advertise
     * the limit even when it is the default one.
     */
    @Test
    public void testDefaultConcurrentStreamsLimitIsAdvertised() throws Exception {
        maxConcurrentStreams = -1;
        startServer(new SlowHandler());

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            final Map<Integer, Long> settings = client.awaitServerSettings(5000);
            assertNotNull("the server did not send SETTINGS", settings);
            assertEquals("SETTINGS_MAX_CONCURRENT_STREAMS", Long.valueOf(100), settings.get(0x3));
        }
    }

    /**
     * Pushed requests are processed by the server like any other request, and the client may decline (reset) pushed
     * streams for free, which frees their slot of the concurrent streams limit right away. Unless the pushed requests
     * count toward the requests in progress, a client which declines every push makes the server process any number of
     * them at the same time.
     */
    @Test
    public void testPushedRequestsCountTowardTheRequestsInProgress() throws Exception {
        final ConcurrencyPushingHandler handler = new ConcurrencyPushingHandler(4);
        startServer(handler);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            final int streamId = client.sendRequest(true);
            client.flush();
            for (int i = 0; i < handler.pushes; i++) {
                final int promised = client.awaitPushPromise(5000);
                assertTrue("no PUSH_PROMISE", promised > 0);
                client.sendRstStream(promised, RawHttp2Client.ERROR_CANCEL);
                client.flush();
            }
            assertTrue("no response for stream " + streamId, client.awaitComplete(streamId, 5000));
            Thread.sleep(HANDLER_DURATION_MILLIS * 2);

            assertTrue("up to " + handler.peak.get() + " service() calls ran at the same time, the limit is " + MAX_CONCURRENT_STREAMS,
                    handler.peak.get() <= MAX_CONCURRENT_STREAMS);
            assertEquals("the connection was told to calm down", -1, client.getGoAwayErrorCode());
        }
    }

    /**
     * Pushed streams count toward the client's SETTINGS_MAX_CONCURRENT_STREAMS, not the server's (RFC 9113, section
     * 5.1.2). A client which never has more of its own streams open than the server has advertised must not be refused,
     * let alone charged and told to calm down, because the server has pushed streams of its own.
     */
    @Test
    public void testPushedStreamsDoNotCountTowardTheClientsConcurrentStreams() throws Exception {
        resetBudget = 1;
        startServer(new SlowPushingHandler());

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            final int first = client.sendRequest(true);
            client.flush();
            assertTrue("no PUSH_PROMISE", client.awaitPushPromise(5000) > 0);

            // MAX_CONCURRENT_STREAMS of the client's own streams are open from now on, while the pushed stream is too
            final int second = client.sendRequest(true);
            client.flush();
            assertTrue("no response for stream " + first, client.awaitComplete(first, 5000));
            assertTrue("no response for stream " + second, client.awaitComplete(second, 5000));

            assertEquals("the server reset a stream within the limit", 0, client.getServerResetCount());
            assertEquals("the connection was told to calm down", -1, client.getGoAwayErrorCode());
        }
    }

    /**
     * A pushed request takes a slot of the requests in progress before it is handed to the thread pool. If the pool
     * rejects it, nothing is ever going to process it, so the slot has to be released (and the promised stream reset),
     * otherwise every rejected push lowers the concurrency of the connection for good.
     */
    @Test
    public void testRejectedPushReleasesItsSlot() throws Exception {
        maxConcurrentStreams = 3;
        // execute() (requests of the client) works, submit() (pushed requests) is rejected
        http2Executor = new ThreadPoolExecutor(4, 4, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<Runnable>()) {
            @Override
            public Future<?> submit(final Runnable task) {
                throw new RejectedExecutionException("saturated");
            }
        };
        final PushOnceThenConcurrencyHandler handler = new PushOnceThenConcurrencyHandler(2);
        startServer(handler, http2Executor, null);

        try (RawHttp2Client client = new RawHttp2Client(PORT)) {
            for (int i = 0; i < handler.pushes; i++) {
                final int streamId = client.sendRequest(true);
                client.flush();
                final int promised = client.awaitPushPromise(5000);
                assertTrue("no PUSH_PROMISE", promised > 0);
                assertTrue("no response for stream " + streamId, client.awaitComplete(streamId, 5000));
                awaitServerReset(client, promised, RawHttp2Client.ERROR_REFUSED_STREAM);
            }

            // nothing is in progress any more, so two requests run at the same time
            final int first = client.sendRequest(true);
            final int second = client.sendRequest(true);
            client.flush();
            assertTrue("no response for stream " + first, client.awaitComplete(first, 5000));
            assertTrue("no response for stream " + second, client.awaitComplete(second, 5000));

            assertEquals("service() calls which ran at the same time", 2, handler.peak.get());
            assertEquals("the connection was told to calm down", -1, client.getGoAwayErrorCode());
        }
    }

    // -------------------------------------------------------- Private Methods

    private void startServer(final HttpHandler handler) throws Exception {
        startServer(handler, null, null);
    }

    /**
     * @param http2Executor the executor for the HTTP/2 filter to dispatch requests to, or {@code null} for the default
     * configuration, where the requests are dispatched on the connection's read thread
     * @param filterBeforeHandler a filter to be put between the HTTP/2 filter and the HTTP server filter, may be
     * {@code null}
     */
    private void startServer(final HttpHandler handler, final ExecutorService http2Executor, final Filter filterBeforeHandler) throws Exception {
        httpServer = HttpServer.createSimpleServer(null, PORT);
        final NetworkListener listener = httpServer.getListener("grizzly");
        listener.setSendFileEnabled(false);
        listener.getKeepAlive().setIdleTimeoutInSeconds(-1);
        if (singleWorkerThread) {
            // Requests wait in the queue of the worker pool for as long as its only thread is busy.
            listener.getTransport().getWorkerThreadPoolConfig().setCorePoolSize(1).setMaxPoolSize(1);
        }

        final Http2Configuration.Http2ConfigurationBuilder configuration = Http2Configuration.builder().disableCipherCheck(true)
                .maxConcurrentStreams(maxConcurrentStreams).maxResetsPerWindow(resetBudget).resetWindowMillis(60000)
                .holdSlotUntilProcessingComplete(holdSlot);
        if (http2Executor != null) {
            configuration.executorService(http2Executor);
        }
        http2Addon = new Http2AddOn(configuration.build());
        listener.registerAddOn(http2Addon);

        if (filterBeforeHandler != null) {
            listener.registerAddOn(new AddOn() {
                @Override
                public void setup(final NetworkListener networkListener, final FilterChainBuilder builder) {
                    builder.add(builder.indexOfType(org.glassfish.grizzly.http.server.HttpServerFilter.class), filterBeforeHandler);
                }
            });
        }

        httpServer.getServerConfiguration().addHttpHandler(handler, "/");
        httpServer.start();
    }

    private static void assertWithinLimit(final SlowHandler handler) {
        assertTrue("up to " + handler.peak.get() + " service() calls ran at the same time, the limit is " + MAX_CONCURRENT_STREAMS,
                handler.peak.get() <= MAX_CONCURRENT_STREAMS);
    }

    private static boolean awaitAtLeast(final AtomicInteger counter, final int expected) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (counter.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return counter.get() >= expected;
    }

    /**
     * Like {@link #flood}, but every stream is reset only after the handler has started serving its request, or after
     * a moment if it doesn't (the stream has been refused). Stops early if the server closes the connection.
     */
    private static void floodPaced(final RawHttp2Client client, final boolean endStream, final SlowHandler handler) throws InterruptedException {
        try {
            for (int i = 0; i < PACED_FLOOD_SIZE && !client.isClosedByServer() && client.getGoAwayErrorCode() == -1; i++) {
                final int callsBefore = handler.calls.get();
                final int streamId = client.sendRequest(endStream);
                client.flush();
                final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25);
                while (handler.calls.get() == callsBefore && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                client.sendRstStream(streamId, RawHttp2Client.ERROR_CANCEL);
                client.flush();
            }
        } catch (IOException e) {
            // the server has closed the connection
        }
    }

    private static void awaitServerReset(final RawHttp2Client client, final int streamId, final int errorCode) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (client.getServerResetCode(streamId) == -1 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("RST_STREAM error code the server sent for stream " + streamId, errorCode, client.getServerResetCode(streamId));
    }

    private static void assertEnhanceYourCalm(final RawHttp2Client client) throws InterruptedException {
        assertTrue("the server did not send GOAWAY", client.awaitGoAway(5000));
        assertEquals("GOAWAY error code", RawHttp2Client.ERROR_ENHANCE_YOUR_CALM, client.getGoAwayErrorCode());
        assertTrue("the server did not close the connection", client.awaitClosed(5000));
    }

    /**
     * Opens {@link #FLOOD_SIZE} streams, each one followed by RST_STREAM right away. Stops early if the server closes
     * the connection.
     */
    private static void flood(final RawHttp2Client client, final boolean endStream) {
        try {
            for (int i = 0; i < FLOOD_SIZE; i++) {
                final int streamId = client.sendRequest(endStream);
                client.sendRstStream(streamId, RawHttp2Client.ERROR_CANCEL);
            }
            client.flush();
        } catch (IOException e) {
            // the server has closed the connection
        }
    }

    /**
     * Pushes {@code /pushed} along with each of the first {@code pushes} responses; takes a while over every later
     * response, recording how many service() calls run at the same time.
     */
    private static final class PushOnceThenConcurrencyHandler extends HttpHandler {
        final int pushes;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger current = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();

        PushOnceThenConcurrencyHandler(final int pushes) {
            this.pushes = pushes;
        }

        @Override
        public void service(final Request request, final Response response) throws Exception {
            if (calls.incrementAndGet() <= pushes) {
                request.newPushBuilder().path("/pushed").push();
            } else {
                peak.accumulateAndGet(current.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(500);
                } finally {
                    current.decrementAndGet();
                }
            }
            response.setContentType("text/plain");
            response.getWriter().write("ok");
        }
    }

    /**
     * Suspends the first request without a timeout, as a long poll would, and answers the others right away.
     */
    private static final class SuspendingHandler extends HttpHandler {
        final CountDownLatch suspended = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        private final AtomicBoolean first = new AtomicBoolean(true);

        @Override
        public void service(final Request request, final Response response) throws Exception {
            if (first.compareAndSet(true, false)) {
                response.suspend(0, TimeUnit.MILLISECONDS, new EmptyCompletionHandler<Response>() {
                    @Override
                    public void cancelled() {
                        cancelled.countDown();
                    }
                });
                suspended.countDown();
                return;
            }
            response.setContentType("text/plain");
            response.getWriter().write("ok");
        }
    }

    /**
     * Answers right away with a body which doesn't fit into a zero flow-control window.
     */
    private static final class FastHandler extends HttpHandler {
        final AtomicInteger served = new AtomicInteger();

        @Override
        public void service(final Request request, final Response response) throws Exception {
            try {
                response.setContentType("text/plain");
                response.getWriter().write("a response body");
            } finally {
                served.incrementAndGet();
            }
        }
    }

    /**
     * Pushes {@code /pushed} along with the response to any other path. The pushed response takes a while, so that the
     * client can cancel it before it is complete.
     */
    private static final class PushingHandler extends HttpHandler {

        @Override
        public void service(final Request request, final Response response) throws Exception {
            if ("/pushed".equals(request.getRequestURI())) {
                Thread.sleep(300);
            } else {
                final PushBuilder pushBuilder = request.newPushBuilder();
                if (pushBuilder == null) {
                    throw new IllegalStateException("push is not enabled");
                }
                pushBuilder.path("/pushed").push();
            }
            response.setContentType("text/plain");
            response.getWriter().write("ok");
        }
    }

    /**
     * Pushes {@code /pushed} along with the response to any other path, and takes a while over every response, pushed
     * or not, so that the pushed stream and the client's streams are open at the same time.
     */
    private static final class SlowPushingHandler extends HttpHandler {

        @Override
        public void service(final Request request, final Response response) throws Exception {
            if (!"/pushed".equals(request.getRequestURI())) {
                final PushBuilder pushBuilder = request.newPushBuilder();
                if (pushBuilder == null) {
                    throw new IllegalStateException("push is not enabled");
                }
                pushBuilder.path("/pushed").push();
            }
            Thread.sleep(500);
            response.setContentType("text/plain");
            response.getWriter().write("ok");
        }
    }

    /**
     * Pushes {@code /pushed} a number of times, a little apart, along with the response to any other path, and records
     * how many service() calls, pushed or not, run at the same time.
     */
    private static final class ConcurrencyPushingHandler extends HttpHandler {
        final int pushes;
        final AtomicInteger current = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();

        ConcurrencyPushingHandler(final int pushes) {
            this.pushes = pushes;
        }

        @Override
        public void service(final Request request, final Response response) throws Exception {
            peak.accumulateAndGet(current.incrementAndGet(), Math::max);
            try {
                if (!"/pushed".equals(request.getRequestURI())) {
                    for (int i = 0; i < pushes; i++) {
                        final PushBuilder pushBuilder = request.newPushBuilder();
                        if (pushBuilder == null) {
                            throw new IllegalStateException("push is not enabled");
                        }
                        pushBuilder.path("/pushed").push();
                        // gives the client the time to decline the push, which frees the stream's slot
                        Thread.sleep(50);
                    }
                }
                Thread.sleep(HANDLER_DURATION_MILLIS * 2);
                response.setContentType("text/plain");
                response.getWriter().write("ok");
            } finally {
                current.decrementAndGet();
            }
        }
    }

    private static final class BlockingHandler extends HttpHandler {
        final CountDownLatch entered;
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        volatile Http2Stream stream;

        BlockingHandler(final int expectedRequests) {
            entered = new CountDownLatch(expectedRequests);
        }

        @Override
        public void service(final Request request, final Response response) throws Exception {
            calls.incrementAndGet();
            stream = (Http2Stream) request.getAttribute(Http2Stream.HTTP2_STREAM_ATTRIBUTE);
            entered.countDown();
            release.await(30, TimeUnit.SECONDS);
            response.setContentType("text/plain");
            response.getWriter().write("ok");
        }
    }

    private static final class SlowHandler extends HttpHandler {
        final AtomicInteger current = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean fail;

        @Override
        public void service(final Request request, final Response response) throws Exception {
            calls.incrementAndGet();
            peak.accumulateAndGet(current.incrementAndGet(), Math::max);
            try {
                Thread.sleep(HANDLER_DURATION_MILLIS);
                if (fail) {
                    throw new IllegalStateException("handler failure expected by the test");
                }
                response.setContentType("text/plain");
                response.getWriter().write("ok");
            } finally {
                current.decrementAndGet();
            }
        }
    }

    /**
     * Holds each request for a while like a slow filter would, then drops it without a response: the request is
     * recycled and never reaches the handler.
     */
    private static final class RejectingFilter extends BaseFilter {
        final AtomicBoolean reject = new AtomicBoolean(true);
        final AtomicInteger seen = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();

        @Override
        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
            if (!reject.get()) {
                return ctx.getInvokeAction();
            }
            seen.incrementAndGet();
            final HttpContent content = ctx.getMessage();
            final HttpHeader request = content.getHttpHeader();
            // The request is recycled only after the filter chain has returned, as whoever drops it would do.
            final Thread dropper = new Thread(() -> {
                try {
                    Thread.sleep(HANDLER_DURATION_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                request.recycle();
                rejected.incrementAndGet();
            });
            dropper.setDaemon(true);
            dropper.start();
            return ctx.getStopAction();
        }
    }

    /**
     * Blocks the first request, and with it the only thread of the executor the HTTP/2 filter dispatches to.
     */
    private static final class BlockingFilter extends BaseFilter {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ctx.getInvokeAction();
        }
    }
}
