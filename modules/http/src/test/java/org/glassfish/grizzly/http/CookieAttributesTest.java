/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly.http;

import org.glassfish.grizzly.utils.Charsets;

import junit.framework.TestCase;

/**
 * Serialization of cookie attributes without a dedicated property, like <code>SameSite</code>.
 */
public class CookieAttributesTest extends TestCase {

    public void testNoAttributesByDefault() {
        Cookie cookie = new Cookie("name", "value");
        assertNull(cookie.getSameSite());
        assertTrue(cookie.getAttributes().isEmpty());
        assertEquals("name=value", cookie.asServerCookieString());
        assertEquals("name=value", bufferString(cookie));
    }

    public void testSameSiteVersion0() {
        Cookie cookie = new Cookie("name", "value");
        cookie.setPath("/");
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        cookie.setSameSite("Strict");

        assertEquals("Strict", cookie.getSameSite());
        assertEquals("Strict", cookie.getAttribute("samesite"));
        assertEquals("name=value; Path=/; Secure; HttpOnly; SameSite=Strict", cookie.asServerCookieString());
        assertEquals("name=value; Path=/; Secure; HttpOnly; SameSite=Strict", bufferString(cookie));
    }

    public void testSameSiteVersion1() {
        Cookie cookie = new Cookie("name", "value");
        cookie.setVersion(1);
        cookie.setSameSite("Lax");

        assertEquals("name=value; Version=1; SameSite=Lax", cookie.asServerCookieString());
        assertEquals("name=value; Version=1; SameSite=Lax", bufferString(cookie));
    }

    public void testValuelessAttributeAndRemoval() {
        Cookie cookie = new Cookie("name", "value");
        cookie.setSameSite("None");
        cookie.setAttribute("Partitioned", "");
        assertEquals("name=value; Partitioned; SameSite=None", cookie.asServerCookieString());

        cookie.setSameSite(null);
        cookie.setAttribute("PARTITIONED", null);
        assertEquals("name=value", cookie.asServerCookieString());
    }

    public void testReservedAttributeRejected() {
        Cookie cookie = new Cookie("name", "value");
        for (String name : new String[] { "Domain", "path", "Max-Age", "Expires", "Secure", "HttpOnly", "Comment", "Version" }) {
            try {
                cookie.setAttribute(name, "x");
                fail(name);
            } catch (IllegalArgumentException expected) {
                // dedicated property
            }
        }
    }

    public void testInvalidValueRejectedOnSerialization() {
        Cookie cookie = new Cookie("name", "value");
        cookie.setSameSite("Lax; Domain=evil.example");
        try {
            cookie.asServerCookieString();
            fail();
        } catch (IllegalArgumentException expected) {
            // no attribute injection
        }
        cookie.setSameSite("Lax\r\nX-Injected: 1");
        try {
            bufferString(cookie);
            fail();
        } catch (IllegalArgumentException expected) {
            // no header injection
        }
    }

    public void testCloneAndRecycle() throws Exception {
        Cookie cookie = new Cookie("name", "value");
        cookie.setSameSite("Lax");
        Cookie clone = (Cookie) cookie.clone();
        clone.setSameSite("Strict");
        assertEquals("Lax", cookie.getSameSite());

        cookie.recycle();
        assertNull(cookie.getSameSite());
        assertEquals("Strict", clone.getSameSite());
    }

    private static String bufferString(Cookie cookie) {
        return cookie.asServerCookieBuffer().toStringContent(Charsets.ASCII_CHARSET);
    }
}
