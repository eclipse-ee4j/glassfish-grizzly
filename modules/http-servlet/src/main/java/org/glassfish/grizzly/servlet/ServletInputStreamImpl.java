/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2008, 2020 Oracle and/or its affiliates. All rights reserved.
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.glassfish.grizzly.servlet;

import java.io.IOException;

import org.glassfish.grizzly.ReadHandler;
import org.glassfish.grizzly.http.io.NIOInputStream;
import org.glassfish.grizzly.localization.LogMessages;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

/**
 *
 * @author Jeanfrancois Arcand
 */
public class ServletInputStreamImpl extends ServletInputStream {

    private final HttpServletRequestImpl servletRequest;
    private NIOInputStream inputStream;

    private ReadHandler readHandler = null;
    private boolean hasSetReadListener = false;
    private boolean prevIsReady = true;

    private static final ThreadLocal<Boolean> IS_READY_SCOPE = new ThreadLocal<>();

    protected ServletInputStreamImpl(final HttpServletRequestImpl servletRequest) {
        this.servletRequest = servletRequest;
    }

    public void initialize() throws IOException {
        this.inputStream = servletRequest.getRequest().createInputStream();
    }

    @Override
    public int read() throws IOException {
        if (!prevIsReady) {
            throw new IllegalStateException(LogMessages.WARNING_GRIZZLY_HTTP_SERVLET_NON_BLOCKING_ERROR());
        }

        return inputStream.read();
    }

    @Override
    public int available() throws IOException {
        if (!prevIsReady) {
            return 0;
        }

        return inputStream.available();
    }

    @Override
    public int read(final byte[] b) throws IOException {
        if (!prevIsReady) {
            throw new IllegalStateException(LogMessages.WARNING_GRIZZLY_HTTP_SERVLET_NON_BLOCKING_ERROR());
        }

        return inputStream.read(b, 0, b.length);
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        if (!prevIsReady) {
            throw new IllegalStateException(LogMessages.WARNING_GRIZZLY_HTTP_SERVLET_NON_BLOCKING_ERROR());
        }

        return inputStream.read(b, off, len);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long skip(final long n) throws IOException {
        return inputStream.skip(n);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void mark(final int readlimit) {
        inputStream.mark(readlimit);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() throws IOException {
        inputStream.reset();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean markSupported() {
        return inputStream.markSupported();
    }

    /**
     * Close the stream Since we re-cycle, we can't allow the call to super.close() which would permanently disable us.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void close() throws IOException {
        inputStream.close();
    }

    void recycle() {
        inputStream = null;
        prevIsReady = true;
        hasSetReadListener = false;
        readHandler = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isFinished() {
        return inputStream.isFinished();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReady() {
        if (!prevIsReady) {
            return false;
        }

        boolean result = inputStream.isReady();
        if (!result) {
            if (hasSetReadListener) {
                prevIsReady = false; // Not data available
                IS_READY_SCOPE.set(Boolean.TRUE);
                try {
                    inputStream.notifyAvailable(readHandler);
                } finally {
                    IS_READY_SCOPE.remove();
                }

            } else {
                prevIsReady = true; // Allow next .isReady() call to check underlying inputStream
            }
        }

        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setReadListener(final ReadListener readListener) {
        if (hasSetReadListener) {
            throw new IllegalStateException("The ReadListener has already been set");
        }

        if (!(servletRequest.isAsyncStarted() || servletRequest.isUpgrade())) {
            throw new IllegalStateException(LogMessages.WARNING_GRIZZLY_HTTP_SERVLET_INPUTSTREAM_SETREADLISTENER_ERROR());
        }

        readHandler = new ReadHandlerImpl(readListener);

        // Initial installation of ReadListener that will be notified
        if (!inputStream.isReady()) {
            prevIsReady = false;
        }
        IS_READY_SCOPE.set(Boolean.TRUE);
        try {
            inputStream.notifyAvailable(readHandler);
        } finally {
            IS_READY_SCOPE.remove();
        }

        hasSetReadListener = true;
    }

    class ReadHandlerImpl implements ReadHandler {
        private ReadListener readListener = null;

        private ReadHandlerImpl(ReadListener listener) {
            readListener = listener;
        }

        @Override
        public void onDataAvailable() throws Exception {
            if (!Boolean.TRUE.equals(IS_READY_SCOPE.get())) {
                invokeReadPossibleCallback();
            } else {
                AsyncContextImpl.pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        invokeReadPossibleCallback();
                    }
                });
            }
        }

        @Override
        public void onAllDataRead() throws Exception {
            if (!Boolean.TRUE.equals(IS_READY_SCOPE.get())) {
                invokeAllDataReadCallback();
            } else {
                AsyncContextImpl.pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        invokeAllDataReadCallback();
                    }
                });
            }
        }

        @Override
        public void onError(final Throwable t) {
            if (!Boolean.TRUE.equals(IS_READY_SCOPE.get())) {
                readListener.onError(t);
            } else {
                AsyncContextImpl.pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        readListener.onError(t);
                    }
                });
            }
        }

        private void invokeReadPossibleCallback() {
            prevIsReady = true;
            try {
                readListener.onDataAvailable();
            } catch (Throwable t) {
                readListener.onError(t);
            }
        }

        private void invokeAllDataReadCallback() {
            prevIsReady = true;
            try {
                readListener.onAllDataRead();
            } catch (Throwable t) {
                readListener.onError(t);
            }
        }
    }
}
