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
import org.glassfish.grizzly.memory.PooledMemoryManager;
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

    @Test
    public void scratchBufferIsRequestedOnlyForNonArrayOutput() throws Exception {
        final HttpRequestPacket request = HttpRequestPacket.builder().method("GET").uri("/").protocol("HTTP/1.1").build();
        final Field field = HttpHeader.class.getDeclaredField("tmpHeaderEncodingBuffer");
        field.setAccessible(true);

        final Buffer heap = MemoryManager.DEFAULT_MEMORY_MANAGER.allocate(16);
        if (heap.hasArray()) {
            assertNull(request.getTempHeaderEncodingBuffer(heap));
            assertNull(field.get(request));
        }

        final Buffer direct = new ByteBufferManager(true).allocate(16);
        assertFalse(direct.hasArray());
        final byte[] scratch = request.getTempHeaderEncodingBuffer(direct);
        assertNotNull(scratch);
        assertSame(scratch, field.get(request));
    }

    @Test
    public void heapBufferThatTurnsCompositeMidEncodingStillEncodes() {
        final PooledMemoryManager mm = new PooledMemoryManager(false);
        final MimeHeaders headers = new MimeHeaders();
        final String big = "v".repeat(200_000); // larger than the largest pooled buffer: reallocate makes a composite
        headers.setValue("X-A").setString("short");
        headers.setValue("X-Big").setString(big);
        headers.setValue("X-C").setString("tail\u0001");
        Buffer out = mm.allocateAtLeast(2048);
        out = HttpCodecFilter.encodeMimeHeaders(mm, out, headers, out.hasArray() ? null : new byte[512]);
        out.flip();
        final byte[] bytes = new byte[out.remaining()];
        out.get(bytes);
        assertEquals("X-A: short\r\nX-Big: " + big + "\r\nX-C: tail \r\n", new String(bytes, StandardCharsets.ISO_8859_1));
    }
}
