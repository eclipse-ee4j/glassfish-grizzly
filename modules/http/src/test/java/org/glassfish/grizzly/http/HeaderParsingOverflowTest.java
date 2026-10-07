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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class HeaderParsingOverflowTest {

    @Test
    public void headersOverflowKeepsIllegalStateExceptionContract() {
        HttpCodecFilter.HeaderParsingState state = new HttpCodecFilter.HeaderParsingState();
        state.initialize(null, 0, 16);

        state.checkHeadersOverflow(15);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> state.checkHeadersOverflow(16));
        assertEquals(HttpHeaderTooLargeException.class, e.getClass());
        assertEquals("HTTP packet header is too large", e.getMessage());
    }
}
