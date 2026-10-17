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

package org.glassfish.grizzly.http.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.glassfish.grizzly.http.io.OutputBuffer;
import org.junit.Test;

/**
 * The Writer's char array starts with what the first write needs and grows up to the buffer size, instead of
 * allocating the whole buffer size for every response.
 */
public class WriterCharBufferTest {

    @Test
    public void aSmallResponseGetsASmallCharArray() throws Exception {
        final AtomicInteger capacity = new AtomicInteger(-1);
        final String body = body(run(response -> {
            final Writer writer = response.getWriter();
            writer.write("hello");
            capacity.set(charsArrayLength(response.getOutputBuffer()));
        }));
        assertEquals("hello", body);
        assertTrue("char array of " + capacity.get() + " chars for a 5-char response", capacity.get() > 0 && capacity.get() <= 512);
    }

    /** Writes of every kind and size, before and after the array reaches the buffer size, keep their order. */
    @Test
    public void growingArrayKeepsTheOutput() throws Exception {
        final StringBuilder expected = new StringBuilder();
        final AtomicInteger capacity = new AtomicInteger(-1);
        final String body = body(run(response -> {
            final Writer writer = response.getWriter();
            int n = 0;
            for (int round = 0; round < 40; round++) {
                for (int size : new int[] { 1, 7, 300, 513, 1500, 4096, 9000 }) {
                    final StringBuilder chunk = new StringBuilder();
                    for (int i = 0; i < size; i++) {
                        chunk.append((char) ('a' + n++ % 26));
                    }
                    final String s = chunk.toString();
                    switch ((round + size) % 3) {
                    case 0:
                        writer.write(s);
                        break;
                    case 1:
                        writer.write(s.toCharArray());
                        break;
                    default:
                        for (int i = 0; i < s.length(); i++) {
                            writer.write(s.charAt(i));
                        }
                    }
                    expected.append(s);
                }
                if (round == 0) {
                    capacity.set(charsArrayLength(response.getOutputBuffer()));
                }
            }
        }));
        assertEquals(expected.length(), body.length());
        assertEquals(expected.toString(), body);
        assertEquals("capacity once the writes went past the buffer size", 8192, capacity.get());
    }

    /**
     * The chars are flushed to bytes where they used to be, also when the buffer size changes after the first char:
     * 2000 chars stay buffered under the 8192 chars the array had, so the response is not committed by them.
     */
    @Test
    public void setBufferSizeAfterTheFirstCharKeepsTheFlushPoint() throws Exception {
        final StringBuilder expected = new StringBuilder("x");
        for (int i = 0; i < 2000; i++) {
            expected.append((char) ('a' + i % 26));
        }
        final String response = run(r -> {
            final Writer writer = r.getWriter();
            writer.write("x");
            r.setBufferSize(600);
            writer.write(expected.substring(1));
            r.setHeader("X-After", "yes");
        });
        assertTrue(response, head(response).contains("\r\nx-after: yes\r\n"));
        assertTrue(response, head(response).contains("\r\ncontent-length: 2001\r\n"));
        assertEquals(expected.toString(), body(response));
    }

    /** A first write longer than the small array takes the whole capacity at once: one array, as before. */
    @Test
    public void aLongerFirstWriteTakesTheWholeArray() throws Exception {
        final AtomicInteger first = new AtomicInteger();
        final AtomicInteger last = new AtomicInteger();
        final String body = body(run(r -> {
            final Writer writer = r.getWriter();
            writer.write("b".repeat(600));
            first.set(System.identityHashCode(charsArray(r.getOutputBuffer())));
            writer.write("c");
            last.set(System.identityHashCode(charsArray(r.getOutputBuffer())));
            assertEquals(8192, charsArrayLength(r.getOutputBuffer()));
        }));
        assertEquals("b".repeat(600) + "c", body);
        assertEquals("arrays allocated", first.get(), last.get());
    }

