/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.BuffersBuffer;
import org.glassfish.grizzly.memory.MemoryManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MimeHeadersTest {

    MimeHeaders mimeHeaders;

    @Before
    public void setUp() {
        mimeHeaders = new MimeHeaders();
        mimeHeaders.addValue("custom-before").setString("one");
        mimeHeaders.addValue("custom-before").setString("two");
        mimeHeaders.addValue("custom-before").setString("three");
        mimeHeaders.setValue("custom-before-2").setString("one");
        mimeHeaders.mark();
        mimeHeaders.addValue("custom-after").setString("one");
        mimeHeaders.addValue("custom-after").setString("two");
        mimeHeaders.addValue("custom-after").setString("three");
        mimeHeaders.setValue("custom-after-2").setString("one");
    }

    @Test
    public void testNames() throws Exception {
        final String[] expectedNames = { "custom-before", "custom-before-2", "custom-after", "custom-after-2" };
        Iterable<String> result = mimeHeaders.names();
        List<String> list = new ArrayList<>();
        for (String s : result) {
            list.add(s);
        }
        Assert.assertArrayEquals(expectedNames, list.toArray(new String[list.size()]));
    }

    @Test
    public void testTrailerNames() throws Exception {
        final String[] expectedNames = { "custom-after", "custom-after-2" };
        Iterable<String> result = mimeHeaders.trailerNames();
        List<String> list = new ArrayList<>();
        for (String s : result) {
            list.add(s);
        }
        Assert.assertArrayEquals(expectedNames, list.toArray(new String[list.size()]));
    }

    @Test
    public void testValues() throws Exception {
        final String[] expectedValuesSet1 = { "one", "two", "three" };
        final String[] expectedValuesSet2 = { "one" };
        Iterable<String> result = mimeHeaders.values("custom-before");
        List<String> list = new ArrayList<>();
        for (String s : result) {
            list.add(s);
        }
        Assert.assertArrayEquals(expectedValuesSet1, list.toArray(new String[list.size()]));

        result = mimeHeaders.values("custom-before-2");
        list = new ArrayList<>();
        for (String s : result) {
            list.add(s);
        }
        Assert.assertArrayEquals(expectedValuesSet2, list.toArray(new String[list.size()]));

        result = mimeHeaders.values("custom-after");
        list = new ArrayList<>();
        for (String s : result) {
            list.add(s);
        }
        Assert.assertArrayEquals(expectedValuesSet1, list.toArray(new String[list.size()]));

        result = mimeHeaders.values("custom-after-2");
        list = new ArrayList<>();
        for (String s : result) {
            list.add(s);
        }
        Assert.assertArrayEquals(expectedValuesSet2, list.toArray(new String[list.size()]));
    }

    @Test
    public void testTrailerValues() throws Exception {
        final String[] expectedValuesSet1 = { "one", "two", "three" };
        final String[] expectedValuesSet2 = { "one" };
        final String[] emptySet = {};

        Iterable<String> result = mimeHeaders.trailerValues("custom-before");
        List<String> list = new ArrayList<>();
        for (String s : result) {
            list.add(s);
        }
        Assert.assertArrayEquals(emptySet, list.toArray(new String[list.size()]));

        result = mimeHeaders.trailerValues("custom-before-2");
        list = new ArrayList<>();
        for (String s : result) {
            list.add(s);
        }
        Assert.assertArrayEquals(emptySet, list.toArray(new String[list.size()]));

        result = mimeHeaders.trailerValues("custom-after");
        list = new ArrayList<>();
        for (String s : result) {
            list.add(s);
        }
        Assert.assertArrayEquals(expectedValuesSet1, list.toArray(new String[list.size()]));

        result = mimeHeaders.trailerValues("custom-after-2");
        list = new ArrayList<>();
        for (String s : result) {
            list.add(s);
        }
        Assert.assertArrayEquals(expectedValuesSet2, list.toArray(new String[list.size()]));
    }

    @Test
    public void testForbiddenTrailerNamesFromParsedBytesAreRejected() {
        // as the parser does: the name is a slice of the whole input buffer
        final byte[] input = "0\r\nhost: evil\r\nx-checksum: abc\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        final int before = mimeHeaders.trailerSize();

        Assert.assertSame(MimeHeaders.NOOP_CHUNK, mimeHeaders.addValue(input, 3, 4)); // "host" is not allowed in trailers
        Assert.assertEquals(before, mimeHeaders.trailerSize());

        mimeHeaders.addValue(input, 15, 10).setString("abc"); // "x-checksum"
        Assert.assertEquals(before + 1, mimeHeaders.trailerSize());
        Assert.assertEquals("abc", mimeHeaders.getHeader("x-checksum"));
        Assert.assertNull(mimeHeaders.getHeader("host"));
    }

    @Test
    public void testForbiddenTrailerNamesAreRejectedInTurkishLocale() {
        final Locale defaultLocale = Locale.getDefault();
        final Locale displayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        final Locale formatLocale = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            // "IF-MATCH".toLowerCase() is "ıf-match" (dotless i) in Turkish
            final byte[] input = "IF-MATCH".getBytes(StandardCharsets.ISO_8859_1);
            Assert.assertSame(MimeHeaders.NOOP_CHUNK, mimeHeaders.addValue(input, 0, input.length));
            Assert.assertSame(MimeHeaders.NOOP_CHUNK, mimeHeaders.addValue("IF-MATCH"));
            final Buffer buffer = Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, "IF-MATCH", StandardCharsets.ISO_8859_1);
            Assert.assertSame(MimeHeaders.NOOP_CHUNK, mimeHeaders.addValue(buffer, 0, 8));
        } finally {
            Locale.setDefault(defaultLocale);
            Locale.setDefault(Locale.Category.DISPLAY, displayLocale);
            Locale.setDefault(Locale.Category.FORMAT, formatLocale);
        }
    }

    @Test
    public void testForbiddenTrailerNamesFromParsedBufferAreRejected() {
        // chunked trailers from the wire are parsed through this overload
        final MemoryManager mm = MemoryManager.DEFAULT_MEMORY_MANAGER;
        final Buffer input = Buffers.wrap(mm, "0\r\nhost: evil\r\nx-checksum: abc\r\n\r\n", StandardCharsets.ISO_8859_1);
        final int pos = input.position();
        final int lim = input.limit();
        final int before = mimeHeaders.trailerSize();

        Assert.assertSame(MimeHeaders.NOOP_CHUNK, mimeHeaders.addValue(input, 3, 4));
        Assert.assertEquals(pos, input.position());
        Assert.assertEquals(lim, input.limit());
        mimeHeaders.addValue(input, 15, 10).setString("abc");
        Assert.assertEquals(before + 1, mimeHeaders.trailerSize());
        Assert.assertNull(mimeHeaders.getHeader("host"));

        // name split across two segments of a composite buffer
        final Buffer composite = BuffersBuffer.create(mm, Buffers.wrap(mm, "0\r\nho", StandardCharsets.ISO_8859_1),
                Buffers.wrap(mm, "st: evil\r\n\r\n", StandardCharsets.ISO_8859_1));
        final int cpos = composite.position();
        final int clim = composite.limit();
        Assert.assertSame(MimeHeaders.NOOP_CHUNK, mimeHeaders.addValue(composite, 3, 4));
        Assert.assertEquals(cpos, composite.position());
        Assert.assertEquals(clim, composite.limit());
    }

    @Test
    public void testNoopChunkIgnoresBytes() {
        final byte[] input = "Host".getBytes(StandardCharsets.ISO_8859_1);
        final DataChunk noop = mimeHeaders.addValue(input, 0, input.length);
        Assert.assertSame(MimeHeaders.NOOP_CHUNK, noop);
        noop.setBytes("evil".getBytes(StandardCharsets.ISO_8859_1));
        noop.setBytes("evil".getBytes(StandardCharsets.ISO_8859_1), 0, 4);
        noop.setBuffer(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, "evil", StandardCharsets.ISO_8859_1));
        final DataChunk evil = DataChunk.newInstance();
        evil.setString("evil");
        noop.set(evil, 0, 4);
        Assert.assertTrue(MimeHeaders.NOOP_CHUNK.isNull());
    }
}
