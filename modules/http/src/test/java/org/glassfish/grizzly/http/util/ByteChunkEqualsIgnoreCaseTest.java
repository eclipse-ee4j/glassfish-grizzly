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
