/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2010, 2020 Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.filterchain.Filter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpPacket;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.ServerConfiguration;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.ssl.SSLContextConfigurator;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.ssl.SSLFilter;

/**
 * General HTTP2 client/server init code.
 *
 * @author Alexey Stashok
 */
public abstract class AbstractHttp2Test {
    static {
        try {
            LogManager.getLogManager().readConfiguration(AbstractHttp2Test.class.getResourceAsStream("/logging.properties"));
        } catch (SecurityException | IOException e) {
            e.printStackTrace();
        }
    }


    protected static final Logger LOGGER = Grizzly.logger(AbstractHttp2Test.class);

    private volatile static SSLEngineConfigurator clientSSLEngineConfigurator;
    private volatile static SSLEngineConfigurator serverSSLEngineConfigurator;

    public static Collection<Object[]> configure() {
        return Arrays.asList(new Object[][] { { Boolean.FALSE, Boolean.TRUE }, // not secure, prior knowledge
                { Boolean.FALSE, Boolean.FALSE }, // not secure, upgrade
                // { (AlpnSupport.isEnabled() && !Boolean.valueOf(System.getProperty("grizzly.skip.http2tls", "false"))), Boolean.TRUE }
                // // secure
                // { (AlpnSupport.isEnabled() && !Boolean.valueOf(System.getProperty("grizzly.skip.http2tls", "false"))), Boolean.FALSE
                // }, // secure
        });
    }

    protected Http2AddOn http2Addon;

    protected HttpServer createServer(final String docRoot, final int port, final boolean isSecure, final HttpHandlerRegistration... registrations) {

        return createServer(docRoot, port, isSecure, false, registrations);
    }

    protected HttpServer createServer(final String docRoot, final int port, final boolean isSecure, final boolean isFileCacheEnabled,
            final HttpHandlerRegistration... registrations) {
        HttpServer server = HttpServer.createSimpleServer(docRoot, port);
        NetworkListener listener = server.getListener("grizzly");
        listener.setSendFileEnabled(false);

        listener.getFileCache().setEnabled(isFileCacheEnabled);

        if (isSecure) {
            listener.setSecure(true);
            listener.setSSLEngineConfig(getServerSSLEngineConfigurator());
        }

        http2Addon = new Http2AddOn(Http2Configuration.builder().disableCipherCheck(true).build());
        listener.registerAddOn(http2Addon);

        ServerConfiguration sconfig = server.getServerConfiguration();

        for (HttpHandlerRegistration registration : registrations) {
            sconfig.addHttpHandler(registration.httpHandler, registration.mappings);
        }

        return server;
    }

    protected static FilterChain createClientFilterChain(final boolean isSecure, final Filter... clientFilters) {

        return createClientFilterChainAsBuilder(isSecure, false, clientFilters).build();
    }

    protected static FilterChainBuilder createClientFilterChainAsBuilder(final boolean isSecure, final Filter... clientFilters) {
        return createClientFilterChainAsBuilder(isSecure, false, clientFilters);
    }

    protected static FilterChainBuilder createClientFilterChainAsBuilder(final boolean isSecure, final boolean priorKnowledge, final Filter... clientFilters) {

        final FilterChainBuilder builder = FilterChainBuilder.stateless().add(new TransportFilter());
        if (isSecure) {
            builder.add(new SSLFilter(null, getClientSSLEngineConfigurator()));
        }

        builder.add(new HttpClientFilter());
        builder.add(new Http2ClientFilter(Http2Configuration.builder().priorKnowledge(priorKnowledge).build()));

        if (clientFilters != null) {
            for (Filter clientFilter : clientFilters) {
                if (clientFilter != null) {
                    builder.add(clientFilter);
                }
            }
        }

        return builder;
    }

    protected static SSLEngineConfigurator getClientSSLEngineConfigurator() {
        checkSSLEngineConfigurators();
        return clientSSLEngineConfigurator;
    }

    protected static SSLEngineConfigurator getServerSSLEngineConfigurator() {
        checkSSLEngineConfigurators();
        return serverSSLEngineConfigurator;
    }

    private static void checkSSLEngineConfigurators() {
        if (clientSSLEngineConfigurator == null) {
            synchronized (AbstractHttp2Test.class) {
                if (clientSSLEngineConfigurator == null) {
                    SSLContextConfigurator sslContextConfigurator = createSSLContextConfigurator();
                    serverSSLEngineConfigurator = new SSLEngineConfigurator(sslContextConfigurator.createSSLContext(true), false, false, false);

                    serverSSLEngineConfigurator.setEnabledCipherSuites(new String[] { "TLS_AES_128_GCM_SHA256" });

                    clientSSLEngineConfigurator = new SSLEngineConfigurator(sslContextConfigurator.createSSLContext(true), true, false, false);

                    clientSSLEngineConfigurator.setEnabledCipherSuites(new String[] { "TLS_AES_128_GCM_SHA256" });
                }
            }
        }
    }

    protected static SSLContextConfigurator createSSLContextConfigurator() {
        SSLContextConfigurator sslContextConfigurator = new SSLContextConfigurator();
        ClassLoader cl = AbstractHttp2Test.class.getClassLoader();
        // override system properties
        URL cacertsUrl = cl.getResource("ssltest-cacerts.jks");
        if (cacertsUrl != null) {
            sslContextConfigurator.setTrustStoreFile(cacertsUrl.getFile());
            sslContextConfigurator.setTrustStorePass("changeit");
        }

        // override system properties
        URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        if (keystoreUrl != null) {
            sslContextConfigurator.setKeyStoreFile(keystoreUrl.getFile());
            sslContextConfigurator.setKeyStorePass("changeit");
        }

        return sslContextConfigurator;
    }

    @SuppressWarnings({ "unchecked" })
    protected HttpPacket createRequest(final int port, final String method, final String content, String encoding) {

        HttpRequestPacket.Builder b = HttpRequestPacket.builder();
        b.method(method).protocol(Protocol.HTTP_1_1).uri("/path").header("Host", "localhost:" + port);

        HttpRequestPacket request = b.build();

        if (content != null) {
            HttpContent.Builder cb = request.httpContentBuilder();
            MemoryManager mm = MemoryManager.DEFAULT_MEMORY_MANAGER;
            Buffer contentBuffer;
            if (encoding != null) {
                try {
                    byte[] bytes = content.getBytes(encoding);
                    contentBuffer = Buffers.wrap(mm, bytes);
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }
            } else {
                contentBuffer = Buffers.wrap(mm, content);
            }

            request.setContentLength(contentBuffer.remaining());

            if (encoding != null) {
                request.setCharacterEncoding(encoding);
            }

            request.setContentType("text/plain");

            cb.content(contentBuffer);
            cb.last(true);
            return cb.build();

        }

        return request;
    }

    protected static class HttpHandlerRegistration {
        private final HttpHandler httpHandler;
        private final String[] mappings;

        private HttpHandlerRegistration(HttpHandler httpHandler, String[] mappings) {
            this.httpHandler = httpHandler;
            this.mappings = mappings;
        }

        public static HttpHandlerRegistration of(final HttpHandler httpHandler, final String... mappings) {
            return new HttpHandlerRegistration(httpHandler, mappings);
        }
    }
}
