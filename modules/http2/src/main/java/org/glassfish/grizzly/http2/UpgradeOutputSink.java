/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2015, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http2;

import java.io.IOException;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.asyncqueue.MessageCloner;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpPacket;

/**
 * The {@link StreamOutputSink} implementation, which is used when upgrading HTTP -> HTTP/2 connections.
 *
 * @author Alexey Stashok
 */
public class UpgradeOutputSink implements StreamOutputSink {
    private final Http2Session connection;
    private boolean isClosed;

    public UpgradeOutputSink(Http2Session connection) {
        this.connection = connection;
    }

    @Override
    public boolean canWrite() {
        return connection.getConnection().canWrite();
    }

    @Override
    public void notifyWritePossible(WriteHandler writeHandler) {
        connection.getConnection().notifyCanWrite(writeHandler);
    }

    @Override
    public void onPeerWindowUpdate(int delta) throws Http2StreamException {
    }

    @Override
    public void writeDownStream(HttpPacket httpPacket, FilterChainContext ctx, CompletionHandler<WriteResult> completionHandler,
            MessageCloner<Buffer> messageCloner) throws IOException {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void flush(CompletionHandler<Http2Stream> completionHandler) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public int getUnflushedWritesCount() {
        return 0;
    }

    @Override
    public void close() {
        terminate(Termination.OUT_FIN_TERMINATION);
    }

    @Override
    public void terminate(Termination termination) {
        synchronized (this) {
            if (isClosed) {
                return;
            }

            isClosed = true;
        }

        if (termination != null) {
            termination.doTask();
        }
    }

    @Override
    public synchronized boolean isClosed() {
        return isClosed;
    }
}
