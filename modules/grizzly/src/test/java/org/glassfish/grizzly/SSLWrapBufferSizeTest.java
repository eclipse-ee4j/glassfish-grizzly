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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

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
import org.glassfish.grizzly.ssl.SSLContextConfigurator;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.ssl.SSLFilter;
import org.glassfish.grizzly.strategies.SameThreadIOStrategy;
import org.glassfish.grizzly.utils.EchoFilter;
import org.junit.Test;

/**
 * Small writes are wrapped into a destination sized for the data, not for a whole TLS packet: engines such as Netty's
 * OpenSSL one encrypt through a temporary direct buffer as large as the destination. The JDK engine, which wants room
 * for a whole packet, still works.
 */
public class SSLWrapBufferSizeTest {

    @Test
    public void smallWritesGetASmallDestination() throws Exception {
        final List<Integer> destinations = new CopyOnWriteArrayList<>();
        echo(destinations);
        assertFalse("no application data wrapped", destinations.isEmpty());
        for (final int size : destinations) {
            assertTrue("destination of " + size + " bytes for a 6-byte write: " + destinations, size < 4096);
        }
    }

    /**
     * The JDK engine refuses a destination smaller than a packet: the first refusal switches its engine class to
     * packet-sized destinations, so later writes and connections don't pay for another refusal.
     */
    @Test
    public void engineWantingAWholePacketGetsPacketSizedDestinations() throws Exception {
        final List<Integer> destinations = new CopyOnWriteArrayList<>();
        final List<Integer> overflows = new CopyOnWriteArrayList<>();
        final int[] packetSize = new int[1];
        echo(engine -> {
            packetSize[0] = engine.getSession().getPacketBufferSize();
            return new PacketRecordingSSLEngine(engine, destinations, overflows);
        });
        assertEquals("refused wraps: " + destinations, 1, overflows.size());
        for (final int size : destinations.subList(overflows.get(0) + 1, destinations.size())) {
            assertTrue("destination of " + size + " bytes after the refusal: " + destinations, size >= packetSize[0]);
        }
    }

    private void echo(final List<Integer> destinations) throws Exception {
        echo(engine -> new DestinationRecordingSSLEngine(engine, destinations));
    }

    private void echo(final UnaryOperator<SSLEngine> engines) throws Exception {
        final SSLContextConfigurator config = new SSLContextConfigurator();
        final URL keystore = getClass().getClassLoader().getResource("ssltest-keystore.jks");
        config.setKeyStoreFile(keystore.getFile());
        config.setKeyStorePass("changeit");
        final SSLContext serverContext = config.createSSLContext(true);
        final SSLEngineConfigurator serverEngines = new SSLEngineConfigurator(serverContext, false, false, false) {
            @Override
            public SSLEngine createSSLEngine(final String peerHost, final int peerPort) {
                return engines.apply(super.createSSLEngine(peerHost, peerPort));
            }
        };

        // one thread does the handshake and the writes, so the per-thread cached output buffer (packet-sized by the
        // handshake) is the one offered to the engine
        final TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().setIOStrategy(SameThreadIOStrategy.getInstance())
                .setSelectorRunnersCount(1).build();
        transport.setProcessor(FilterChainBuilder.stateless().add(new TransportFilter()).add(new SSLFilter(serverEngines, null))
                .add(new EchoFilter()).build());
        try {
            final int port = ((InetSocketAddress) transport.bind(new InetSocketAddress("127.0.0.1", 0)).getLocalAddress()).getPort();
            transport.start();
            for (final String protocol : new String[] { "TLSv1.3", "TLSv1.2" }) {
                final SSLContext clientContext = SSLContext.getInstance("TLS");
                clientContext.init(null, new TrustManager[] { TRUST_ALL }, null);
                try (SSLSocket socket = (SSLSocket) clientContext.getSocketFactory().createSocket("127.0.0.1", port)) {
                    socket.setSoTimeout(10_000);
                    socket.setEnabledProtocols(new String[] { protocol });
                    final OutputStream out = socket.getOutputStream();
                    for (int i = 0; i < 3; i++) {
                        out.write("hello\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        assertEquals(protocol, "hello\n", new String(socket.getInputStream().readNBytes(6), StandardCharsets.US_ASCII));
                    }
                }
            }
        } finally {
            transport.shutdownNow();
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
     * Encrypts like Netty's OpenSSL engine with a heap destination: refuses a destination smaller than the plaintext of
     * one record plus the record overhead, and otherwise uses a temporary buffer as large as the destination. Records the
     * destination sizes offered for application data.
     */
    private static class DestinationRecordingSSLEngine extends SSLEngine {
        /** Overhead OpenSSL reserves per record (SSL_max_seal_overhead for TLS), more than the JDK's. */
        static final int OPENSSL_MAX_WRAP_OVERHEAD = 325;

        final SSLEngine delegate;
        final List<Integer> appDataDestinations;

        DestinationRecordingSSLEngine(final SSLEngine delegate, final List<Integer> appDataDestinations) {
            this.delegate = delegate;
            this.appDataDestinations = appDataDestinations;
        }

        @Override
        public SSLEngineResult wrap(final ByteBuffer[] srcs, final int offset, final int length, final ByteBuffer dst) throws SSLException {
            long plaintext = 0;
            for (int i = offset; i < offset + length; i++) {
                plaintext += srcs[i].remaining();
            }
            final SSLSession session = delegate.getSession();
            if (plaintext > 0 && delegate.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING) {
                appDataDestinations.add(dst.remaining());
                final long needed = Math.min(plaintext, session.getApplicationBufferSize()) + OPENSSL_MAX_WRAP_OVERHEAD;
                if (dst.remaining() < needed) {
                    return new SSLEngineResult(Status.BUFFER_OVERFLOW, HandshakeStatus.NOT_HANDSHAKING, 0, 0);
                }
            }
            final ByteBuffer tmp = ByteBuffer.allocate(Math.max(dst.remaining(), session.getPacketBufferSize()));
            final SSLEngineResult result = delegate.wrap(srcs, offset, length, tmp);
            tmp.flip();
            if (tmp.remaining() > dst.remaining()) {
                throw new IllegalStateException("produced " + tmp.remaining() + " bytes for a destination of " + dst.remaining());
            }
            dst.put(tmp);
            return result;
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

    /**
     * The JDK engine as it is, recording the destination sizes offered for application data and which wraps it refused.
     */
    private static final class PacketRecordingSSLEngine extends DestinationRecordingSSLEngine {
        private final List<Integer> overflows;

        PacketRecordingSSLEngine(final SSLEngine delegate, final List<Integer> appDataDestinations, final List<Integer> overflows) {
            super(delegate, appDataDestinations);
            this.overflows = overflows;
        }

        @Override
        public SSLEngineResult wrap(final ByteBuffer[] srcs, final int offset, final int length, final ByteBuffer dst) throws SSLException {
            long plaintext = 0;
            for (int i = offset; i < offset + length; i++) {
                plaintext += srcs[i].remaining();
            }
            final boolean appData = plaintext > 0 && delegate.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING;
            if (appData) {
                appDataDestinations.add(dst.remaining());
            }
            final SSLEngineResult result = delegate.wrap(srcs, offset, length, dst);
            if (appData && result.getStatus() == Status.BUFFER_OVERFLOW) {
                overflows.add(appDataDestinations.size() - 1);
            }
            return result;
        }
    }
}
