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
        final String body = run((response, writer) -> {
            writer.write("hello");
            capacity.set(charsArrayLength(response.getOutputBuffer()));
        });
        assertEquals("hello", body);
        assertTrue("char array of " + capacity.get() + " chars for a 5-char response", capacity.get() > 0 && capacity.get() <= 512);
    }

    /** Writes of every kind and size, before and after the array reaches the buffer size, keep their order. */
    @Test
    public void growingArrayKeepsTheOutput() throws Exception {
        final StringBuilder expected = new StringBuilder();
        final AtomicInteger capacity = new AtomicInteger(-1);
        final String body = run((response, writer) -> {
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
                    synchronized (expected) {
                        expected.append(s);
                    }
                }
                if (round == 0) {
                    capacity.set(charsArrayLength(response.getOutputBuffer()));
                }
            }
        });
        assertEquals(expected.length(), body.length());
        assertEquals(expected.toString(), body);
        assertEquals("capacity once the writes went past the buffer size", 8192, capacity.get());
    }

    private interface Body {
        void write(Response response, Writer writer) throws Exception;
    }

    private static String run(final Body body) throws Exception {
        final HttpServer server = new HttpServer();
        final NetworkListener listener = new NetworkListener("writer", "127.0.0.1", 0);
        server.addListener(listener);
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                body.write(response, response.getWriter());
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
            return text.substring(text.indexOf("\r\n\r\n") + 4);
        } finally {
            server.shutdownNow();
        }
    }

    private static int charsArrayLength(final OutputBuffer outputBuffer) throws ReflectiveOperationException {
        final Field field = OutputBuffer.class.getDeclaredField("charsArray");
        field.setAccessible(true);
        final char[] chars = (char[]) field.get(outputBuffer);
        return chars == null ? 0 : chars.length;
    }
}
