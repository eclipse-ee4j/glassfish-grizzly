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

package org.glassfish.grizzly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.ssl.SSLBaseFilter;
import org.glassfish.grizzly.ssl.SSLContextConfigurator;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.ssl.SSLFilter;
import org.glassfish.grizzly.utils.EchoFilter;
import org.junit.After;
import org.junit.Test;

/**
 * Some {@link SSLEngine} implementations (e.g. OpenSSL/BoringSSL based ones) report the end of the handshake only
 * when they unwrap the record that follows the peer's Finished message, and decrypt that record - the first request -
 * in the same call. Such application data, and application data a peer sends during a renegotiation, must reach the
 * upper filters in order instead of being dropped with the handshake scratch buffer.
 */
public class SSLHandshakeAppDataTest {

    private TCPNIOTransport transport;
    private final java.util.Queue<Throwable> handshakeFailures = new java.util.concurrent.ConcurrentLinkedQueue<>();

    @After
    public void after() throws IOException {
        if (transport != null) {
            transport.shutdownNow();
        }
    }

    @Test
    public void applicationDataDecryptedWithTheLastHandshakeRecordIsDelivered() throws Exception {
        final int port = startEchoServer(true, false);
        for (final String protocol : new String[] { "TLSv1.3", "TLSv1.2" }) {
            echoOverSslSocket(port, protocol);
        }
    }

    /**
     * The same with a handshake timeout, so the server handshakes through SSLBaseFilter#doHandshakeSync.
     */
    @Test
    public void applicationDataIsDeliveredAfterASynchronousHandshake() throws Exception {
        final int port = startEchoServer(true, true);
        for (final String protocol : new String[] { "TLSv1.3", "TLSv1.2" }) {
            echoOverSslSocket(port, protocol);
        }
    }

    /**
     * Two records in one network write: the second one is unwrapped after the data kept from the handshake.
     */
    @Test
    public void recordFollowingTheKeptDataInTheSameReadIsAppended() throws Exception {
        final int port = startEchoServer(true, false);
        for (final String protocol : new String[] { "TLSv1.3", "TLSv1.2" }) {
            try (EngineClient client = new EngineClient(port, protocol)) {
                client.handshake();
                client.send(concat(client.wrap("hel"), client.wrap("lo\n")));
                assertEquals(protocol, "hello\n", client.read(6));
            }
        }
    }

    /**
     * The kept data is the whole input of its read: it is passed upstream although nothing else is pending.
     */
    @Test
    public void keptDataAloneIsDelivered() throws Exception {
        final int port = startEchoServer(true, false);
        for (final String protocol : new String[] { "TLSv1.3", "TLSv1.2" }) {
            try (EngineClient client = new EngineClient(port, protocol)) {
                client.handshake();
                client.send(client.wrap("hel"));
                assertEquals(protocol, "hel", client.read(3));
                client.send(client.wrap("lo\n"));
                assertEquals(protocol, "lo\n", client.read(3));
            }
        }
    }

    /**
     * A TLS 1.2 client renegotiates and sends application data right after its ClientHello: the server unwraps it
     * while re-handshaking and must pass it upstream when the renegotiation is done, not with a later read.
     */
    @Test
    public void applicationDataSentDuringARenegotiationIsDelivered() throws Exception {
        final int port = startEchoServer(false, false);
        try (EngineClient client = new EngineClient(port, "TLSv1.2")) {
            client.handshake();
            client.send(client.wrap("a\n"));
            assertEquals("a\n", client.read(2));

            client.engine.beginHandshake();
            final ByteBuffer clientHello = client.wrapHandshake();
            final ByteBuffer data = client.wrap("ping\n");
            client.send(concat(clientHello, data));
            client.handshakeSteps();
            assertEquals("ping\n", client.read(5));
        }
    }

