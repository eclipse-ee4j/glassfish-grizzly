/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
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

package org.glassfish.grizzly.http.ajp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.junit.Test;

/**
 * Test the <code>103 Early Hints</code> interim response support over the AJP protocol. Mirrors
 * {@link org.glassfish.grizzly.http.server.EarlyHintsTest}.
 */
public class AjpEarlyHintsTest extends AjpTestBase {

    private static final String LINK_VALUE = "</style.css>; rel=preload; as=style";

    @Test
    public void testEarlyHintsThenFinalResponse() throws Exception {
        startHttpServer(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                response.setHeader("Link", LINK_VALUE);
                response.sendEarlyHints();
                response.setContentType("text/plain");
                response.getWriter().write("done");
            }
        });

        send(new AjpForwardRequestPacket("GET", "/path", 80, port).toByteArray());

        final AjpResponse interim = Utils.parseResponse(readAjpMessage());
        assertEquals(AjpConstants.JK_AJP13_SEND_HEADERS, interim.getType());
        assertEquals(103, interim.getResponseCode());
        assertEquals("Early Hints", interim.getResponseMessage());
        assertEquals(LINK_VALUE, interim.getHeaders().getHeader("Link"));

        // Auto-injected Content-* headers describe the final response and must not leak into the 103 packet.
        assertNull("Content-Type must not be auto-injected into the 103 packet", interim.getHeaders().getHeader("Content-Type"));
        assertNull("Content-Length must not be auto-injected into the 103 packet", interim.getHeaders().getHeader("Content-Length"));

        final AjpResponse finalHeaders = Utils.parseResponse(readAjpMessage());
        assertEquals(AjpConstants.JK_AJP13_SEND_HEADERS, finalHeaders.getType());
        assertEquals(200, finalHeaders.getResponseCode());

        // Headers set before sendEarlyHints() must still appear on the final response.
        assertEquals(LINK_VALUE, finalHeaders.getHeaders().getHeader("Link"));
        assertTrue("expected Content-Type to start with text/plain, got: " + finalHeaders.getHeaders().getHeader("Content-Type"),
                finalHeaders.getHeaders().getHeader("Content-Type").startsWith("text/plain"));
    }

    @Test
    public void testEarlyHintsMayBeSentMultipleTimes() throws Exception {
        startHttpServer(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                response.setHeader("Link", LINK_VALUE);
                response.sendEarlyHints();
                response.sendEarlyHints();
                response.setContentType("text/plain");
                response.getWriter().write("done");
            }
        });

        send(new AjpForwardRequestPacket("GET", "/path", 80, port).toByteArray());

        assertEquals(103, Utils.parseResponse(readAjpMessage()).getResponseCode());
        assertEquals(103, Utils.parseResponse(readAjpMessage()).getResponseCode());
        assertEquals(200, Utils.parseResponse(readAjpMessage()).getResponseCode());
    }
}
