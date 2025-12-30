/*
 * Copyright (c) 2011, 2020 Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022 Contributors to Eclipse Foundation. All rights reserved.
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

package org.glassfish.grizzly;

import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.junit.Test;

public class FileTransferTest {

    private static final int PORT = 3773;

    // ------------------------------------------------------------ Test Methods

    @Test
    public void testSimpleFileTransfer() throws Exception {
        TCPNIOTransport t = TCPNIOTransportBuilder.newInstance().build();
        FilterChainBuilder builder = FilterChainBuilder.stateless();
        final File f = generateTempFile(1024 * 1024);
        builder.add(new TransportFilter());
        builder.add(new BaseFilter() {
            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                ctx.write(new FileTransfer(f));
                return ctx.getStopAction();
            }
        });
        t.setProcessor(builder.build());
        t.bind(PORT);
        t.start();

        TCPNIOTransport client = TCPNIOTransportBuilder.newInstance().build();
        FilterChainBuilder clientChain = FilterChainBuilder.stateless();
        final SafeFutureImpl<File> future = SafeFutureImpl.create();
        final File temp = Files.createTempFile("grizzly-download-", ".tmp").toFile();
        temp.deleteOnExit();
        final FileOutputStream out = new FileOutputStream(temp);
        final AtomicInteger total = new AtomicInteger(0);
        clientChain.add(new TransportFilter());
        clientChain.add(new BaseFilter() {
            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                ctx.write(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, "."));
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                Buffer b = ctx.getMessage();
                ByteBuffer bb = b.toByteBuffer();
                total.addAndGet(b.remaining());
                out.getChannel().write(bb);
                if (total.get() == f.length()) {
                    future.result(temp);
                }
                return ctx.getStopAction();
            }
        });
        client.setProcessor(clientChain.build());
        client.start();
        client.connect("localhost", PORT);
        long start = System.currentTimeMillis();
        BigInteger testSum = getMDSum(future.get(10, TimeUnit.SECONDS));
        long stop = System.currentTimeMillis();
        BigInteger controlSum = getMDSum(f);
        assertTrue(controlSum.equals(testSum));
        System.out.println("File transfer completed in " + (stop - start) + " ms.");
    }

    @Test
    public void negativeFileTransferAPITest() throws Exception {
        try {
            new FileTransfer(null);
            fail("Expected IllegalArgumentException to be thrown, FileTransfer(null)");
        } catch (NullPointerException npe) {
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }

        try {
            new FileTransfer(null, 0, 1);
            fail("Expected IllegalArgumentException to be thrown, FileTransfer(null, 0, 1)");
        } catch (IllegalArgumentException iae) {
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }

        File f = new File("foo");
        try {
            new FileTransfer(f, 0, 1);
            fail("Expected IllegalArgumentException to be thrown, FileTransfer(new File('foo'), 0, 1");
        } catch (IllegalArgumentException iae) {
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }

        f = new File(System.getProperty("java.io.tmpdir"));
        try {
            new FileTransfer(f, 0, 1);
            fail("Expected IllegalArgumentException to be thrown, FileTransfer(new File(System.getProperty(\"java.io.tmpdir\")), 0, 1)");
        } catch (IllegalArgumentException iae) {
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }

        f = Files.createTempFile("grizzly-test-", ".tmp").toFile();
        f.deleteOnExit();
        new FileOutputStream(f).write(1);

        if (f.setReadable(false) && !f.canRead()) { // skip this check if setReadable returned false
            try {
                new FileTransfer(f, 0, 1);
                fail("Expected IllegalArgumentException to be thrown, f.setReadable(false); FileTransfer(f, 0, 1)");
            } catch (IllegalArgumentException iae) {
                // noinspection ResultOfMethodCallIgnored
                f.setReadable(true);
            } catch (Exception e) {
                fail("Unexpected exception type: " + e);
            }
        }

        try {
            new FileTransfer(f, -1, 1);
            fail("Expected IllegalArgumentException to be thrown, FileTransfer(f, -1, 1)");
        } catch (IllegalArgumentException iae) {
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }

        try {
            new FileTransfer(f, 0, -1);
            fail("Expected IllegalArgumentException to be thrown, FileTransfer(f, 0, -1)");
        } catch (IllegalArgumentException iae) {
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }

        try {
            new FileTransfer(f, 2, 1);
            fail("Expected IllegalArgumentException to be thrown, FileTransfer(f, 2, 1)");
        } catch (IllegalArgumentException iae) {
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }
    }

    // --------------------------------------------------------- Private Methods

    private static BigInteger getMDSum(final File f) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] b = new byte[8192];
        FileInputStream in = new FileInputStream(f);
        int len;
        while ((len = in.read(b)) != -1) {
            digest.update(b, 0, len);
        }
        return new BigInteger(digest.digest());
    }

    private static File generateTempFile(final int size) throws IOException {
        final File f = Files.createTempFile("grizzly-temp-" + size, ".tmp").toFile();
        Random r = new Random();
        byte[] data = new byte[8192];
        r.nextBytes(data);
        FileOutputStream out = new FileOutputStream(f);
        int total = 0;
        int remaining = size;
        while (total < size) {
            int len = remaining > 8192 ? 8192 : remaining;
            out.write(data, 0, len);
            total += len;
            remaining -= len;
        }
        f.deleteOnExit();
        return f;
    }
}
