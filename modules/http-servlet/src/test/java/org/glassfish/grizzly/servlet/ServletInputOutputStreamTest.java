/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
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

package org.glassfish.grizzly.servlet;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.ReadHandler;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.http.io.NIOInputStream;
import org.glassfish.grizzly.http.io.NIOOutputStream;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;

import jakarta.servlet.ReadListener;
import jakarta.servlet.WriteListener;
import junit.framework.TestCase;

/**
 * Regression tests for the listener installation race in {@link ServletInputStreamImpl#setReadListener(ReadListener)}
 * and {@link ServletOutputStreamImpl#setWriteListener(WriteListener)}.
 * <p>
 * When data is already available (or the stream is already writable) while the listener is installed, the first
 * {@code onDataAvailable()} ({@code onWritePossible()}) callback runs on the async thread pool. If that callback reached
 * {@code isReady() == false} before {@code setReadListener()} ({@code setWriteListener()}) had marked the listener as
 * installed, {@code isReady()} did not register for the next notification, so the listener was never called again
 * (seen as an intermittent read timeout in {@code HttpUpgradeTest}).
 * <p>
 * The recording streams make the race deterministic: their first {@code notifyAvailable()} ({@code notifyCanWrite()})
 * fires the handler and keeps the installing thread inside the call until the pooled callback has finished.
 */
public class ServletInputOutputStreamTest extends TestCase {

    private static final long TIMEOUT_SECONDS = 10;

    // In-memory stubs only, nothing to release. Closing the servlet stream would also fail by design here:
    // it is left in "not ready" non-blocking state, where close() throws IllegalStateException.
    @SuppressWarnings("resource")
    public void testReadListenerReRegistersWhenDataAvailableOnInstall() throws Exception {
        final CountDownLatch callbackDone = new CountDownLatch(1);
        final RecordingInputStream nioInput = new RecordingInputStream(callbackDone);

        final Request grizzlyRequest = new Request(null) {
            @Override
            public NIOInputStream createInputStream() {
                return nioInput;
            }
        };
        final HttpServletRequestImpl servletRequest = new HttpServletRequestImpl() {
            @Override
            public boolean isUpgrade() {
                return true;
            }

            @Override
            public Request getRequest() {
                return grizzlyRequest;
            }
        };

        final ServletInputStreamImpl servletInput = new ServletInputStreamImpl(servletRequest);
        servletInput.initialize();
        servletInput.setReadListener(new ReadListener() {
            @Override
            public void onDataAvailable() {
                try {
                    servletInput.isReady(); // no more data -> must register for the next notification
                } finally {
                    callbackDone.countDown();
                }
            }

            @Override
            public void onAllDataRead() {
            }

            @Override
            public void onError(Throwable t) {
            }
        });

        assertEquals("onDataAvailable() callback was not invoked", 0, callbackDone.getCount());
        // 1st: initial install by setReadListener(), 2nd: re-registration by isReady() inside the callback
        assertEquals("isReady() did not register for the next read notification", 2, nioInput.notifyCount.get());
    }

    // See testReadListenerReRegistersWhenDataAvailableOnInstall() for why the streams are not closed.
    @SuppressWarnings("resource")
    public void testWriteListenerReRegistersWhenWritableOnInstall() throws Exception {
        final CountDownLatch callbackDone = new CountDownLatch(1);
        final RecordingOutputStream nioOutput = new RecordingOutputStream(callbackDone);

        final Response grizzlyResponse = new Response() {
            @Override
            public NIOOutputStream createOutputStream() {
                return nioOutput;
            }
        };
        final HttpServletResponseImpl servletResponse = new HttpServletResponseImpl() {
            @Override
            public Response getResponse() {
                return grizzlyResponse;
            }
        };
        servletResponse.servletRequest = new HttpServletRequestImpl() {
            @Override
            public boolean isUpgrade() {
                return true;
            }
        };

        final ServletOutputStreamImpl servletOutput = new ServletOutputStreamImpl(servletResponse);
        servletOutput.initialize();
        servletOutput.setWriteListener(new WriteListener() {
            @Override
            public void onWritePossible() {
                try {
                    servletOutput.isReady(); // buffer full -> must register for the next notification
                } finally {
                    callbackDone.countDown();
                }
            }

            @Override
            public void onError(Throwable t) {
            }
        });

        assertEquals("onWritePossible() callback was not invoked", 0, callbackDone.getCount());
        // 1st: initial install by setWriteListener(), 2nd: re-registration by isReady() inside the callback
        assertEquals("isReady() did not register for the next write notification", 2, nioOutput.notifyCount.get());
    }

    /**
     * Simulates data that was available when the listener got installed and is fully consumed by the first callback:
     * the first {@code notifyAvailable()} fires the handler right away, later calls only record the registration.
     */
    private static final class RecordingInputStream extends NIOInputStream {
        final AtomicInteger notifyCount = new AtomicInteger();
        private final CountDownLatch callbackDone;

        RecordingInputStream(CountDownLatch callbackDone) {
            this.callbackDone = callbackDone;
        }

        @Override
        public void notifyAvailable(ReadHandler handler) {
            if (notifyCount.incrementAndGet() == 1) {
                try {
                    // dispatched to the async pool, because setReadListener() runs inside the isReady() scope
                    handler.onDataAvailable();
                    // keep the installing thread inside setReadListener() until the pooled callback finished
                    callbackDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public void notifyAvailable(ReadHandler handler, int size) {
            notifyAvailable(handler);
        }

        @Override
        public boolean isReady() {
            return false;
        }

        @Override
        public int readyData() {
            return 0;
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public Buffer getBuffer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Buffer readBuffer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Buffer readBuffer(int size) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Simulates a stream that was writable when the listener got installed and is full after the first callback: the
     * first {@code notifyCanWrite()} fires the handler right away, later calls only record the registration.
     */
    private static final class RecordingOutputStream extends NIOOutputStream {
        final AtomicInteger notifyCount = new AtomicInteger();
        private final CountDownLatch callbackDone;

        RecordingOutputStream(CountDownLatch callbackDone) {
            this.callbackDone = callbackDone;
        }

        @Override
        public void notifyCanWrite(WriteHandler handler) {
            if (notifyCount.incrementAndGet() == 1) {
                try {
                    // dispatched to the async pool, because setWriteListener() runs inside the canWrite() scope
                    handler.onWritePossible();
                    // keep the installing thread inside setWriteListener() until the pooled callback finished
                    callbackDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        @Deprecated
        public void notifyCanWrite(WriteHandler handler, int length) {
            notifyCanWrite(handler);
        }

        @Override
        public boolean canWrite() {
            return false;
        }

        @Override
        @Deprecated
        public boolean canWrite(int length) {
            return false;
        }

        @Override
        public void write(int b) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(Buffer buffer) {
            throw new UnsupportedOperationException();
        }
    }
}
