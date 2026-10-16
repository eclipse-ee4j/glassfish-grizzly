/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly.ssl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.CompositeBuffer;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.threadpool.VirtualThreadExecutorService;
import org.junit.Test;

/**
 * Application data of {@link SSLEngine}s outside the JDK reaches the engine in one direct source and one direct
 * destination, see {@link DirectEngineBuffers}.
 */
public class DirectEngineBuffersTest {

    private static final byte[] PREFIX = "<".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SUFFIX = ">\n".getBytes(StandardCharsets.US_ASCII);

    @Test
    public void appDataReachesTheEngineInOneDirectSourceAndDestination() throws Exception {
        final List<String> calls = new CopyOnWriteArrayList<>();
        exchange(calls, false, socket -> {
            for (int i = 0; i < 3; i++) {
                final byte[] message = ("hello " + i + "\n").getBytes(StandardCharsets.US_ASCII);
                write(socket, message);
                assertArrayEquals(framed(message), socket.getInputStream().readNBytes(framed(message).length));
            }
        });
        assertTrue("wraps: " + calls, calls.contains("wrap sources=1 direct=true destination=direct"));
        assertTrue("unwraps: " + calls, calls.contains("unwrap source=direct destination=direct"));
        for (final String call : calls) {
            assertTrue(calls.toString(), call.equals("wrap sources=1 direct=true destination=direct")
                    || call.equals("unwrap source=direct destination=direct"));
        }
    }

    /** A response of several records, built of odd-sized parts, goes out complete and in order. */
    @Test
    public void largeCompositeWritesAreWrappedCompletely() throws Exception {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final byte[] message = new byte[100_000];
        for (int i = 0; i < message.length; i++) {
            message[i] = (byte) (i * 31 + i / 997);
            if (message[i] == '\n') {
                message[i] = 0;
            }
        }
        message[message.length - 1] = '\n';
        exchange(calls, false, socket -> {
            write(socket, message);
            assertArrayEquals(framed(message), readFully(socket.getInputStream(), framed(message).length));
        });
        int wraps = 0;
        for (final String call : calls) {
            if (call.startsWith("wrap")) {
                wraps++;
                assertEquals(calls.toString(), "wrap sources=1 direct=true destination=direct", call);
            }
        }
        assertTrue("records: " + wraps, wraps >= 7);
    }

    /** A virtual thread keeps no staging area: the engine gets the heap buffers it got before. */
    @Test
    public void virtualThreadsPassTheirOwnBuffers() throws Exception {
        final List<String> calls = new CopyOnWriteArrayList<>();
        exchange(calls, true, socket -> {
            final byte[] message = "hello\n".getBytes(StandardCharsets.US_ASCII);
            write(socket, message);
            assertArrayEquals(framed(message), socket.getInputStream().readNBytes(framed(message).length));
        });
        assertTrue("wraps: " + calls, calls.contains("wrap sources=3 direct=false destination=heap"));
        assertTrue("unwraps: " + calls, calls.contains("unwrap source=heap destination=heap"));
    }

    @Test
    public void onlyEnginesOutsideTheJdkAreStaged() throws Exception {
        final SSLEngine jdkEngine = SSLContext.getDefault().createSSLEngine();
        final SSLConnectionContext jdk = new SSLConnectionContext(null);
        jdk.configure(jdkEngine);
        assertFalse(jdk.isStagingInDirectBuffers());

        final SSLConnectionContext other = new SSLConnectionContext(null);
        other.configure(new RecordingSSLEngine(jdkEngine, new ArrayList<>()));
        assertTrue(other.isStagingInDirectBuffers());
    }

    @Test
    public void theStagingAreaIsNotSharedByNestedCalls() {
        final ByteBuffer[] area = DirectEngineBuffers.acquire();
        assertNotNull(area);
        try {
            assertNull(DirectEngineBuffers.acquire());
        } finally {
            DirectEngineBuffers.release(area);
        }
        final ByteBuffer[] again = DirectEngineBuffers.acquire();
        assertSame(area, again);
        DirectEngineBuffers.release(again);
    }

