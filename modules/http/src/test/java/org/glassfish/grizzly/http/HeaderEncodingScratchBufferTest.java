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

package org.glassfish.grizzly.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.memory.ByteBufferManager;
import org.glassfish.grizzly.memory.MemoryManager;
import org.junit.Test;

/**
 * The per-header scratch buffer used to encode Strings into non-array Buffers is created on first use, and encoding
 * into such a Buffer also works without one.
 */
public class HeaderEncodingScratchBufferTest {

    @Test
    public void scratchBufferIsCreatedOnFirstUse() throws Exception {
        final HttpRequestPacket request = HttpRequestPacket.builder().method("GET").uri("/").protocol("HTTP/1.1").build();
        final Field field = HttpHeader.class.getDeclaredField("tmpHeaderEncodingBuffer");
        field.setAccessible(true);
        assertNull(field.get(request));

        final byte[] buffer = request.getTempHeaderEncodingBuffer();
        assertNotNull(buffer);
        assertSame(buffer, request.getTempHeaderEncodingBuffer());
    }

    @Test
    public void stringHeadersEncodeIntoADirectBufferWithoutScratchBuffer() {
        final MemoryManager direct = new ByteBufferManager(true);
        final MimeHeaders headers = new MimeHeaders();
        headers.setValue("Content-Type").setString("text/plain;charset=UTF-8");
        headers.setValue("X-Long").setString("z".repeat(2000));
        headers.setValue("X-Control").setString("a\u0001b"); // control characters are replaced with a space

        Buffer out = direct.allocate(64);
        assertFalse(out.hasArray());
        out = HttpCodecFilter.encodeMimeHeaders(direct, out, headers, null);
        out.flip();
        final byte[] bytes = new byte[out.remaining()];
        out.get(bytes);
        assertEquals("Content-Type: text/plain;charset=UTF-8\r\nX-Long: " + "z".repeat(2000) + "\r\nX-Control: a b\r\n",
                new String(bytes, StandardCharsets.ISO_8859_1));
    }
}