    /**
     * A peer that starts a renegotiation and then only sends application data can't make the server keep it all.
     */
    @Test
    public void applicationDataDuringAHandshakeIsLimited() throws Exception {
        final int port = startEchoServer(false, false);
        try (EngineClient client = new EngineClient(port, "TLSv1.2")) {
            client.handshake();
            client.engine.beginHandshake();
            client.send(client.wrapHandshake());
            final String chunk = "x".repeat(16_000);
            try {
                for (int i = 0; i < 16; i++) {
                    client.send(client.wrap(chunk));
                }
                // the server must close the connection instead of waiting for the end of the handshake
                final InputStream in = client.socket.getInputStream();
                while (in.read() != -1) {
                    // the server's handshake messages
                }
            } catch (SocketTimeoutException e) {
                throw new AssertionError("connection still open", e);
            } catch (IOException expected) {
                // reset by the server
            }
        }
        final Throwable failure = handshakeFailures.peek();
        assertTrue(String.valueOf(failure), failure != null && String.valueOf(failure.getMessage()).startsWith("More than "));
    }

    private int startEchoServer(final boolean deferFinished, final boolean synchronousHandshake) throws IOException {
        final SSLContextConfigurator config = new SSLContextConfigurator();
        final URL keystore = getClass().getClassLoader().getResource("ssltest-keystore.jks");
        config.setKeyStoreFile(keystore.getFile());
        config.setKeyStorePass("changeit");
        final SSLContext serverContext = config.createSSLContext(true);

        final SSLEngineConfigurator serverEngines = new SSLEngineConfigurator(serverContext, false, false, false) {
            @Override
            public SSLEngine createSSLEngine(final String peerHost, final int peerPort) {
                final SSLEngine engine = super.createSSLEngine(peerHost, peerPort);
                return deferFinished ? new DeferredFinishedSSLEngine(engine) : engine;
            }
        };

        final SSLFilter sslFilter = new SSLFilter(serverEngines, null);
        sslFilter.addHandshakeListener(new SSLBaseFilter.HandshakeListener() {
            @Override
            public void onStart(final Connection<?> connection) {
            }

            @Override
            public void onComplete(final Connection<?> connection) {
            }

            @Override
            public void onFailure(final Connection<?> connection, final Throwable t) {
                handshakeFailures.add(t);
            }
        });
        if (synchronousHandshake) {
            sslFilter.setHandshakeTimeout(10, TimeUnit.SECONDS);
        }
        transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(FilterChainBuilder.stateless().add(new TransportFilter()).add(sslFilter).add(new EchoFilter()).build());
        final int port = ((InetSocketAddress) transport.bind(new InetSocketAddress("127.0.0.1", 0)).getLocalAddress()).getPort();
        transport.start();
        return port;
    }

    private static void echoOverSslSocket(final int port, final String protocol) throws Exception {
        final SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, new TrustManager[] { TRUST_ALL }, null);
        try (SSLSocket socket = (SSLSocket) clientContext.getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.setEnabledProtocols(new String[] { protocol });
            final OutputStream out = socket.getOutputStream();
            out.write("hello\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertEquals(protocol, "hello\n", new String(socket.getInputStream().readNBytes(6), StandardCharsets.US_ASCII));
        }
    }

    private static ByteBuffer concat(final ByteBuffer a, final ByteBuffer b) {
        final ByteBuffer both = ByteBuffer.allocate(a.remaining() + b.remaining());
        both.put(a).put(b).flip();
        return both;
    }

    /**
     * A blocking TLS client on an {@link SSLEngine}, so the test decides which records leave in one network write.
     */
    private static final class EngineClient implements AutoCloseable {
        final Socket socket;
        final SSLEngine engine;
        private final ByteBuffer netIn;
        private final ByteBuffer appIn;

