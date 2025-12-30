/*
 * Copyright (c) 2008, 2024 Oracle and/or its affiliates. All rights reserved.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import javax.net.ssl.SSLParameters;
import org.glassfish.grizzly.Grizzly;

/**
 * Utility class, which helps to configure {@link SSLEngine}.
 *
 * @author Alexey Stashok
 */
public class SSLEngineConfigurator implements SSLEngineFactory {
    private static final Logger LOGGER = Grizzly.logger(SSLEngineConfigurator.class);

    private final Object sync = new Object();

    protected volatile SSLContextConfigurator sslContextConfiguration;

    protected volatile SSLContext sslContext;

    /**
     * Client mode when handshaking.
     */
    protected boolean clientMode;

    /**
     * Parameters to configure {@link SSLEngine}.
     */
    private SSLParameters sslParameters;

    /**
     * Create SSL Engine configuration basing on passed {@link SSLContext}.
     *
     * @param sslContext {@link SSLContext}.
     */
    public SSLEngineConfigurator(SSLContext sslContext) {
        this(sslContext, true, false, false);
    }

    /**
     * Create SSL Engine configuration basing on passed {@link SSLContext}, using passed client mode, need/want client auth
     * parameters.
     *
     * @param sslContext {@link SSLContext}.
     * @param clientMode
     * @param needClientAuth
     * @param wantClientAuth
     */
    public SSLEngineConfigurator(final SSLContext sslContext, final boolean clientMode, final boolean needClientAuth, final boolean wantClientAuth) {
        if (sslContext == null) {
            throw new IllegalArgumentException("SSLContext can not be null");
        }

        this.sslContextConfiguration = null;
        this.sslContext = sslContext;
        this.clientMode = clientMode;

        this.sslParameters = sslContext.getDefaultSSLParameters();
        if (needClientAuth) {
            sslParameters.setNeedClientAuth(true);
        }

        if (wantClientAuth) {
            sslParameters.setWantClientAuth(true);
        }
    }

    /**
     * Create SSL Engine configuration basing on passed {@link SSLContextConfigurator}. This constructor makes possible to
     * initialize SSLEngine and SSLContext in lazy fashion on first {@link #createSSLEngine()} call.
     *
     * @param sslContextConfiguration {@link SSLContextConfigurator}.
     */
    public SSLEngineConfigurator(SSLContextConfigurator sslContextConfiguration) {
        this(sslContextConfiguration, true, false, false);
    }

    /**
     * Create SSL Engine configuration basing on passed {@link SSLContextConfigurator}. This constructor makes possible to
     * initialize SSLEngine and SSLContext in lazy fashion on first {@link #createSSLEngine()} call.
     *
     * @param sslContextConfiguration {@link SSLContextConfigurator}.
     * @param clientMode
     * @param needClientAuth
     * @param wantClientAuth
     */
    public SSLEngineConfigurator(SSLContextConfigurator sslContextConfiguration, boolean clientMode, boolean needClientAuth, boolean wantClientAuth) {
        if (sslContextConfiguration == null) {
            throw new IllegalArgumentException("SSLContextConfigurator can not be null");
        }

        this.sslContextConfiguration = sslContextConfiguration;
        this.clientMode = clientMode;

        this.sslParameters = new SSLParameters();
        if (needClientAuth) {
            sslParameters.setNeedClientAuth(true);
        }

        if (wantClientAuth) {
            sslParameters.setWantClientAuth(true);
        }
    }

    public SSLEngineConfigurator(SSLEngineConfigurator pattern) {
        this.sslContextConfiguration = pattern.sslContextConfiguration;
        this.sslContext = pattern.sslContext;
        this.clientMode = pattern.clientMode;
        this.sslParameters = copy(pattern.sslParameters);
    }

    protected SSLEngineConfigurator() {
        this.sslParameters = new SSLParameters();
    }

    /**
     * Create and configure {@link SSLEngine} using this context configuration.
     *
     * @return {@link SSLEngine}.
     */
    public SSLEngine createSSLEngine() {
        return createSSLEngine(null, -1);
    }