    /** The sources are advanced by what the engine consumed of the gathered buffer, not by what was gathered. */
    @Test
    public void wrapAdvancesTheSourcesByTheConsumedBytes() throws Exception {
        final CopyingSSLEngine engine = new CopyingSSLEngine();
        final ByteBuffer a = ByteBuffer.wrap(bytes(0, 5));
        final ByteBuffer empty = ByteBuffer.allocate(0);
        final ByteBuffer b = ByteBuffer.wrap(bytes(5, 7));
        final ByteBuffer[] srcs = { a, empty, b };
        final ByteBuffer dst = ByteBuffer.allocate(8);

        final SSLEngineResult result = staged(area -> DirectEngineBuffers.wrap(area, engine, srcs, srcs.length, 10, dst));

        assertEquals("gathered", 10, engine.lastSourceLength);
        assertTrue(engine.lastSourceDirect);
        assertEquals(8, result.bytesConsumed());
        assertEquals(5, a.position());
        assertEquals(3, b.position());
        assertArrayEquals(bytes(0, 8), dst.flip().array());
    }

    /** An engine reporting no application buffer size still gets all the plaintext (up to the bound). */
    @Test
    public void wrapWithoutAPlaintextLimitGathersEverything() throws Exception {
        final CopyingSSLEngine engine = new CopyingSSLEngine();
        final ByteBuffer[] srcs = { ByteBuffer.wrap(bytes(0, 5)), ByteBuffer.wrap(bytes(5, 7)) };
        staged(area -> DirectEngineBuffers.wrap(area, engine, srcs, srcs.length, 0, ByteBuffer.allocate(20)));
        assertEquals(12, engine.lastSourceLength);
    }

    @Test
    public void aSingleDirectSourceIsPassedAsItIs() throws Exception {
        final CopyingSSLEngine engine = new CopyingSSLEngine();
        final ByteBuffer src = ByteBuffer.allocateDirect(10);
        staged(area -> DirectEngineBuffers.wrap(area, engine, new ByteBuffer[] { src }, 1, 100, ByteBuffer.allocate(20)));
        assertSame(src, engine.lastSource);
        assertEquals(10, src.position());
    }

    @Test
    public void unwrapAdvancesSourceAndDestination() throws Exception {
        final CopyingSSLEngine engine = new CopyingSSLEngine();
        final ByteBuffer src = ByteBuffer.wrap(bytes(0, 12)).position(2);
        final ByteBuffer dst = ByteBuffer.allocate(20).position(4);
        dst.limit(10);

        assertEquals(6, staged(area -> DirectEngineBuffers.unwrap(area, engine, src, dst)).bytesProduced());

        assertTrue(engine.lastSourceDirect);
        assertTrue(engine.lastDestinationDirect);
        assertEquals(8, src.position());
        assertEquals(10, dst.position());
        final byte[] produced = new byte[6];
        dst.get(4, produced);
        assertArrayEquals(bytes(2, 6), produced);
    }

    /** What an engine reports as produced is copied back even if it wrote without moving the buffer position. */
    @Test
    public void reportedBytesAreCopiedBack() throws Exception {
        final CopyingSSLEngine engine = new CopyingSSLEngine();
        engine.absolutePuts = true;
        final ByteBuffer dst = ByteBuffer.allocate(10);

        staged(area -> DirectEngineBuffers.unwrap(area, engine, ByteBuffer.wrap(bytes(1, 6)), dst));

        final byte[] produced = new byte[6];
        dst.get(0, produced);
        assertArrayEquals(bytes(1, 6), produced);
    }

