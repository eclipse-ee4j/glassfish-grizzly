/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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
 * Interface represents an output sink associated with specific {@link Http2Stream}.
 *
 * @author Alexey Stashok
 */
interface StreamOutputSink {
    boolean canWrite();

    void notifyWritePossible(WriteHandler writeHandler);

    /**
     * The method is called by HTTP2 Filter once WINDOW_UPDATE message comes for this {@link Http2Stream}.
     *
     * @param delta the delta.
     * @throws Http2StreamException if an error occurs processing the window update.
     */
    void onPeerWindowUpdate(int delta) throws Http2StreamException;

    void writeDownStream(HttpPacket httpPacket, FilterChainContext ctx, CompletionHandler<WriteResult> completionHandler, MessageCloner<Buffer> messageCloner)
            throws IOException;

    /**
     * Flush {@link Http2Stream} output and notify {@link CompletionHandler} once all output data has been flushed.
     *
     * @param completionHandler {@link CompletionHandler} to be notified
     */
    void flush(CompletionHandler<Http2Stream> completionHandler);

    /**
     * @return the number of writes (not bytes), that haven't reached network layer
     */
    int getUnflushedWritesCount();

    /**
     * Closes the output sink by adding last DataFrame with the FIN flag to a queue. If the output sink is already closed -
     * method does nothing.
     */
    void close();

    /**
     * Unlike {@link #close()} this method forces the output sink termination by setting termination flag and canceling all
     * the pending writes.
     */
    void terminate(Termination terminationFlag);

    /**
     * Same as {@link #terminate(Termination)}, but the pending writes and write handlers are failed with an exception
     * carrying the given cause.
     *
     * @param terminationFlag the termination flag
     * @param cause the cause of the termination, may be <tt>null</tt>
     */
    default void terminate(Termination terminationFlag, Throwable cause) {
        terminate(terminationFlag);
    }

    boolean isClosed();
}