    /**
     * Create and configure {@link SSLEngine} using this context configuration using advisory peer information.
     * <P>
     * Applications using this factory method are providing hints for an internal session reuse strategy.
     * <P>
     * Some cipher suites (such as Kerberos) require remote hostname information, in which case peerHost needs to be
     * specified.
     *
     * @param peerHost the non-authoritative name of the host
     * @param peerPort the non-authoritative port
     *
     * @return {@link SSLEngine}.
     */
    @Override
    public SSLEngine createSSLEngine(final String peerHost, final int peerPort) {
        if (sslContext == null) {
            synchronized (sync) {
                if (sslContext == null) {
                    sslContext = sslContextConfiguration.createSSLContext(true);
                }
            }
        }

        final SSLEngine sslEngine = sslContext.createSSLEngine(peerHost, peerPort);
        configure(sslEngine);

        return sslEngine;
    }

    /**
     * Configure passed {@link SSLEngine}, using current configurator settings
     *
     * @param sslEngine {@link SSLEngine} to configure.
     * @return configured {@link SSLEngine}.
     */
    public SSLEngine configure(final SSLEngine sslEngine) {

        SSLParameters params = copy(sslParameters);

        String[] enabledCipherSuites = params.getCipherSuites();
        if (enabledCipherSuites != null) {
            enabledCipherSuites = configureEnabledCiphers(sslEngine, enabledCipherSuites);
            params.setCipherSuites(enabledCipherSuites);
        }

        String[] enabledProtocols = params.getProtocols();
        if (enabledProtocols != null) {
            enabledProtocols = configureEnabledProtocols(sslEngine,
                            enabledProtocols);
            params.setProtocols(enabledProtocols);
        }

        sslEngine.setUseClientMode(clientMode);
        sslEngine.setSSLParameters(sslParameters);

        return sslEngine;
    }

    /**
     * Will {@link SSLEngine} be configured to work in client mode.
     *
     * @return <tt>true</tt>, if {@link SSLEngine} will be configured to work in <tt>client</tt> mode, or <tt>false</tt> for
     * <tt>server</tt> mode.
     */
    public boolean isClientMode() {
        return clientMode;
    }

    /**
     * Set {@link SSLEngine} to be configured to work in client mode.
     *
     * @param clientMode <tt>true</tt>, if {@link SSLEngine} will be configured to work in <tt>client</tt> mode, or
     * <tt>false</tt> for <tt>server</tt> mode.
     * @return this SSLEngineConfigurator
     */
    public SSLEngineConfigurator setClientMode(boolean clientMode) {
        this.clientMode = clientMode;
        return this;
    }

    public boolean isNeedClientAuth() {
        return sslParameters.getNeedClientAuth();
    }

    public SSLEngineConfigurator setNeedClientAuth(boolean needClientAuth) {
        sslParameters.setNeedClientAuth(needClientAuth);
        return this;
    }

    public boolean isWantClientAuth() {
        return sslParameters.getWantClientAuth();
    }

    public SSLEngineConfigurator setWantClientAuth(boolean wantClientAuth) {
        sslParameters.setWantClientAuth(wantClientAuth);
        return this;
    }

    /**
     * Apply {@link SSLParameters} to this SSLEngineConfigurator.
     *
     * @param sslParameters
     * @return this SSLEngineConfigurator
     */
    public SSLEngineConfigurator setSSLParameters(SSLParameters sslParameters) {
        this.sslParameters = copy(sslParameters);
        return this;
    }

    /**
     * @return an array of enabled cipher suites. Modifications made on the array
     *      content won't be propagated to SSLEngineConfigurator
     */
    public String[] getEnabledCipherSuites() {
        return sslParameters.getCipherSuites();
    }

    /**
     * Sets a list of enabled cipher suites. Note: further modifications made on the passed array won't be propagated to
     * SSLEngineConfigurator.
     *
     * @param enabledCipherSuites list of enabled cipher suites
     * @return this SSLEngineConfigurator
     */
    public SSLEngineConfigurator setEnabledCipherSuites(final String[] enabledCipherSuites) {
        sslParameters.setCipherSuites(enabledCipherSuites);
        return this;
    }

    /**
     * @return an array of enabled protocols. Modifications made on the array content won't be propagated to
     * SSLEngineConfigurator
     */
    public String[] getEnabledProtocols() {
        return sslParameters.getProtocols();
    }

