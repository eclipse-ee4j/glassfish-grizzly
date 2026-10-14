/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */

package org.glassfish.grizzly;

import static org.junit.Assert.assertEquals;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.function.BiFunction;
import java.util.List;

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
import org.glassfish.grizzly.utils.EchoFilter;
import org.junit.Test;

/**
 * Some {@link SSLEngine} implementations (e.g. OpenSSL/BoringSSL based ones) report the end of the handshake only
 * when they unwrap the record that follows the peer's Finished message, and decrypt that record - the first request -
 * in the same call. Such application data must reach the upper filters instead of being dropped with the handshake
 * scratch buffer.
 */
public class SSLHandshakeAppDataTest {

    @Test
    public void applicationDataDecryptedWithTheLastHandshakeRecordIsDelivered() throws Exception {
        final SSLContextConfigurator config = new SSLContextConfigurator();
        final URL keystore = getClass().getClassLoader().getResource("ssltest-keystore.jks");
        config.setKeyStoreFile(keystore.getFile());
        config.setKeyStorePass("changeit");
        final SSLContext serverContext = config.createSSLContext(true);

        final SSLEngineConfigurator serverEngines = new SSLEngineConfigurator(serverContext, false, false, false) {
            @Override
            public SSLEngine createSSLEngine(final String peerHost, final int peerPort) {
                return new DeferredFinishedSSLEngine(super.createSSLEngine(peerHost, peerPort));
            }
        };

        final TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(FilterChainBuilder.stateless()
                .add(new TransportFilter())
                .add(new SSLFilter(serverEngines, null))
                .add(new EchoFilter())
                .build());
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
                    out.write("hello\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    final InputStream in = socket.getInputStream();
                    final byte[] echo = in.readNBytes(6);
                    assertEquals(protocol, "hello\n", new String(echo, StandardCharsets.US_ASCII));
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
