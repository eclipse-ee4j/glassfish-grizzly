/*
 * Copyright (c) 2025 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.websockets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.logging.Logger;

import org.glassfish.grizzly.websockets.rfc6455.RFC6455HandShake;
import org.junit.Test;

public class HandShakeTest {
    private static final Logger LOGGER = Logger.getLogger("HandShakeTest");
    private static String SSL = "wss://localhost:8443";
    private static String NON_SSL = "ws://localhost:8080";
    private static String RESOURCE_PATH = "/websocket";

    @Test
    public void testOrigin() throws URISyntaxException {
        // non-ssl
        HandShake handshake = new RFC6455HandShake(new URI(NON_SSL + RESOURCE_PATH));
        LOGGER.info("Handshake: isSecure=" + handshake.isSecure() + ", headers: " + handshake.composeHeaders().getHttpHeader());
        assertEquals(NON_SSL, handshake.getOrigin());
        assertFalse(handshake.isSecure());
        assertEquals(NON_SSL + RESOURCE_PATH, handshake.getLocation());

        // ssl
        handshake = new RFC6455HandShake(new URI(SSL + RESOURCE_PATH));
        LOGGER.info("Handshake: isSecure=" + handshake.isSecure() + ", headers: " + handshake.composeHeaders().getHttpHeader());
        assertEquals(SSL, handshake.getOrigin());
        assertTrue(handshake.isSecure());
        assertEquals(SSL + RESOURCE_PATH, handshake.getLocation());


    }
}