    /**
     * Sets a list of enabled protocols. Note: further modifications made on the passed array won't be propagated to
     * SSLEngineConfigurator.
     *
     * @param enabledProtocols list of enabled protocols
     * @return this SSLEngineConfigurator
     */
    public SSLEngineConfigurator setEnabledProtocols(final String[] enabledProtocols) {
        sslParameters.setProtocols(enabledProtocols);
        return this;
    }

    public SSLContext getSslContext() {
        if (sslContext == null) {
            synchronized (sync) {
                if (sslContext == null) {
                    sslContext = sslContextConfiguration.createSSLContext(true);
                }
            }
        }

        return sslContext;
    }

    /**
     * Return the list of allowed protocol.
     *
     * @return String[] an array of supported protocols.
     */
    private static String[] configureEnabledProtocols(SSLEngine sslEngine, String[] requestedProtocols) {

        String[] supportedProtocols = sslEngine.getSupportedProtocols();
        String[] protocols = null;
        ArrayList<String> list = null;
        for (String supportedProtocol : supportedProtocols) {
            /*
             * Check to see if the requested protocol is among the supported protocols, i.e., may be enabled
             */
            for (String protocol : requestedProtocols) {
                protocol = protocol.trim();
                if (supportedProtocol.equals(protocol)) {
                    if (list == null) {
                        list = new ArrayList<>();
                    }
                    list.add(protocol);
                    break;
                }
            }
        }

        if (list != null) {
            protocols = list.toArray(new String[list.size()]);
        }

        return protocols;
    }

    /**
     * Determines the SSL cipher suites to be enabled.
     *
     * @return Array of SSL cipher suites to be enabled, or null if none of the requested ciphers are supported
     */
    private static String[] configureEnabledCiphers(SSLEngine sslEngine, String[] requestedCiphers) {

        String[] supportedCiphers = sslEngine.getSupportedCipherSuites();
        String[] ciphers = null;
        ArrayList<String> list = null;
        for (String supportedCipher : supportedCiphers) {
            /*
             * Check to see if the requested protocol is among the supported protocols, i.e., may be enabled
             */
            for (String cipher : requestedCiphers) {
                cipher = cipher.trim();
                if (supportedCipher.equals(cipher)) {
                    if (list == null) {
                        list = new ArrayList<>();
                    }
                    list.add(cipher);
                    break;
                }
            }
        }

        if (list != null) {
            ciphers = list.toArray(new String[list.size()]);
        }

        return ciphers;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("SSLEngineConfigurator");
        sb.append("{clientMode=").append(clientMode);
        sb.append(", enabledCipherSuites=")
            .append(sslParameters.getCipherSuites() == null ? "null" : Arrays.toString(sslParameters.getCipherSuites()));
        sb.append(", enabledProtocols=")
            .append(sslParameters.getProtocols() == null ? "null" : Arrays.toString(sslParameters.getProtocols()));
        sb.append(", needClientAuth=").append(sslParameters.getNeedClientAuth());
        sb.append(", wantClientAuth=").append(sslParameters.getWantClientAuth());
        sb.append('}');
        return sb.toString();
    }

    public SSLEngineConfigurator copy() {
        return new SSLEngineConfigurator(this);
    }

    private SSLParameters copy(SSLParameters src) {
        SSLParameters dest = new SSLParameters();

        dest.setCipherSuites(src.getCipherSuites());
        dest.setProtocols(src.getProtocols());

        if (src.getNeedClientAuth()) {
            dest.setNeedClientAuth(src.getNeedClientAuth());
        }

        if (src.getWantClientAuth()) {
            dest.setWantClientAuth(src.getWantClientAuth());
        }

        dest.setAlgorithmConstraints(src.getAlgorithmConstraints());
        dest.setApplicationProtocols(src.getApplicationProtocols());
        dest.setEnableRetransmissions(src.getEnableRetransmissions());
        dest.setEndpointIdentificationAlgorithm(src.getEndpointIdentificationAlgorithm());
        dest.setMaximumPacketSize(src.getMaximumPacketSize());
        dest.setSNIMatchers(src.getSNIMatchers());
        dest.setServerNames(src.getServerNames());
        dest.setUseCipherSuitesOrder(src.getUseCipherSuitesOrder());

        return dest;
    }
}
