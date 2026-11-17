/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2010, 2020 Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.CompositeBuffer;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.utils.ChunkingFilter;

import junit.framework.TestCase;

public class HttpResponseStreamsTest extends TestCase {

    private static final char[] ALPHA = "abcdefghijklmnopqrstuvwxyz".toCharArray();

    // --------------------------------------------------------- Character Tests

    public void testCharacter001() throws Exception {

        final String[] content = new String[] { "abcdefg", "hijk", "lmnopqrs", "tuvwxyz" };

        StringBuilder sb = new StringBuilder();
        for (int i = 0, len = content.length; i < len; i++) {
            sb.append(content[i]);
        }

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                for (int i = 0, len = content.length; i < len; i++) {
                    writer.write(content[i]);
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter002() throws Exception {

        final char[][] content = new char[][] { "abcdefg".toCharArray(), "hijk".toCharArray(), "lmnopqrs".toCharArray(), "tuvwxyz".toCharArray() };

        StringBuilder sb = new StringBuilder();
        for (int i = 0, len = content.length; i < len; i++) {
            sb.append(content[i]);
        }

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                for (int i = 0, len = content.length; i < len; i++) {
                    writer.write(content[i]);
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter003() throws Exception {

        final StringBuilder sb = buildBuffer(8192); // boundary

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString());
            }
        };

        doTest(s, sb.toString());

        s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString().toCharArray());
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter004() throws Exception {

        final StringBuilder sb = buildBuffer(8194); // boundary + 2

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString());
            }
        };

        doTest(s, sb.toString());

        s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString().toCharArray());
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter005() throws Exception {

        final StringBuilder sb = buildBuffer(8192); // boundary

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                for (int i = 0, len = sb.length(); i < len; i++) {
                    writer.write(sb.charAt(i));
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter006() throws Exception {

        final StringBuilder sb = buildBuffer(8194); // boundary + 2

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                for (int i = 0, len = sb.length(); i < len; i++) {
                    writer.write(sb.charAt(i));
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter007() throws Exception {

        int len = 1024 * 31; // boundary
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString());
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter008() throws Exception {

        int len = 1024 * 31; // boundary
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString().toCharArray());
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter009() throws Exception {

        int len = 1024 * 31 + 8; // boundary + 8
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString().toCharArray());
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter010() throws Exception {

        final int len = 1024 * 33;
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                // write in 3k chunks
                int off = 0;
                int writeLen = 1024 * 3;
                int count = len / writeLen;
                String s = sb.toString();
                for (int i = 0; i < count; i++) {
                    writer.write(s, off, writeLen);
                    off += writeLen;
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter011() throws Exception {

        final int len = 1024 * 33;
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                // write in 3k chunks
                int off = 0;
                int writeLen = 1024 * 3;
                int count = len / writeLen;
                String s = sb.toString();
                for (int i = 0; i < count; i++) {
                    char[] buf = new char[writeLen];
                    s.getChars(off, off + writeLen, buf, 0);
                    writer.write(buf, 0, writeLen);
                    off += writeLen;
                }
            }
        };

        doTest(s, sb.toString());
    }

    public void testCharacter012() throws Exception {

        final int len = 1024 * 9 * 10;
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                // write in 9k chunks
                int off = 0;
                int writeLen = 1024 * 9;
                int count = len / writeLen;
                String s = sb.toString();
                for (int i = 0; i < count; i++) {
                    writer.write(s, off, writeLen);
                    off += writeLen;
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter013() throws Exception {

        final int len = 1024 * 9 * 10;
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                // write in 9k chunks
                int off = 0;
                int writeLen = 1024 * 9;
                int count = len / writeLen;
                String s = sb.toString();
                for (int i = 0; i < count; i++) {
                    char[] buf = new char[writeLen];
                    s.getChars(off, off + writeLen, buf, 0);
                    writer.write(buf, 0, writeLen);
                    off += writeLen;
                }
            }
        };

        doTest(s, sb.toString());
    }

    public void testCharacter014() throws Exception {

        final String[] content = new String[] { "abcdefg", "hijk", "lmnopqrs", "tuvwxyz" };

        StringBuilder sb = new StringBuilder();
        for (int i = 0, len = content.length; i < len; i++) {
            sb.append(content[i]);
        }

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                for (int i = 0, len = content.length; i < len; i++) {
                    writer.write(content[i]);
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter015() throws Exception {

        final char[][] content = new char[][] { "abcdefg".toCharArray(), "hijk".toCharArray(), "lmnopqrs".toCharArray(), "tuvwxyz".toCharArray() };

        StringBuilder sb = new StringBuilder();
        for (int i = 0, len = content.length; i < len; i++) {
            sb.append(content[i]);
        }

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                for (int i = 0, len = content.length; i < len; i++) {
                    writer.write(content[i]);
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter016() throws Exception {

        final StringBuilder sb = buildBuffer(8192); // boundary

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString());
            }
        };

        doTest(s, sb.toString());

        s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString().toCharArray());
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter017() throws Exception {

        final StringBuilder sb = buildBuffer(8194); // boundary + 2

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString());
            }
        };

        doTest(s, sb.toString());

        s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString().toCharArray());
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter018() throws Exception {

        final StringBuilder sb = buildBuffer(8192); // boundary

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                for (int i = 0, len = sb.length(); i < len; i++) {
                    writer.write(sb.charAt(i));
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter019() throws Exception {

        final StringBuilder sb = buildBuffer(8194); // boundary + 2

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                for (int i = 0, len = sb.length(); i < len; i++) {
                    writer.write(sb.charAt(i));
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter020() throws Exception {

        int len = 1024 * 31; // boundary
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString());
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter021() throws Exception {

        int len = 1024 * 31; // boundary
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString().toCharArray());
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter022() throws Exception {

        int len = 1024 * 31 + 8; // boundary + 8
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString().toCharArray());
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter023() throws Exception {

        final int len = 1024 * 33;
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                // write in 3k chunks
                int off = 0;
                int writeLen = 1024 * 3;
                int count = len / writeLen;
                String s = sb.toString();
                for (int i = 0; i < count; i++) {
                    writer.write(s, off, writeLen);
                    off += writeLen;
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter024() throws Exception {

        final int len = 1024 * 33;
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                // write in 3k chunks
                int off = 0;
                int writeLen = 1024 * 3;
                int count = len / writeLen;
                String s = sb.toString();
                for (int i = 0; i < count; i++) {
                    char[] buf = new char[writeLen];
                    s.getChars(off, off + writeLen, buf, 0);
                    writer.write(buf, 0, writeLen);
                    off += writeLen;
                }
            }
        };

        doTest(s, sb.toString());
    }

    public void testCharacter025() throws Exception {

        final int len = 1024 * 9 * 10;
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                // write in 9k chunks
                int off = 0;
                int writeLen = 1024 * 9;
                int count = len / writeLen;
                String s = sb.toString();
                for (int i = 0; i < count; i++) {
                    writer.write(s, off, writeLen);
                    off += writeLen;
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testCharacter026() throws Exception {

        final int len = 1024 * 9 * 10;
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                // write in 9k chunks
                int off = 0;
                int writeLen = 1024 * 9;
                int count = len / writeLen;
                String s = sb.toString();
                for (int i = 0; i < count; i++) {
                    char[] buf = new char[writeLen];
                    s.getChars(off, off + writeLen, buf, 0);
                    writer.write(buf, 0, writeLen);
                    off += writeLen;
                }
            }
        };

        doTest(s, sb.toString());
    }

    public void testCharacter027() throws Exception {

        final StringBuilder sb = buildBuffer(2002); // boundary

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString());
            }
        };

        doTest(s, sb.toString());

        s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                Writer writer = response.getWriter();
                writer.write(sb.toString().toCharArray());
            }
        };

        doTest(s, sb.toString());

    }

    // ------------------------------------------------------------ Binary Tests

    public void testBinary001() throws Exception {

        final byte[][] content = new byte[][] { "abcdefg".getBytes("UTF-8"), "hijk".getBytes("UTF-8"), "lmnopqrs".getBytes("UTF-8"),
                "tuvwxyz".getBytes("UTF-8") };

        StringBuilder sb = new StringBuilder();
        for (int i = 0, len = content.length; i < len; i++) {
            sb.append(new String(content[i], "UTF-8"));
        }

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                OutputStream out = response.getOutputStream();
                for (int i = 0, len = content.length; i < len; i++) {
                    out.write(content[i]);
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testBinary002() throws Exception {

        final StringBuilder sb = buildBuffer(8192); // boundary

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                OutputStream out = response.getOutputStream();
                for (int i = 0, len = sb.length(); i < len; i++) {
                    out.write(sb.charAt(i));
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testBinary003() throws Exception {

        final StringBuilder sb = buildBuffer(8192); // boundary + 2

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                OutputStream out = response.getOutputStream();
                for (int i = 0, len = sb.length(); i < len; i++) {
                    out.write(sb.charAt(i));
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testBinary004() throws Exception {

        int len = 1024 * 31; // boundary
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                OutputStream out = response.getOutputStream();
                out.write(sb.toString().getBytes());
            }
        };

        doTest(s, sb.toString());

    }

    public void testBinary005() throws Exception {

        int len = 1024 * 31 + 8; // boundary + 8
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                OutputStream out = response.getOutputStream();
                out.write(sb.toString().getBytes());
            }
        };

        doTest(s, sb.toString());

    }

    public void testBinary006() throws Exception {

        final int len = 1024 * 33;
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                OutputStream out = response.getOutputStream();
                // write in 3k chunks
                int off = 0;
                int writeLen = 1024 * 3;
                int count = len / writeLen;
                byte[] s = sb.toString().getBytes();
                for (int i = 0; i < count; i++) {
                    out.write(s, off, writeLen);
                    off += writeLen;
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testBinary007() throws Exception {

        final int len = 1024 * 9 * 10;
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                OutputStream out = response.getOutputStream();
                // write in 9k chunks
                int off = 0;
                int writeLen = 1024 * 9;
                int count = len / writeLen;
                byte[] s = sb.toString().getBytes();
                for (int i = 0; i < count; i++) {
                    out.write(s, off, writeLen);
                    off += writeLen;
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testBinary008() throws Exception {

        final byte[][] content = new byte[][] { "abcdefg".getBytes("UTF-8"), "hijk".getBytes("UTF-8"), "lmnopqrs".getBytes("UTF-8"),
                "tuvwxyz".getBytes("UTF-8") };

        StringBuilder sb = new StringBuilder();
        for (int i = 0, len = content.length; i < len; i++) {
            sb.append(new String(content[i], "UTF-8"));
        }

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                OutputStream out = response.getOutputStream();
                for (int i = 0, len = content.length; i < len; i++) {
                    out.write(content[i]);
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testBinary009() throws Exception {

        final StringBuilder sb = buildBuffer(8192); // boundary

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                OutputStream out = response.getOutputStream();
                for (int i = 0, len = sb.length(); i < len; i++) {
                    out.write(sb.charAt(i));
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testBinary010() throws Exception {

        final StringBuilder sb = buildBuffer(8192); // boundary + 2

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                OutputStream out = response.getOutputStream();
                for (int i = 0, len = sb.length(); i < len; i++) {
                    out.write(sb.charAt(i));
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testBinary011() throws Exception {

        int len = 1024 * 31; // boundary
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                OutputStream out = response.getOutputStream();
                out.write(sb.toString().getBytes());
            }
        };

        doTest(s, sb.toString());

    }

    public void testBinary012() throws Exception {

        int len = 1024 * 31 + 8; // boundary + 8
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                OutputStream out = response.getOutputStream();
                out.write(sb.toString().getBytes());
            }
        };

        doTest(s, sb.toString());

    }

    public void testBinary013() throws Exception {

        final int len = 1024 * 33;
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                OutputStream out = response.getOutputStream();
                // write in 3k chunks
                int off = 0;
                int writeLen = 1024 * 3;
                int count = len / writeLen;
                byte[] s = sb.toString().getBytes();
                for (int i = 0; i < count; i++) {
                    out.write(s, off, writeLen);
                    off += writeLen;
                }
            }
        };

        doTest(s, sb.toString());

    }

    public void testBinary014() throws Exception {

        final int len = 1024 * 9 * 10;
        final StringBuilder sb = buildBuffer(len);

        WriteStrategy s = new WriteStrategy() {
            @Override
            public void doWrite(Response response) throws IOException {
                OutputStream out = response.getOutputStream();
                // write in 9k chunks
                int off = 0;
                int writeLen = 1024 * 9;
                int count = len / writeLen;
                byte[] s = sb.toString().getBytes();
                for (int i = 0; i < count; i++) {
                    out.write(s, off, writeLen);
                    off += writeLen;
                }
            }
        };

        doTest(s, sb.toString());

    }

    // --------------------------------------------------------- Private Methods

    private StringBuilder buildBuffer(int len) {
        final StringBuilder sb = new StringBuilder(len);
        for (int i = 0, j = 0; i < len; i++, j++) {
            if (j > 25) {
                j = 0;
            }
            sb.append(ALPHA[j]);
        }
        return sb;
    }

    private void doTest(WriteStrategy strategy, String expectedResult) throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        HttpServer server = HttpServer.createSimpleServer("/tmp", port);
        ServerConfiguration sconfig = server.getServerConfiguration();
        sconfig.addHttpHandler(new TestHttpHandler(strategy), "/*");

        final FutureImpl<String> parseResult = SafeFutureImpl.create();
        TCPNIOTransport ctransport = TCPNIOTransportBuilder.newInstance().build();
        try {
            server.start();

            FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
            clientFilterChainBuilder.add(new TransportFilter());
            clientFilterChainBuilder.add(new ChunkingFilter(1024));
            clientFilterChainBuilder.add(new HttpClientFilter());
            clientFilterChainBuilder.add(new ClientFilter(parseResult, port));
            ctransport.setProcessor(clientFilterChainBuilder.build());

            ctransport.start();

            Future<Connection> connectFuture = ctransport.connect("localhost", port);
            String res = null;
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                res = parseResult.get();
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }
            assertEquals(expectedResult, res);

        } finally {
            server.shutdownNow();
            ctransport.shutdownNow();
        }
    }

    private interface WriteStrategy {

        void doWrite(Response response) throws IOException;

    }

    private static final class TestHttpHandler extends HttpHandler {

        private final WriteStrategy strategy;

        // -------------------------------------------------------- Constructors

        public TestHttpHandler(WriteStrategy strategy) {
            this.strategy = strategy;
        }

        @Override
        public void service(Request req, Response res) throws Exception {

            res.setStatus(HttpStatus.OK_200);
            strategy.doWrite(res);

        }
    }

    private static class ClientFilter extends BaseFilter {
        private final static Logger logger = Grizzly.logger(ClientFilter.class);

        private final CompositeBuffer buf = CompositeBuffer.newBuffer();

        private final FutureImpl<String> completeFuture;

        // number of bytes downloaded
        private volatile int bytesDownloaded;

        private final int port;

        // -------------------------------------------------------- Constructors

        public ClientFilter(FutureImpl<String> completeFuture, final int port) {

            this.completeFuture = completeFuture;
            this.port = port;

        }

        // ------------------------------------------------ Methods from Filters

        @Override
        public NextAction handleConnect(FilterChainContext ctx) throws IOException {
            // Build the HttpRequestPacket, which will be sent to a server
            // We construct HTTP request version 1.1 and specifying the URL of the
            // resource we want to download
            final HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET").uri("/path").protocol(Protocol.HTTP_1_1)
                    .header("Host", "localhost:" + port).build();
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "Connected... Sending the request: {0}", httpRequest);
            }

            // Write the request asynchronously
            ctx.write(httpRequest);

            // Return the stop action, which means we don't expect next filter to process
            // connect event
            return ctx.getStopAction();
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            try {
                // Cast message to a HttpContent
                final HttpContent httpContent = ctx.getMessage();

                logger.log(Level.FINE, "Got HTTP response chunk");

                // Get HttpContent's Buffer
                final Buffer buffer = httpContent.getContent();

                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, "HTTP content size: {0}", buffer.remaining());
                }
                if (buffer.remaining() > 0) {
                    bytesDownloaded += buffer.remaining();

                    buf.append(buffer);

                }

                if (httpContent.isLast()) {
                    if (logger.isLoggable(Level.FINE)) {
                        logger.log(Level.FINE, "Response complete: {0} bytes", bytesDownloaded);
                    }
                    completeFuture.result(buf.toStringContent());
                    close();
                }
            } catch (IOException e) {
                close();
            }

            return ctx.getStopAction();
        }

        @Override
        public NextAction handleClose(FilterChainContext ctx) throws IOException {
            close();
            return ctx.getStopAction();
        }

        private void close() throws IOException {

            if (!completeFuture.isDone()) {
                // noinspection ThrowableInstanceNeverThrown
                completeFuture.failure(new IOException("Connection was closed"));
            }

        }
    }

}
