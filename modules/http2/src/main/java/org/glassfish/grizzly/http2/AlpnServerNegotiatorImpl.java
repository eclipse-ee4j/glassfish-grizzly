/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2014, 2020 Oracle and/or its affiliates. All rights reserved.
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

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.npn.AlpnServerNegotiator;

final class AlpnServerNegotiatorImpl extends AlpnNegotiatorBase implements AlpnServerNegotiator {
    private final static Logger LOGGER = Grizzly.logger(AlpnServerNegotiatorImpl.class);

    private final Http2BaseFilter filter;

    public AlpnServerNegotiatorImpl(final Http2ServerFilter http2HandlerFilter) {
        this.filter = http2HandlerFilter;
    }

    @Override
    public String selectProtocol(SSLEngine sslEngine, String[] clientProtocols) {
        final Connection<?> connection = AlpnSupport.getConnection(sslEngine);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "Alpn selectProtocol. Connection={0} sslEngine={1} clientProtocols={2}",
                    new Object[] { connection, sslEngine, Arrays.toString(clientProtocols) });
        }
        for (String supportedProtocol : SUPPORTED_PROTOCOLS) {
            for (String clientProtocol : clientProtocols) {
                if (supportedProtocol.equals(clientProtocol)) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(Level.FINE, "Alpn select {0}", clientProtocol);
                    }
                    configureHttp2(connection, clientProtocol);
                    return clientProtocol;
                }
            }
        }

        // Never try HTTP2 for this connection
        Http2State.create(connection).setNeverHttp2();

        return null;
    }

    private void configureHttp2(final Connection<?> connection, final String supportedProtocol) {
        if (HTTP2.equals(supportedProtocol)) {
            // If HTTP2 is supported - initialize HTTP2 connection
            // Create HTTP2 connection and bind it to the Grizzly connection
            final Http2Session http2Session = filter.createHttp2Session(connection, true);

            // we expect client preface
            http2Session.getHttp2State().setDirectUpgradePhase();
            // !!! DON'T SEND PREFACE HERE
            // SSL connection (handshake) is not established yet and if we try
            // to send preface here SSLBaseFilter will try to flush it right away,
            // because it doesn't queue the output data like SSLFilter.
//            http2Session.enableHttp2Output();
//            http2Session.sendPreface();
        }
    }
}