    /** No staging buffer grows above the bound: larger buffers go to the engine as they are, a source is capped. */
    @Test
    public void stagingBuffersAreBounded() throws Exception {
        final CopyingSSLEngine engine = new CopyingSSLEngine();
        final int above = DirectEngineBuffers.MAX_STAGED_BYTES + 1;

        staged(area -> DirectEngineBuffers.unwrap(area, engine, ByteBuffer.allocate(above), ByteBuffer.allocate(above)));
        assertFalse("large source staged", engine.lastSourceDirect);
        assertFalse("large destination staged", engine.lastDestinationDirect);
        assertEquals(above, engine.lastDestinationRemaining);

        staged(area -> DirectEngineBuffers.wrap(area, engine, new ByteBuffer[] { ByteBuffer.allocate(above) }, 1, above,
                ByteBuffer.allocate(above)));
        assertEquals(DirectEngineBuffers.MAX_STAGED_BYTES, engine.lastSourceLength);
        assertTrue(engine.lastSourceDirect);
        assertFalse("large destination staged", engine.lastDestinationDirect);
    }

    /** Every call gives the area back, also when the engine fails, so the next call on the thread is staged too. */
    @Test
    public void everyCallReleasesTheStagingArea() throws Exception {
        final CopyingSSLEngine engine = new CopyingSSLEngine();
        final SSLConnectionContext sslCtx = new SSLConnectionContext(null);
        sslCtx.configure(engine);
        assertTrue(sslCtx.isStagingInDirectBuffers());

        for (int i = 0; i < 2; i++) {
            engine.lastSourceDirect = false;
            final Buffer output = sslCtx.wrapAll(Buffers.wrap(MM, bytes(0, 100)), (ctx, old, size) -> Buffers.wrap(MM, new byte[size]));
            assertEquals(100, output.remaining());
            assertTrue("wrap " + i + " staged", engine.lastSourceDirect);
        }
        for (int i = 0; i < 2; i++) {
            engine.lastSourceDirect = false;
            final Buffer input = Buffers.wrap(MM, bytes(0, 100));
            assertFalse(sslCtx.unwrap(100, input, Buffers.wrap(MM, new byte[OUTPUT_SIZE]), null).isError());
            assertTrue("unwrap " + i + " staged", engine.lastSourceDirect);
        }

        engine.fail = true;
        assertTrue(sslCtx.unwrap(100, Buffers.wrap(MM, bytes(0, 100)), Buffers.wrap(MM, new byte[OUTPUT_SIZE]), null).isError());
        engine.fail = false;
        final ByteBuffer[] area = DirectEngineBuffers.acquire();
        assertNotNull("area kept by a failed unwrap", area);
        DirectEngineBuffers.release(area);
    }

    /** A composite destination goes to the engine as it is. */
    @Test
    public void compositeOutputIsNotStaged() throws Exception {
        final CopyingSSLEngine engine = new CopyingSSLEngine();
        final SSLConnectionContext sslCtx = new SSLConnectionContext(null);
        sslCtx.configure(engine);
        final Buffer output = CompositeBuffer.newBuffer(MM, Buffers.wrap(MM, new byte[OUTPUT_SIZE / 2]),
                Buffers.wrap(MM, new byte[OUTPUT_SIZE / 2]));

        assertFalse(sslCtx.unwrap(100, Buffers.wrap(MM, bytes(0, 100)), output, null).isError());
        assertFalse(engine.lastDestinationDirect);
        assertEquals(100, output.position());
    }

    @Test
    public void stagingCanBeSwitchedOff() throws Exception {
        final boolean before = SSLConnectionContext.directEngineBuffers;
        SSLConnectionContext.directEngineBuffers = false;
        try {
            final SSLConnectionContext sslCtx = new SSLConnectionContext(null);
            sslCtx.configure(new CopyingSSLEngine());
            assertFalse(sslCtx.isStagingInDirectBuffers());
        } finally {
            SSLConnectionContext.directEngineBuffers = before;
        }
    }

    private static final MemoryManager MM = MemoryManager.DEFAULT_MEMORY_MANAGER;

    /** Above what the context asks for (1.5 times the application buffer size), so no allocator is needed. */
    private static final int OUTPUT_SIZE = 64 * 1024;

    private interface StagedCall {
        SSLEngineResult run(ByteBuffer[] area) throws SSLException;
    }