        EngineClient(final int port, final String protocol) throws Exception {
            final SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] { TRUST_ALL }, null);
            engine = context.createSSLEngine("127.0.0.1", port);
            engine.setUseClientMode(true);
            engine.setEnabledProtocols(new String[] { protocol });
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(10_000);
            netIn = ByteBuffer.allocate(engine.getSession().getPacketBufferSize() * 4);
            netIn.flip();
            appIn = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize() * 4);
        }

        void handshake() throws IOException {
            engine.beginHandshake();
            handshakeSteps();
        }

        void handshakeSteps() throws IOException {
            while (true) {
                switch (engine.getHandshakeStatus()) {
                case NEED_WRAP:
                    send(wrapHandshake());
                    break;
                case NEED_UNWRAP:
                case NEED_UNWRAP_AGAIN:
                    unwrapOnce();
                    break;
                case NEED_TASK:
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    break;
                default:
                    return;
                }
            }
        }

        ByteBuffer wrapHandshake() throws SSLException {
            final ByteBuffer out = ByteBuffer.allocate(engine.getSession().getPacketBufferSize() * 4);
            while (engine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) {
                final SSLEngineResult result = engine.wrap(ByteBuffer.allocate(0), out);
                if (result.getStatus() != Status.OK) {
                    throw new IllegalStateException(result.toString());
                }
                if (result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null) {
                        task.run();
                    }
                }
            }
            out.flip();
            return out;
        }

        ByteBuffer wrap(final String data) throws SSLException {
            final ByteBuffer src = ByteBuffer.wrap(data.getBytes(StandardCharsets.US_ASCII));
            final ByteBuffer out = ByteBuffer.allocate(engine.getSession().getPacketBufferSize() * 2);
            final SSLEngineResult result = engine.wrap(src, out);
            assertEquals(result.toString(), data.length(), result.bytesConsumed());
            out.flip();
            return out;
        }

        void send(final ByteBuffer data) throws IOException {
            socket.getOutputStream().write(data.array(), data.position(), data.remaining());
            socket.getOutputStream().flush();
        }

        String read(final int length) throws IOException {
            while (appIn.position() < length) {
                unwrapOnce();
            }
            appIn.flip();
            final byte[] bytes = new byte[length];
            appIn.get(bytes);
            appIn.compact();
            return new String(bytes, StandardCharsets.US_ASCII);
        }

        private void unwrapOnce() throws IOException {
            while (true) {
                if (netIn.hasRemaining()) {
                    final SSLEngineResult result = engine.unwrap(netIn, appIn);
                    if (result.getStatus() == Status.OK) {
                        return;
                    }
                    assertTrue(result.toString(), result.getStatus() == Status.BUFFER_UNDERFLOW);
                }
                netIn.compact();
                final int n = socket.getInputStream().read(netIn.array(), netIn.position(), netIn.remaining());
                if (n < 0) {
                    throw new IOException("connection closed");
                }
                netIn.position(netIn.position() + n);
                netIn.flip();
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
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

    /**
     * Delegates to the JDK engine, but keeps reporting NEED_UNWRAP once the handshake is done (on a wrap or an unwrap)
     * until the next record has been unwrapped, and returns FINISHED together with that record's application data.
     */
    private static final class DeferredFinishedSSLEngine extends SSLEngine {
        private final SSLEngine delegate;
        private boolean finishedPending;

        DeferredFinishedSSLEngine(final SSLEngine delegate) {
            this.delegate = delegate;
        }

        @Override
        public SSLEngineResult unwrap(final ByteBuffer src, final ByteBuffer[] dsts, final int offset, final int length) throws SSLException {
            final SSLEngineResult result = delegate.unwrap(src, dsts, offset, length);
            if (finishedPending) {
                if (result.getStatus() != Status.OK) {
                    return new SSLEngineResult(result.getStatus(), HandshakeStatus.NEED_UNWRAP, result.bytesConsumed(), result.bytesProduced());
                }
                finishedPending = false;
                return new SSLEngineResult(Status.OK, HandshakeStatus.FINISHED, result.bytesConsumed(), result.bytesProduced());
            }
            if (result.getHandshakeStatus() == HandshakeStatus.FINISHED && !getUseClientMode()) {
                finishedPending = true;
                return new SSLEngineResult(result.getStatus(), HandshakeStatus.NEED_UNWRAP, result.bytesConsumed(), result.bytesProduced());
            }
            return result;
        }

        @Override
        public HandshakeStatus getHandshakeStatus() {
            return finishedPending ? HandshakeStatus.NEED_UNWRAP : delegate.getHandshakeStatus();
        }

        @Override
        public SSLEngineResult wrap(final ByteBuffer[] srcs, final int offset, final int length, final ByteBuffer dst) throws SSLException {
            final SSLEngineResult result = delegate.wrap(srcs, offset, length, dst);
            // the JDK server completes the handshake on a wrap (its own Finished in TLS 1.2, a ticket in TLS 1.3)
            if (result.getHandshakeStatus() == HandshakeStatus.FINISHED && !getUseClientMode()) {
                finishedPending = true;
                return new SSLEngineResult(result.getStatus(), HandshakeStatus.NEED_UNWRAP, result.bytesConsumed(), result.bytesProduced());
            }
            return result;
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
