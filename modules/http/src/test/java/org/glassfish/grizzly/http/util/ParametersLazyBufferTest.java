/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
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
}
