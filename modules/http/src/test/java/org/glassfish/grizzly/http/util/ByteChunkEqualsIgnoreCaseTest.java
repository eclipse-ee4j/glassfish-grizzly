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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class ByteChunkEqualsIgnoreCaseTest {

    @Test
    public void sameArrayDifferentOffsets() {
        // header names parsed from one request all live in the same input array
        final byte[] input = "host: a\r\nvary: b\r\n".getBytes(StandardCharsets.ISO_8859_1);
        assertFalse(ByteChunk.equalsIgnoreCase(input, 0, 4, input, 9, 4));
        assertTrue(ByteChunk.equalsIgnoreCase(input, 0, 4, input, 0, 4));

        final byte[] twice = "Accept-Encoding;ACCEPT-ENCODING".getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(ByteChunk.equalsIgnoreCase(twice, 0, 15, twice, 16, 15));
        // the case-sensitive variant had the same shortcut
        assertFalse(ByteChunk.equals(input, 0, 4, input, 9, 4));
        assertTrue(ByteChunk.equals(input, 0, 4, input, 0, 4));
        assertFalse(ByteChunk.equals(twice, 0, 15, twice, 16, 15));
        // null arrays of length 0 still compare equal, as before
        assertTrue(ByteChunk.equalsIgnoreCase(null, 0, 0, null, 5, 0));
        assertTrue(ByteChunk.equals(null, 0, 0, null, 5, 0));
    }

    @Test
    public void dataChunksSlicingTheSameInput() {
        final byte[] input = "host: a\r\nvary: b\r\n".getBytes(StandardCharsets.ISO_8859_1);
        final DataChunk host = DataChunk.newInstance();
        host.setBytes(input, 0, 4);
        final ByteChunk vary = new ByteChunk();
        vary.setBytes(input, 9, 4);
        assertFalse(host.equalsIgnoreCase(vary));
    }
}
