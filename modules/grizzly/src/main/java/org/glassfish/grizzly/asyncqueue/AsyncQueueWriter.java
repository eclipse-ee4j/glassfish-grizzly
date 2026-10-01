/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2008, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.asyncqueue;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.Writer;
import org.glassfish.grizzly.nio.NIOConnection;

/**
 * The {@link AsyncQueue}, which implements asynchronous write queue.
 *
 * @param <L> the destination address type
 *
 * @author Alexey Stashok
 * @author Ryan Lubke
 */
@SuppressWarnings("deprecation")
public interface AsyncQueueWriter<L> extends Writer<L>, AsyncQueue {

    /**
     * Constant set via {@link #setMaxPendingBytesPerConnection(int)} means the async write queue size is unlimited.
     */
    int UNLIMITED_SIZE = -1;

    /**
     * Constant set via {@link #setMaxPendingBytesPerConnection(int)} means the async write queue size will be configured
     * automatically per {@link NIOConnection} depending on connections write buffer size.
     */
    int AUTO_SIZE = -2;

    /**
     * Default multiplier used to calculate the hard limit for the asynchronous
     * write queue reservation size per connection.
     *
     * <p>The hard limit is calculated by multiplying the connection's configured
     * soft limit by this value. If a write would exceed the hard limit, the write
     * fails and the connection is closed.</p>
     */
    int DEFAULT_PENDING_BYTES_HARD_LIMIT_MULTIPLIER = 4;

    /**
     * Method writes the {@link Buffer} to the specific address.
     *
     *
     * @param connection the {@link org.glassfish.grizzly.Connection} to write to
     * @param dstAddress the destination address the {@link WritableMessage} will be sent to
     * @param message the {@link WritableMessage}, from which the data will be written
     * @param completionHandler {@link org.glassfish.grizzly.CompletionHandler}, which will get notified, when write will be
     * completed
     * @param pushBackHandler {@link PushBackHandler}, which will be notified if message was accepted by transport write
     * queue or refused
     * @param cloner {@link MessageCloner}, which will be invoked by <tt>AsyncQueueWriter</tt>, if message could not be
     * written to a channel directly and has to be put on a asynchronous queue
     *
     * @deprecated push back logic is deprecated
     */
    @Deprecated
    void write(Connection<L> connection, L dstAddress, WritableMessage message, CompletionHandler<WriteResult<WritableMessage, L>> completionHandler,
            PushBackHandler pushBackHandler, MessageCloner<WritableMessage> cloner);

    /**
     * @param connection the {@link Connection} to test whether or not the specified number of bytes can be written to.
     * @param size number of bytes to write.
     * @return <code>true</code> if the queue has not exceeded it's maximum size in bytes of pending writes, otherwise
     * <code>false</code>
     *
     * @since 2.2
     * @deprecated the size parameter will be ignored, use {@link #canWrite(org.glassfish.grizzly.Connection)} instead.
     */
    @Deprecated
    boolean canWrite(final Connection<L> connection, int size);

    /**
     * Registers {@link WriteHandler}, which will be notified ones the {@link Connection} is able to accept more bytes to be
     * written. Note: using this method from different threads simultaneously may lead to quick situation changes, so at
     * time {@link WriteHandler} is called - the queue may become busy again.
     *
     * @param connection {@link Connection}
     * @param writeHandler {@link WriteHandler} to be notified.
     * @param size number of bytes queue has to be able to accept before notifying {@link WriteHandler}.
     *
     * @since 2.2
     * @deprecated the size parameter will be ignored, use
     * {@link #notifyWritePossible(org.glassfish.grizzly.Connection, org.glassfish.grizzly.WriteHandler) instead.
     */
    @Deprecated
    void notifyWritePossible(final Connection<L> connection, final WriteHandler writeHandler, final int size);

    /**
     * Configures the soft limit for the maximum number of bytes pending to be written for a particular {@link Connection}.
     *
     * @param maxPendingBytes the soft limit for the maximum number of bytes that may be pending to be written to a particular {@link Connection}.
     */
    void setMaxPendingBytesPerConnection(final int maxPendingBytes);

    /**
     * Gets the configured soft limit for the maximum number of bytes pending to be written per connection for asynchronous writes.
     *
     * @return the configured soft limit. By default, this will be four times the size of the {@link java.net.Socket} send buffer size.
     */
    int getMaxPendingBytesPerConnection();

    /**
     * Sets the multiplier used to calculate the hard limit for the asynchronous
     * write queue reservation size per connection.
     *
     * <p>The hard limit is calculated by multiplying the connection's configured
     * soft limit by this value. If adding a write would cause the reservation size
     * to exceed the calculated hard limit, the write fails and the connection is closed.</p>
     *
     * <p>The default multiplier is {@code 4}.</p>
     *
     * @param multiplier the hard limit multiplier; must be greater than or equal
     *        to {@code 1}
     * @throws IllegalArgumentException if {@code multiplier} is less than {@code 1}
     */
    void setPendingBytesHardLimitMultiplier(final int multiplier);

    /**
     * Gets the multiplier used to calculate the hard limit for the asynchronous
     * write queue reservation size per connection.
     *
     * <p>If adding a write would cause the reservation size to exceed the
     * calculated hard limit, the write fails and the connection is closed.</p>
     *
     * <p>The default multiplier is {@code 4}.</p>
     *
     * @return the hard limit multiplier
     */
    int getPendingBytesHardLimitMultiplier();

    /**
     * Returns <tt>true</tt>, if async write queue is allowed to write buffer directly during write(...) method call, w/o
     * adding buffer to the queue, or <tt>false</tt> otherwise.
     *
     * @return <tt>true</tt>, if async write queue is allowed to write buffer directly during write(...) method call, w/o
     * adding buffer to the queue, or <tt>false</tt> otherwise.
     */
    boolean isAllowDirectWrite();

    /**
     * Set <tt>true</tt>, if async write queue is allowed to write buffer directly during write(...) method call, w/o adding
     * buffer to the queue, or <tt>false</tt> otherwise.
     *
     * @param isAllowDirectWrite <tt>true</tt>, if async write queue is allowed to write buffer directly during write(...)
     * method call, w/o adding buffer to the queue, or <tt>false</tt> otherwise.
     */
    void setAllowDirectWrite(final boolean isAllowDirectWrite);

}