    private static SSLEngineResult staged(final StagedCall call) throws SSLException {
        final ByteBuffer[] area = DirectEngineBuffers.acquire();
        assertNotNull(area);
        try {
            final SSLEngineResult result = call.run(area);
            assertNotNull("staged", result);
            return result;
        } finally {
            DirectEngineBuffers.release(area);
        }
    }

    private static byte[] bytes(final int from, final int length) {
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (from + i);
        }
        return bytes;
    }

    private static byte[] framed(final byte[] message) {
        final byte[] framed = new byte[PREFIX.length + message.length + SUFFIX.length];
        System.arraycopy(PREFIX, 0, framed, 0, PREFIX.length);
        System.arraycopy(message, 0, framed, PREFIX.length, message.length);
        System.arraycopy(SUFFIX, 0, framed, PREFIX.length + message.length, SUFFIX.length);
        return framed;
    }

    private static void write(final SSLSocket socket, final byte[] message) throws IOException {
        final OutputStream out = socket.getOutputStream();
        out.write(message);
        out.flush();
    }

    private static byte[] readFully(final InputStream in, final int length) throws IOException {
        final byte[] bytes = in.readNBytes(length);
        assertEquals("bytes read", length, bytes.length);
        return bytes;
    }

    private interface Client {
        void run(SSLSocket socket) throws Exception;
    }

    /**
     * Runs the client against a server that answers every message, which ends with its only '\n', with a composite
     * buffer of three parts: a prefix, the message and a suffix.
     */
    private void exchange(final List<String> calls, final boolean virtualThreads, final Client client) throws Exception {
        final SSLContextConfigurator config = new SSLContextConfigurator();
        final URL keystore = getClass().getClassLoader().getResource("ssltest-keystore.jks");
        config.setKeyStoreFile(keystore.getFile());
        config.setKeyStorePass("changeit");
        final SSLContext serverContext = config.createSSLContext(true);
        final SSLEngineConfigurator serverEngines = new SSLEngineConfigurator(serverContext, false, false, false) {
            @Override
            public SSLEngine createSSLEngine(final String peerHost, final int peerPort) {
                return new RecordingSSLEngine(super.createSSLEngine(peerHost, peerPort), calls);
            }
        };

        final TCPNIOTransportBuilder builder = TCPNIOTransportBuilder.newInstance();
        if (virtualThreads) {
            builder.setIOStrategy(WorkerThreadIOStrategy.getInstance());
        }
        final TCPNIOTransport transport = builder.build();
        ExecutorService virtualWorkers = null;
        if (virtualThreads) {
            virtualWorkers = VirtualThreadExecutorService.createInstance();
            transport.setWorkerThreadPool(virtualWorkers);
        }
        transport.setProcessor(FilterChainBuilder.stateless().add(new TransportFilter()).add(new SSLFilter(serverEngines, null))
                .add(new FramingFilter()).build());
        try {
            final int port = ((InetSocketAddress) transport.bind(new InetSocketAddress("127.0.0.1", 0)).getLocalAddress()).getPort();
            transport.start();
            for (final String protocol : new String[] { "TLSv1.3", "TLSv1.2" }) {
                final SSLContext clientContext = SSLContext.getInstance("TLS");
                clientContext.init(null, new TrustManager[] { TRUST_ALL }, null);
                try (SSLSocket socket = (SSLSocket) clientContext.getSocketFactory().createSocket("127.0.0.1", port)) {
                    socket.setSoTimeout(10_000);
                    socket.setEnabledProtocols(new String[] { protocol });
                    client.run(socket);
                }
            }
        } finally {
            transport.shutdownNow();
            if (virtualWorkers != null) {
                virtualWorkers.shutdownNow();
            }
        }
    }

    /** Collects a message up to its only '\n', its last byte, and answers it framed, in a composite of 3 buffers. */
    private static final class FramingFilter extends BaseFilter {
        private final Attribute<Buffer> pending = Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(FramingFilter.class.getName() + ".pending");

        @Override
        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
            final MemoryManager mm = ctx.getMemoryManager();
            Buffer message = ctx.getMessage();
            final Buffer previous = pending.remove(ctx.getConnection());
            if (previous != null) {
                message = Buffers.appendBuffers(mm, previous, message);
            }
            if (message.get(message.limit() - 1) != '\n') {
                pending.set(ctx.getConnection(), message);
                return ctx.getStopAction();
            }
            final byte[] bytes = new byte[message.remaining()];
            message.get(bytes);
            message.tryDispose();
            final CompositeBuffer response = CompositeBuffer.newBuffer(mm, Buffers.wrap(mm, PREFIX), Buffers.wrap(mm, bytes),
                    Buffers.wrap(mm, SUFFIX));
            ctx.write(response);
            return ctx.getStopAction();
        }
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    /** Records the buffers application data is wrapped from and unwrapped into. */
    private static final class RecordingSSLEngine extends ForwardingSSLEngine {
        private final List<String> calls;

        RecordingSSLEngine(final SSLEngine delegate, final List<String> calls) {
            super(delegate);
            this.calls = calls;
        }

        @Override
        public SSLEngineResult wrap(final ByteBuffer[] srcs, final int offset, final int length, final ByteBuffer dst) throws SSLException {
            int sources = 0;
            boolean direct = true;
            for (int i = offset; i < offset + length; i++) {
                if (srcs[i].hasRemaining()) {
                    sources++;
                    direct &= srcs[i].isDirect();
                }
            }
            if (sources > 0 && delegate.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING) {
                calls.add("wrap sources=" + sources + " direct=" + direct + " destination=" + (dst.isDirect() ? "direct" : "heap"));
            }
            return delegate.wrap(srcs, offset, length, dst);
        }

        @Override
        public SSLEngineResult unwrap(final ByteBuffer src, final ByteBuffer[] dsts, final int offset, final int length) throws SSLException {
            final boolean appData = delegate.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING;
            final SSLEngineResult result = delegate.unwrap(src, dsts, offset, length);
            if (appData && result.bytesProduced() > 0) {
                calls.add("unwrap source=" + (src.isDirect() ? "direct" : "heap") + " destination=" + (dsts[offset].isDirect() ? "direct" : "heap"));
            }
            return result;
        }
    }

    /** Copies plaintext as it is, recording the buffers it was given. */
    private static final class CopyingSSLEngine extends ForwardingSSLEngine {
        ByteBuffer lastSource;
        int lastSourceLength;
        boolean lastSourceDirect;
        boolean lastDestinationDirect;
        int lastDestinationRemaining;
        /** Writes with absolute puts, leaving the destination position where it was. */
        boolean absolutePuts;
        boolean fail;

        CopyingSSLEngine() throws Exception {
            super(SSLContext.getDefault().createSSLEngine());
        }

        @Override
        public HandshakeStatus getHandshakeStatus() {
            return HandshakeStatus.NOT_HANDSHAKING;
        }

        @Override
        public SSLEngineResult wrap(final ByteBuffer[] srcs, final int offset, final int length, final ByteBuffer dst) throws SSLException {
            assertEquals("one source", 1, length);
            return copy(srcs[offset], dst);
        }

        @Override
        public SSLEngineResult unwrap(final ByteBuffer src, final ByteBuffer[] dsts, final int offset, final int length) throws SSLException {
            return copy(src, dsts[offset]);
        }

        private SSLEngineResult copy(final ByteBuffer src, final ByteBuffer dst) throws SSLException {
            lastSource = src;
            lastSourceLength = src.remaining();
            lastSourceDirect = src.isDirect();
            lastDestinationDirect = dst.isDirect();
            lastDestinationRemaining = dst.remaining();
            if (fail) {
                throw new SSLException("failing on purpose");
            }
            final int n = Math.min(src.remaining(), dst.remaining());
            if (absolutePuts) {
                dst.put(dst.position(), src, src.position(), n);
            } else {
                dst.put(src.slice().limit(n));
            }
            src.position(src.position() + n);
            return new SSLEngineResult(Status.OK, HandshakeStatus.NOT_HANDSHAKING, n, n);
        }
    }

    private static class ForwardingSSLEngine extends SSLEngine {
        final SSLEngine delegate;

        ForwardingSSLEngine(final SSLEngine delegate) {
            this.delegate = delegate;
        }

        @Override
        public SSLEngineResult wrap(final ByteBuffer[] srcs, final int offset, final int length, final ByteBuffer dst) throws SSLException {
            return delegate.wrap(srcs, offset, length, dst);
        }

        @Override
        public SSLEngineResult unwrap(final ByteBuffer src, final ByteBuffer[] dsts, final int offset, final int length) throws SSLException {
            return delegate.unwrap(src, dsts, offset, length);
        }

        @Override
        public HandshakeStatus getHandshakeStatus() {
            return delegate.getHandshakeStatus();
        }

        @Override
        public Runnable getDelegatedTask() {
            return delegate.getDelegatedTask();
        }

        @Override
        public void closeInbound() throws SSLException {
            delegate.closeInbound();
        }

        @Override
        public boolean isInboundDone() {
            return delegate.isInboundDone();
        }

        @Override
        public void closeOutbound() {
            delegate.closeOutbound();
        }

        @Override
        public boolean isOutboundDone() {
            return delegate.isOutboundDone();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return delegate.getEnabledCipherSuites();
        }

        @Override
        public void setEnabledCipherSuites(final String[] suites) {
            delegate.setEnabledCipherSuites(suites);
        }

        @Override
        public String[] getSupportedProtocols() {
            return delegate.getSupportedProtocols();
        }

        @Override
        public String[] getEnabledProtocols() {
            return delegate.getEnabledProtocols();
        }

        @Override
        public void setEnabledProtocols(final String[] protocols) {
            delegate.setEnabledProtocols(protocols);
        }

        @Override
        public SSLSession getSession() {
            return delegate.getSession();
        }

        @Override
        public SSLSession getHandshakeSession() {
            return delegate.getHandshakeSession();
        }

        @Override
        public void beginHandshake() throws SSLException {
            delegate.beginHandshake();
        }

        @Override
        public void setUseClientMode(final boolean mode) {
            delegate.setUseClientMode(mode);
        }

        @Override
        public boolean getUseClientMode() {
            return delegate.getUseClientMode();
        }

        @Override
        public void setNeedClientAuth(final boolean need) {
            delegate.setNeedClientAuth(need);
        }

        @Override
        public boolean getNeedClientAuth() {
            return delegate.getNeedClientAuth();
        }

        @Override
        public void setWantClientAuth(final boolean want) {
            delegate.setWantClientAuth(want);
        }

        @Override
        public boolean getWantClientAuth() {
            return delegate.getWantClientAuth();
        }

        @Override
        public void setEnableSessionCreation(final boolean flag) {
            delegate.setEnableSessionCreation(flag);
        }

        @Override
        public boolean getEnableSessionCreation() {
            return delegate.getEnableSessionCreation();
        }

        @Override
        public SSLParameters getSSLParameters() {
            return delegate.getSSLParameters();
        }

        @Override
        public void setSSLParameters(final SSLParameters params) {
            delegate.setSSLParameters(params);
        }

        @Override
        public String getApplicationProtocol() {
            return delegate.getApplicationProtocol();
        }

        @Override
        public String getHandshakeApplicationProtocol() {
            return delegate.getHandshakeApplicationProtocol();
        }

        @Override
        public void setHandshakeApplicationProtocolSelector(final BiFunction<SSLEngine, List<String>, String> selector) {
            delegate.setHandshakeApplicationProtocolSelector(selector);
        }

        @Override
        public BiFunction<SSLEngine, List<String>, String> getHandshakeApplicationProtocolSelector() {
            return delegate.getHandshakeApplicationProtocolSelector();
        }
    }
}
