/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2012, 2017 Oracle and/or its affiliates. All rights reserved.
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

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

class AplnExtensionCompatibility {
    private static final Logger LOG = Logger.getLogger(AplnExtensionCompatibility.class.getName());

    private static final String IMPL_CLASS_NAME = "sun.security.ssl.SSLEngineImpl";
    private static final String METHOD_NAME = "setHandshakeApplicationProtocolSelector";

    private static AplnExtensionCompatibility INSTANCE;

    private final boolean protocolSelectorSetterInApi;
    private final boolean protocolSelectorSetterInImpl;

    public static synchronized AplnExtensionCompatibility getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AplnExtensionCompatibility();
        }
        return INSTANCE;
    }


    public boolean isAlpnExtensionAvailable() {
        return isProtocolSelectorSetterInApi() || isProtocolSelectorSetterInImpl();
    }


    public boolean isProtocolSelectorSetterInApi() {
        return protocolSelectorSetterInApi;
    }


    public boolean isProtocolSelectorSetterInImpl() {
        return protocolSelectorSetterInImpl;
    }


    public Method getProtocolSelectorSetter(final SSLEngine engine) {
        Objects.requireNonNull(engine, "engine");
        try {
            // newer JSSE versions implement this method.
            // some JDK8 versions (Zulu 8u265) don't see the method as public on impl
            final Class<? extends SSLEngine> engineClass;
            if (isHandshakeSetterInApi()) {
                engineClass = SSLEngine.class;
            } else {
                engineClass = engine.getClass();
            }
            return engineClass.getMethod(METHOD_NAME, BiFunction.class);
        } catch (final NoSuchMethodException e) {
            throw new IllegalArgumentException("The method public void setHandshakeApplicationProtocolSelector("
                + "BiFunction<SSLEngine, List<String>, String> selector) is not declared by"
                + " the " + engine.getClass().getName() + ".", e);
        }
    }


    private AplnExtensionCompatibility() {
        this.protocolSelectorSetterInApi = isHandshakeSetterInApi();
        this.protocolSelectorSetterInImpl = isHandshakeSetterInImpl();
    }


    private static boolean isHandshakeSetterInImpl() {
        try {
            Class.forName(IMPL_CLASS_NAME).getMethod(METHOD_NAME, BiFunction.class);
            return true;
        } catch (final IllegalAccessError e) {
            LOG.config(() -> "The class " + IMPL_CLASS_NAME + " is not accessible.");
            return false;
        } catch (final ClassNotFoundException | NoClassDefFoundError e) {
            LOG.config(() -> "The class " + IMPL_CLASS_NAME + " cloud not be found.");
            return false;
        } catch (final NoSuchMethodException e) {
            LOG.config(() -> "The method public void setHandshakeApplicationProtocolSelector("
                + "BiFunction<SSLEngine, List<String>, String> selector) is not declared by"
                + " the " + IMPL_CLASS_NAME + " class.");
            return false;
        }
    }


    private static boolean isHandshakeSetterInApi() {
        try {
            SSLEngine.class.getMethod(METHOD_NAME, BiFunction.class);
            return true;
        } catch (final NoSuchMethodException e) {
            LOG.config("The method public void setHandshakeApplicationProtocolSelector("
                + "BiFunction<SSLEngine, List<String>, String> selector) is not declared by"
                + " the " + SSLEngine.class.getName() + ".");
            return false;
        }
    }


    @Override
    public String toString() {
        return super.toString() + "ALPN available: " + isAlpnExtensionAvailable()
            + ", setHandshakeApplicationProtocolSelector in API: " + isProtocolSelectorSetterInApi()
            + ", setHandshakeApplicationProtocolSelector in impl: " + isProtocolSelectorSetterInImpl();
    }
}
