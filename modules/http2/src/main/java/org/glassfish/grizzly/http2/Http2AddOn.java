/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2012, 2020 Oracle and/or its affiliates. All rights reserved.
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

import java.util.logging.Logger;

import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.Transport;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.http.server.AddOn;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.ssl.SSLBaseFilter;
import org.glassfish.grizzly.ssl.SSLFilter;

/**
 * FilterChain after being processed by {@link Http2AddOn}:
 *
 * <pre>
 *     {@link org.glassfish.grizzly.filterchain.TransportFilter} <-> {@link SSLFilter}(optional) <-> {@link org.glassfish.grizzly.http.HttpServerFilter} <-> {@link Http2ServerFilter} <-> {@link org.glassfish.grizzly.http.server.HttpServer}
 * </pre>
 *
 * {@link SSLFilter}, if present, is configured to use ALPN for HTTP2 protocol negotiation
 */
public class Http2AddOn implements AddOn {

    private static final Logger LOGGER = Grizzly.logger(Http2AddOn.class);

    private final Http2Configuration http2Configuration;

    // ----------------------------------------------------------- Constructors


    public Http2AddOn() {
        this(Http2Configuration.builder().build());
    }

    public Http2AddOn(final Http2Configuration http2Configuration) {
        this.http2Configuration = http2Configuration;
    }

    // ----------------------------------------------------- Methods From AddOn

    @Override
    public void setup(NetworkListener networkListener, FilterChainBuilder builder) {
        LOGGER.config(() -> String.format("setup(networkListener=%s, builder=%s)", networkListener, builder));
        final TCPNIOTransport transport = networkListener.getTransport();

        if (networkListener.isSecure() && !AlpnSupport.isEnabled()) {
            LOGGER.warning("TLS ALPN (Application-Layer Protocol Negotiation) support is not available."
                + " HTTP/2 support will not be enabled.");
            return;
        }

        final Http2ServerFilter http2Filter = updateFilterChain(builder);

        if (networkListener.isSecure()) {
            configureAlpn(transport, http2Filter, builder);
        }
    }

    // --------------------------------------------------------- Public Methods

    /**
     * @return the configuration backing this {@link AddOn} and ultimately the {@link Http2ServerFilter}.
     */
    public Http2Configuration getConfiguration() {
        return http2Configuration;
    }

    // -------------------------------------------------------- Private Methods

    private Http2ServerFilter updateFilterChain(final FilterChainBuilder builder) {
        final int codecFilterIdx = builder.indexOfType(org.glassfish.grizzly.http.HttpServerFilter.class);
        final Http2ServerFilter http2HandlerFilter = new Http2ServerFilter(http2Configuration);
        http2HandlerFilter.setLocalMaxFramePayloadSize(http2Configuration.getMaxFramePayloadSize());
        builder.add(codecFilterIdx + 1, http2HandlerFilter);
        return http2HandlerFilter;
    }

    private static void configureAlpn(final Transport transport,
                                      final Http2ServerFilter http2Filter,
                                      final FilterChainBuilder builder) {
        LOGGER.finest(() -> String.format("configureAlpn(transport=%s, http2Filter=%s, builder=%s)",
                transport, http2Filter, builder));

        final int idx = builder.indexOfType(SSLBaseFilter.class);
        if (idx == -1) {
            LOGGER.warning("No usable SSLBaseFilter found!");
            return;
        }
        final SSLBaseFilter sslFilter = (SSLBaseFilter) builder.get(idx);
        AlpnSupport alpnSupport = AlpnSupport.getInstance();
        alpnSupport.configure(sslFilter);
        alpnSupport.setServerSideNegotiator(transport, new AlpnServerNegotiatorImpl(http2Filter));
    }
}
