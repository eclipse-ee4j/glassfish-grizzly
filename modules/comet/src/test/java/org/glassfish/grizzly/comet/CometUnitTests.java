/*
 * Copyright (c) 2009, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.comet;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class CometUnitTests /* extends TestCase */ {
    private static final int PORT = 19100;
    private SocketAddress connectadr;
    private final int socketreusedelayMilliSec = 40;
    private static volatile boolean status;
    private static volatile boolean testisdone;
    private final String context = "/cometTextn";
    private final byte joinMessage = 126;
    private final byte[] connectString = ("POST /index.html/comet HTTP/1.1\r\n" + "Content-Type: application/x-www-form-urlencoded; charset=UTF-8\r\n"
            + "Host: localhost\r\n" + "Content-Length: 0\r\n\r\n").getBytes();

    public CometUnitTests(String testName) {
//        super(testName);
    }

//    @Override
    protected void tearDown() throws Exception {
//        super.tearDown();
    }

//    @Override
    protected void setUp() throws Exception {
//        super.setUp();
        init(false);
    }

    protected void init(boolean useconcurrentcomethandler) throws Exception {
        connectadr = new InetSocketAddress("localhost", PORT);
        RuntimeMXBean rmx = ManagementFactory.getRuntimeMXBean();
        System.out.println("JVM: " + rmx.getVmVendor() + " " + rmx.getVmName() + " " + rmx.getVmVersion() + " params: " + rmx.getInputArguments());
    }

    public void testSlug() {

    }

    /*
     * public void testLongPollingSocketReuse() throws Exception{ doActualLogic(true,false,40,20); }
     */
    /*
     * public void testLongPollingNewSocket() throws Exception{ doActualLogic(false,false,6500,64); }
     */

//    public void testStreaming1() throws Throwable {
    // doActualLogic(false,true,15,1,false);
//    }
    /*
     * public void testStreaming2() throws Throwable{ doActualLogic(false,true,21,4, false); }
     * 
     * public void testStreaming3() throws Throwable{ doActualLogic(false,true,21,64, false); }
     */
    /*
     * public void testStreaming5() throws Throwable{ doActualLogic(false,true, 15, 256); }
     */

    protected void doActualLogic(final boolean reuse, final boolean streaming, int secondsPerTest, int threadCount, boolean spreadNotify)
            throws InterruptedException, IOException {
        System.out.println((streaming ? "STREAMING" : "LONG POLLING") + ": " + (reuse ? "SOCKET REUSE" : "NEW SOCKET") + ", client threads: " + threadCount
                + ", spreadNotifyToManyThreads: " + spreadNotify);
        // int cpus = Runtime.getRuntime().availableProcessors();
//        ((DefaultNotificationHandler) CometTestAdapter.cometContext.notificationHandler).
//            setSpreadNotifyToManyToThreads(spreadNotify);
        testisdone = false;
        msgc.set(0);
        CometTestHttpHandler.useStreaming = streaming;
        final CountDownLatch threadsAreDone = new CountDownLatch(threadCount);
        status = true;
        for (int i = 0; i < threadCount; i++) {
            final boolean first = false;
            new Thread("cometUnitTestClient") {
                @Override
                public void run() {
                    try {
                        connectClient(reuse, streaming, first);
                    } catch (Exception ex) {
                        if (!testisdone && status) {
                            status = false; // can happen a few times due to not hreadsafe. but itt ok
                            ex.printStackTrace();
                        }
                    } finally {
                        threadsAreDone.countDown();
                    }
                }
            }.start();
        }
        Thread.currentThread().setPriority(Thread.currentThread().getPriority() + 1);
        final long t0 = System.currentTimeMillis();
        long t1 = t0;
        int oldTotal = 0;
        final int waitTime = 20;
        int broadcasts = 900000 / (threadCount * 1000 / waitTime);
        while (t1 - t0 < secondsPerTest * 1000) {
            int size = 0;
            long t2 = System.currentTimeMillis();
            long delta = t2 - t1;
            if (delta > 4500) {
                t1 = t2;
                int currentTotalMsg = msgc.get();
                System.out.println("  K events/sec : " + (currentTotalMsg - oldTotal + 500) / delta
//                            "  cometHandlers: " + CometTestAdapter.cometContext.handlers.size() +
                        + "  work queue: " + size + "  broadcasts: " + broadcasts);
                oldTotal = currentTotalMsg;
            }
            if (streaming) {
                if (size < (spreadNotify ? threadCount : 1) * 100) {
                    for (int i = 0; i < broadcasts; i++) {
                        CometTestHttpHandler.cometContext.notify(joinMessage);
                    }
                }
            } else {
                CometTestHttpHandler.cometContext.notify(joinMessage);
            }
            synchronized (connectString) {
                connectString.wait(waitTime);
            }
        }
        testisdone = true;
        System.out.println("test is done. waiting for clients to die.");
        threadsAreDone.await(6, TimeUnit.SECONDS);
        System.out.println("clients are done.");
//        assertTrue(status);
    }

    private static final AtomicInteger msgc = new AtomicInteger();

    protected void connectClient(boolean reuse, boolean streaming, boolean notifyBeforeRead) throws Exception {
        InputStream in = null;
        OutputStream out = null;
        Socket socket = null;
        final int deltaAdd = 500;
        int count = 0;
        try {
            while (!testisdone) {
                if (socket == null) {
                    socket = newSocket(5000);
                    out = socket.getOutputStream();
                    in = new BufferedInputStream(socket.getInputStream());
                }
                out.write(connectString);
                out.flush();
                int b;
                if (notifyBeforeRead) {
                    CometTestHttpHandler.cometContext.notify(joinMessage);
                }
                while ((b = in.read()) != joinMessage && !testisdone) {
                }
                if (!streaming) {
                    msgc.getAndIncrement();
                } else {
                    if (count++ == deltaAdd) { // lowers thread contention
                        msgc.getAndAdd(deltaAdd);
                        count = 0;
                    }
                }
                // {
                // if (b==joinMessage){
                in.read();
                in.read();
                in.read();
                in.read();
                in.read();
                boolean _status = true;
                while (streaming && _status && !testisdone) {
                    if (notifyBeforeRead) {
                        CometTestHttpHandler.cometContext.notify(joinMessage);
                    }
                    b = in.read();
                    in.read();
                    in.read();
                    in.read();
                    in.read();
                    in.read();
                    _status = b == joinMessage;
                    if (_status && count++ == deltaAdd) { // lowers thread contention
                        msgc.getAndAdd(deltaAdd);
                        count = 0;
                    }
                }
                if (!_status && !testisdone) {
                    if (b == -1) {
//                        fail("server closed connection");
                    } else {
//                        fail("client did not receive expected message, got:'" + b + "'");
                    }
                }
                if (!reuse) {
                    socket.close();
                    socket = null;
                }
                if (!streaming && socketreusedelayMilliSec > 0) {
                    Thread.sleep(socketreusedelayMilliSec);
                }
            }
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private Socket newSocket(int timeout) throws Exception {
        Socket socket = new Socket();
        socket.setReuseAddress(true);
        // socket.setReceiveBufferSize(2048);
        // socket.setSendBufferSize(512);
        socket.setSoLinger(false, 0);
        socket.setSoTimeout(timeout);
        socket.setTcpNoDelay(true);
        socket.connect(connectadr);
        return socket;
    }
}
