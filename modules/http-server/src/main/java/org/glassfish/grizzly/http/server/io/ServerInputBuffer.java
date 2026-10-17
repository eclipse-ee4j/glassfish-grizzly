/*
 * Copyright (c) 2025, 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2012, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http.server.io;

import java.io.IOException;
import java.util.concurrent.Executor;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpBrokenContent;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.io.InputBuffer;
import org.glassfish.grizzly.http.server.HttpServerFilter;
import org.glassfish.grizzly.http.server.Request;

/**
 * Server-side implementation of the {@link InputBuffer}.
 *
 * @author Alexey Stashok
 */
public class ServerInputBuffer extends InputBuffer {
    private volatile long totalReadContentInBytes;
    private volatile Request serverRequest;
    // see #isAsyncReadStarted()
    private volatile boolean asyncReadStarted;

    /**
     * @return whether the body of the current request has been waited for asynchronously (a {@link
     * org.glassfish.grizzly.ReadHandler} that wasn't satisfied at once)
     * @since 5.0.4
     */
    public boolean isAsyncReadStarted() {
        return asyncReadStarted;
    }

    public void initialize(final Request serverRequest, final FilterChainContext ctx) {
        this.serverRequest = serverRequest;
        super.initialize(serverRequest.getRequest(), ctx);
    }

    /**
     * Initiates asynchronous data receiving.
     *
     * This is service method, usually users don't have to call it explicitly.
     */
    @Override
    public void initiateAsyncronousDataReceiving() {
        // set first: also the error below may reach the ReadHandler on another thread
        asyncReadStarted = true;
        if (!checkChunkedMaxPostSize()) {
            final Request localServerRequest = serverRequest;
            if (localServerRequest == null) {
                throw new IllegalStateException("ServerInputBuffer is not initialized in a Request");
            }
            final HttpContent brokenContent = HttpBrokenContent.builder(localServerRequest.getRequest())
                    .error(new IOException("The HTTP request content exceeds max post size")).build();
            try {
                append(brokenContent);
            } catch (IOException ignored) {
            }

            return;
        }

        super.initiateAsyncronousDataReceiving();
    }

    @Override
    protected HttpContent blockingRead() throws IOException {
        if (!checkChunkedMaxPostSize()) {
            throw new IOException("The HTTP request content exceeds max post size");
        }

        return super.blockingRead();
    }

    @Override
    protected void updateInputContentBuffer(final Buffer buffer) throws IOException {
        if (!initialized()) {
            // If serverRequest is null, we are in the process of recycling the ServerInputBuffer.
            buffer.dispose();
            throw new IOException("ServerInputBuffer is not initialized");
        }
        totalReadContentInBytes += buffer.remaining();
        super.updateInputContentBuffer(buffer);
    }

    @Override
    public void recycle() {
        serverRequest = null;
        totalReadContentInBytes = 0;
        if (asyncReadStarted) {
            asyncReadStarted = false;
        }
        super.recycle();
    }

    @Override
    protected Executor getThreadPool() {
        final Request localServerRequest = serverRequest;
        // If serverRequest is null, we are in the process of recycling the ServerInputBuffer.
        return localServerRequest != null ? localServerRequest.getRequestExecutor() : null;
    }

    private boolean checkChunkedMaxPostSize() {
        final Request localServerRequest = serverRequest;
        if (localServerRequest == null) {
            throw new IllegalStateException("ServerInputBuffer is not initialized in a Request");
        }
        final HttpHeader httpRequest = localServerRequest.getRequest();
        if (httpRequest == null) {
            throw new IllegalStateException("HttpRequestPacket is not initialized in a Request");
        }
        if (httpRequest.isChunked()) {
            final HttpServerFilter httpServerFilter = localServerRequest.getHttpFilter();
            if (httpServerFilter == null) {
                throw new IllegalStateException("HttpServerFilter is not initialized in a Request");
            }
            final long maxPostSize = httpServerFilter.getConfiguration().getMaxPostSize();
            return maxPostSize < 0 || maxPostSize > totalReadContentInBytes;
        }

        return true;
    }

    private boolean initialized() {
        return serverRequest != null;
    }
}
