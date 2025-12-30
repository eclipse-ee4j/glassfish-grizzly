/*
 * Copyright (c) 2014, 2023 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.sni;

import javax.net.ssl.SSLEngine;

import org.glassfish.grizzly.ssl.SSLEngineConfigurator;

/**
 * The object represents SNI configuration for either server or client side. In order to create a server-side SNI
 * configuration - the {@link #serverConfigBuilder()} has to be used, for client-side SNI configuration please use
 * {@link #clientConfigBuilder()}.
 *
 * @author Alexey Stashok
 */
public class SNIConfig {
    private static final SSLEngineConfigurator NULL_SERVER_CONFIG = new NullSSLEngineConfigurator();

    final SSLEngineConfigurator sslEngineConfigurator;
    final String host;
    final boolean isClientConfig;

    /**
     * @param sslEngineConfigurator {@link SSLEngineConfigurator}, or <tt>null</tt> for the default configuration
     * @return server-side SNI configuration
     */
    public static SNIConfig newServerConfig(final SSLEngineConfigurator sslEngineConfigurator) {
        return new SNIConfig(sslEngineConfigurator, null, false);
    }

    /**
     * @param host the SNI host name to be sent to a server, or <tt>null</tt> to not use SNI extension
     * @return client-side SNI configuration
     */
    public static SNIConfig newClientConfig(final String host) {
        return new SNIConfig(null, host, true);
    }

    /**
     * @param host the SNI host name to be sent to a server, or <tt>null</tt> to not use SNI extension
     * @param sslEngineConfigurator {@link SSLEngineConfigurator}, or <tt>null</tt> for the default configuration
     * @return client-side SNI configuration
     */
    public static SNIConfig newClientConfig(final String host, final SSLEngineConfigurator sslEngineConfigurator) {
        return new SNIConfig(sslEngineConfigurator, host, true);
    }

    /**
     * @param host
     * @return SNIConfig for {@link Connection}, whose SNI host wasn't recognized as supported, so the {@link Connection}
     * has to be closed
     */
    public static SNIConfig failServerConfig(final String host) {
        return new SNIConfig(NULL_SERVER_CONFIG, host, false);
    }

    private SNIConfig(final SSLEngineConfigurator engineConfig, final String host, final boolean isClientConfig) {
        this.sslEngineConfigurator = engineConfig;
        this.host = host;
        this.isClientConfig = isClientConfig;
    }

    private static class NullSSLEngineConfigurator extends SSLEngineConfigurator {

        public NullSSLEngineConfigurator() {
        }

        @Override
        public SSLEngine createSSLEngine(String peerHost, int peerPort) {
            throw new IllegalStateException("No SNI config found");
        }

        @Override
        public SSLEngine createSSLEngine() {
            throw new IllegalStateException("No SNI config found");
        }

        @Override
        public SSLEngine configure(SSLEngine sslEngine) {
            throw new IllegalStateException("No SNI config found");
        }

        @Override
        public SSLEngineConfigurator copy() {
            return new NullSSLEngineConfigurator();
        }

        @Override
        public SSLEngineConfigurator setEnabledProtocols(String[] enabledProtocols) {
            throw new IllegalStateException("Immutable config");
        }

        @Override
        public SSLEngineConfigurator setEnabledCipherSuites(String[] enabledCipherSuites) {
            throw new IllegalStateException("Immutable config");
        }

        @Override
        public SSLEngineConfigurator setWantClientAuth(boolean wantClientAuth) {
            throw new IllegalStateException("Immutable config");
        }

        @Override
        public SSLEngineConfigurator setNeedClientAuth(boolean needClientAuth) {
            throw new IllegalStateException("Immutable config");
        }

        @Override
        public SSLEngineConfigurator setClientMode(boolean clientMode) {
            throw new IllegalStateException("Immutable config");
        }
    }
}