    /** A refused first write fixes the flush point too, as the whole array used to be allocated before the check. */
    @Test
    public void aRefusedFirstWriteKeepsTheFlushPoint() throws Exception {
        final String chars = "a".repeat(2000);
        final String response = run(r -> {
            final Writer writer = r.getWriter();
            try {
                writer.write("abc", 0, -1);
            } catch (IndexOutOfBoundsException expected) {
                // refused
            }
            r.setBufferSize(600);
            writer.write(chars);
            r.setHeader("X-After", "yes");
        });
        assertTrue(response, head(response).contains("\r\nx-after: yes\r\n"));
        assertTrue(response, head(response).contains("\r\ncontent-length: 2000\r\n"));
        assertEquals(chars, body(response));
    }

    /** A negative length is refused as it was, with an IndexOutOfBoundsException, also on the first write. */
    @Test
    public void aNegativeLengthIsRefused() throws Exception {
        final String body = body(run(r -> {
            final Writer writer = r.getWriter();
            for (final Runnable write : new Runnable[] { () -> uncheckedWrite(writer, "abc", -1), () -> uncheckedWrite(writer, "abc".toCharArray(), -1) }) {
                try {
                    write.run();
                    writer.write("accepted;");
                } catch (IndexOutOfBoundsException e) {
                    writer.write("refused;");
                }
            }
        }));
        assertEquals("refused;refused;", body);
    }

    /** Surrogate pairs written around the end of the first, small array come out whole when the array grows. */
    @Test
    public void surrogatePairsAcrossTheFirstArray() throws Exception {
        final StringBuilder expected = new StringBuilder();
        final String response = run(r -> {
            r.setCharacterEncoding("UTF-8");
            final Writer writer = r.getWriter();
            final String ascii = "a".repeat(511);
            final String pairs = "\uD83D\uDE00".repeat(300);
            writer.write(ascii);
            writer.write(pairs);
            writer.write(ascii.toCharArray());
            for (int i = 0; i < pairs.length(); i++) {
                writer.write(pairs.charAt(i));
            }
            expected.append(ascii).append(pairs).append(ascii).append(pairs);
        });
        final String body = new String(body(response).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        assertEquals(expected.toString(), body);
    }

    private static void uncheckedWrite(final Writer writer, final String s, final int len) {
        try {
            writer.write(s, 0, len);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void uncheckedWrite(final Writer writer, final char[] c, final int len) {
        try {
            writer.write(c, 0, len);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String body(final String response) {
        return response.substring(response.indexOf("\r\n\r\n") + 4);
    }

    private static String head(final String response) {
        return response.substring(0, response.indexOf("\r\n\r\n") + 2).toLowerCase();
    }

    private interface Body {
        void write(Response response) throws Exception;
    }

    private static String run(final Body body) throws Exception {
        final HttpServer server = new HttpServer();
        final NetworkListener listener = new NetworkListener("writer", "127.0.0.1", 0);
        server.addListener(listener);
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                body.write(response);
            }
        }, "/");
        server.start();
        try (Socket socket = new Socket("127.0.0.1", listener.getPort())) {
            socket.setSoTimeout(10_000);
            final OutputStream out = socket.getOutputStream();
            out.write("GET / HTTP/1.0\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            final InputStream in = socket.getInputStream();
            final ByteArrayOutputStream response = new ByteArrayOutputStream();
            in.transferTo(response);
            final String text = response.toString(StandardCharsets.ISO_8859_1);
            assertTrue(text, text.startsWith("HTTP/1.1 200") || text.startsWith("HTTP/1.0 200"));
            return text;
        } finally {
            server.shutdownNow();
        }
    }

    private static int charsArrayLength(final OutputBuffer outputBuffer) throws ReflectiveOperationException {
        final char[] chars = charsArray(outputBuffer);
        return chars == null ? 0 : chars.length;
    }

    private static char[] charsArray(final OutputBuffer outputBuffer) throws ReflectiveOperationException {
        final Field field = OutputBuffer.class.getDeclaredField("charsArray");
        field.setAccessible(true);
        return (char[]) field.get(outputBuffer);
    }
}
