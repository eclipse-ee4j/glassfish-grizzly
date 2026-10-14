/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2013, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.SocketConnectorHandler;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.server.util.Globals;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.ssl.SSLContextConfigurator;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.ssl.SSLFilter;
import org.glassfish.grizzly.utils.Charsets;
import org.junit.After;
import org.junit.Test;

/**
 * Testing SSL attributes.
 *
 * @author Alexey Stashok
 */
@SuppressWarnings("unchecked")
public class SSLAttributesTest {
    private static final Logger LOGGER = Grizzly.logger(SSLAttributesTest.class);
    private static final byte[] PAYLOAD_BYTES = "Hello world".getBytes(Charsets.ASCII_CHARSET);
    private static final String NULL = "null";

    private HttpServer httpServer;

    @Test
    public void testPlainHttpConnection() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        configureHttpServer(false, port);
        startHttpServer(new SSLAttrbitesHandler());

        final BlockingQueue<HttpContent> resultQueue = new LinkedTransferQueue<>();

        final Connection connection = connectClient(false, resultQueue, port);

        try {
            HttpRequestPacket request = HttpRequestPacket.builder().uri("/").method(Method.POST).chunked(true).protocol(Protocol.HTTP_1_1)
                    .header("Host", "localhost:" + port).build();

            connection.write(request);

            Thread.sleep(1000);

            HttpContent content = HttpContent.builder(request).content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, PAYLOAD_BYTES)).last(true).build();

            connection.write(content);

            final HttpContent httpContent = resultQueue.poll(30, TimeUnit.SECONDS);

            assertNotNull(httpContent);

            HttpResponsePacket response = (HttpResponsePacket) httpContent.getHttpHeader();

            assertEquals(NULL, response.getHeader("CERTIFICATES_ATTR_1"));
            assertEquals(NULL, response.getHeader("CIPHER_SUITE_ATTR_1"));
            assertEquals(NULL, response.getHeader("KEY_SIZE_ATTR_1"));

            assertEquals(NULL, response.getHeader("SSL_CERTIFICATE_ATTR"));

            assertEquals(NULL, response.getHeader("CERTIFICATES_ATTR_2"));
            assertEquals(NULL, response.getHeader("CIPHER_SUITE_ATTR_2"));
            assertEquals(NULL, response.getHeader("KEY_SIZE_ATTR_2"));

            assertEquals(Integer.toString(PAYLOAD_BYTES.length), response.getHeader("PAYLOAD_SIZE"));
        } finally {
            connection.closeSilently();
        }
    }

    @Test
    public void testHttpsConnection() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        configureHttpServer(true, port);
        startHttpServer(new SSLAttrbitesHandler());

        final BlockingQueue<HttpContent> resultQueue = new LinkedTransferQueue<>();

        final Connection connection = connectClient(true, resultQueue, port);

        try {
            HttpRequestPacket request = HttpRequestPacket.builder().uri("/").method(Method.POST).chunked(true).protocol(Protocol.HTTP_1_1)
                    .header("Host", "localhost:" + port).build();

            connection.write(request);

            Thread.sleep(1000);

            HttpContent content = HttpContent.builder(request).content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, PAYLOAD_BYTES)).last(true).build();

            connection.write(content);

            final HttpContent httpContent = resultQueue.poll(30, TimeUnit.SECONDS);

            assertNotNull(httpContent);

            HttpResponsePacket response = (HttpResponsePacket) httpContent.getHttpHeader();

            assertEquals(NULL, response.getHeader("CERTIFICATES_ATTR_1"));
            assertTrue(notNull(response.getHeader("CIPHER_SUITE_ATTR_1")));
            assertTrue(notNull(response.getHeader("KEY_SIZE_ATTR_1")));

            assertTrue(notNull(response.getHeader("SSL_CERTIFICATE_ATTR")));

            assertTrue(notNull(response.getHeader("CERTIFICATES_ATTR_2")));
            assertTrue(notNull(response.getHeader("CIPHER_SUITE_ATTR_2")));
            assertTrue(notNull(response.getHeader("KEY_SIZE_ATTR_2")));

            assertEquals(Integer.toString(PAYLOAD_BYTES.length), response.getHeader("PAYLOAD_SIZE"));
        } finally {
            connection.closeSilently();
        }
    }

    @After
    public void after() throws Exception {
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }

    private void configureHttpServer(final boolean isSslEnabled, final int port) throws Exception {
        httpServer = new HttpServer();
        final NetworkListener listener = new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, port);
        if (isSslEnabled) {
            listener.setSecure(true);
            listener.setSSLEngineConfig(createSSLConfig(true));
        }
        httpServer.addListener(listener);

    }

    private static SSLEngineConfigurator createSSLConfig(boolean isServer) throws Exception {
        final SSLContextConfigurator sslContextConfigurator = new SSLContextConfigurator();
        final ClassLoader cl = SuspendTest.class.getClassLoader();
        // override system properties
        final URL cacertsUrl = cl.getResource("ssltest-cacerts.jks");
        if (cacertsUrl != null) {
            sslContextConfigurator.setTrustStoreFile(cacertsUrl.getFile());
            sslContextConfigurator.setTrustStorePass("changeit");
            sslContextConfigurator.setSecurityProtocol("TLSv1.2");
        }

        // override system properties
        final URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        if (keystoreUrl != null) {
            sslContextConfigurator.setKeyStoreFile(keystoreUrl.getFile());
            sslContextConfigurator.setKeyStorePass("changeit");
        }

        return new SSLEngineConfigurator(sslContextConfigurator.createSSLContext(), !isServer, false, false);
    }

    private void startHttpServer(final HttpHandler httpHandler) throws Exception {
        httpServer.getServerConfiguration().addHttpHandler(httpHandler);
        httpServer.start();
    }

    private Connection connectClient(final boolean isSSLEnabled, Queue<HttpContent> resultQueue, final int port) throws Exception {
        final FilterChainBuilder builder = FilterChainBuilder.stateless();
        builder.add(new TransportFilter());

        if (isSSLEnabled) {
            final SSLFilter sslFilter = new SSLFilter(createSSLConfig(true), createSSLConfig(false));
            builder.add(sslFilter);
        }

        builder.add(new HttpClientFilter());
        builder.add(new ClientFilter(resultQueue));

        SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(httpServer.getListener("grizzly").getTransport()).processor(builder.build())
                .build();

        Future<Connection> connectFuture = connectorHandler.connect("localhost", port);
        return connectFuture.get(10, TimeUnit.SECONDS);
    }

    private static boolean notNull(final Object value) {
        return value != null && !value.equals(NULL);
    }

    private static class ClientFilter extends BaseFilter {

        private final Queue<HttpContent> resultQueue;

        public ClientFilter(final Queue<HttpContent> resultQueue) {
            this.resultQueue = resultQueue;
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            final HttpContent httpContent = ctx.getMessage();
            if (!httpContent.isLast()) {
                return ctx.getStopAction(httpContent);
            }

            resultQueue.offer(httpContent);

            return ctx.getStopAction();
        }
    }

    private static class SSLAttrbitesHandler extends HttpHandler {
        @Override
        public void service(Request request, Response response) throws Exception {
            try {
                // Check empty attribute name (not relevant to SSL attributes)
                assertNull(request.getAttribute(""));
                request.setAttribute("", Boolean.TRUE);
                assertTrue((Boolean) request.getAttribute(""));
                request.removeAttribute("");
                assertNull(request.getAttribute(""));
                // -------

                final Object certAttr1 = request.getAttribute(Globals.CERTIFICATES_ATTR);
                final Object cipherSuiteAttr1 = request.getAttribute(Globals.CIPHER_SUITE_ATTR);
                final Object keySizeAttr1 = request.getAttribute(Globals.KEY_SIZE_ATTR);

                final Object sslCertAttr = request.getAttribute(Globals.SSL_CERTIFICATE_ATTR);

                final Object certAttr2 = request.getAttribute(Globals.CERTIFICATES_ATTR);
                final Object cipherSuiteAttr2 = request.getAttribute(Globals.CIPHER_SUITE_ATTR);
                final Object keySizeAttr2 = request.getAttribute(Globals.KEY_SIZE_ATTR);

                response.setHeader("CERTIFICATES_ATTR_1", toStr(certAttr1));
                response.setHeader("CIPHER_SUITE_ATTR_1", toStr(cipherSuiteAttr1));
                response.setHeader("KEY_SIZE_ATTR_1", toStr(keySizeAttr1));

                response.setHeader("SSL_CERTIFICATE_ATTR", toStr(sslCertAttr));

                response.setHeader("CERTIFICATES_ATTR_2", toStr(certAttr2));
                response.setHeader("CIPHER_SUITE_ATTR_2", toStr(cipherSuiteAttr2));
                response.setHeader("KEY_SIZE_ATTR_2", toStr(keySizeAttr2));

                final InputStream is = request.getInputStream();
                int size = 0;

                final byte[] buf = new byte[32];
                do {
                    final int readNow = is.read(buf);
                    if (readNow < 0) {
                        break;
                    }

                    size += readNow;
                } while (true);

                response.setHeader("PAYLOAD_SIZE", Integer.toString(size));

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Can't retrieve SSL attribute", e);
                response.setStatus(500, "Can't retrieve SSL attribute");
            }
        }

        private String toStr(final Object value) {
            return value != null ? value.toString() : NULL;
        }
    }
}
