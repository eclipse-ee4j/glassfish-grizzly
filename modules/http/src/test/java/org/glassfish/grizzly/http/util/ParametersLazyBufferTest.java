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

package org.glassfish.grizzly.http.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * The scratch CharChunks of {@link Parameters} are allocated on first use and grow past their initial size.
 */
public class ParametersLazyBufferTest {

    @Test
    public void scratchBuffersAreNotAllocatedUpFront() {
        final Parameters parameters = new Parameters();
        assertNull(parameters.tmpNameC.getBuffer());
        assertNull(parameters.tmpValueC.getBuffer());
    }

    @Test
    public void longParameterFromString() {
        final String name = "n".repeat(300);
        final String value = "v".repeat(500);
        final Parameters parameters = new Parameters();
        parameters.setQueryStringEncoding(StandardCharsets.UTF_8);
        parameters.processParameters(name + "=" + value.substring(0, 250) + "%20" + value.substring(250) + "&a=b");
        assertEquals(value.substring(0, 250) + " " + value.substring(250), parameters.getParameter(name));
        assertEquals("b", parameters.getParameter("a"));
    }

    @Test
    public void longParameterFromChars() {
        final String name = "x".repeat(400);
        final String value = "y".repeat(700);
        final Parameters parameters = new Parameters();
        parameters.setQueryStringEncoding(StandardCharsets.UTF_8);
        final char[] chars = ("a=b&" + name + "=" + value).toCharArray();
        parameters.processParameters(chars, 0, chars.length);
        assertEquals(value, parameters.getParameter(name));
        assertEquals("b", parameters.getParameter("a"));
    }

    @Test
    public void emptyNamesAndValuesOnFirstUse() {
        final Parameters p = new Parameters();
        p.setQueryStringEncoding(StandardCharsets.UTF_8);
        p.processParameters("a=&b&=c&d=%41"); // String path, the first append has length 0
        assertEquals("", p.getParameter("a"));
        assertEquals("", p.getParameter("b"));
        assertNull(p.getParameter(""));
        assertEquals("A", p.getParameter("d"));

        final Parameters q = new Parameters();
        q.setQueryStringEncoding(StandardCharsets.UTF_8);
        final char[] chars = "x=&y=%41".toCharArray(); // char[] path
        q.processParameters(chars, 0, chars.length);
        assertEquals("", q.getParameter("x"));
        assertEquals("A", q.getParameter("y"));
    }

    @Test
    public void reuseAfterRecycleAndLimit() {
        final Parameters p = new Parameters();
        p.setLimit(1);
        p.setQueryStringEncoding(StandardCharsets.UTF_8);
        p.processParameters("a=1&b=2");
        assertEquals("1", p.getParameter("a"));
        assertNull(p.getParameter("b"));
        p.recycle();
        p.setQueryStringEncoding(StandardCharsets.UTF_8);
        p.processParameters("c=" + "z".repeat(3000));
        assertEquals("z".repeat(3000), p.getParameter("c"));
    }
}
