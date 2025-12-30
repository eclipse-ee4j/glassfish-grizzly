/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
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

package org.glassfish.grizzly;

import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import java.util.function.Supplier;
import org.glassfish.grizzly.attributes.AttributeStorage;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.monitoring.MonitoringAware;
import org.glassfish.grizzly.monitoring.MonitoringConfig;

/**
 * Common interface, which represents any kind of connection.
 *
 * @param <L> the Connection address type
 *
 * @author Alexey Stashok
 */
public interface Connection<L> extends Readable<L>, Writeable<L>, Closeable, AttributeStorage, MonitoringAware<ConnectionProbe> {

    /**
     * Returns id of this instance, unique in the context of the JVM and the implementation class.
     *
     * @return id of this instance, never null.
     */
    long getId();

    /**
     * Get the {@link Transport}, to which this {@link Connection} belongs to.
     *
     * @return the {@link Transport}, to which this {@link Connection} belongs to.
     */
    Transport getTransport();

    /**
     * Is {@link Connection} open and ready. Returns <tt>true</tt>, if connection is open and ready, or <tt>false</tt>
     * otherwise.
     *
     * @return <tt>true</tt>, if connection is open and ready, or <tt>false</tt> otherwise.
     */
    @Override
    boolean isOpen();

    /**
     * Checks if this <tt>Connection</tt> is open and ready to be used. If this <tt>Connection</tt> is closed - this method
     * throws {@link IOException} giving the reason why this <tt>Connection</tt> was closed.
     *
     */
    @Override
    void assertOpen() throws IOException;

    /**
     * Returns {@link CloseReason} if this <tt>Connection</tt> has been closed, or <tt>null</tt> otherwise.
     *
     * @return {@link CloseReason} if this <tt>Connection</tt> has been closed, or <tt>null</tt> otherwise
     */
    CloseReason getCloseReason();

    /**
     * Sets the {@link Connection} mode.
     *
     * @param isBlocking the {@link Connection} mode. <tt>true</tt>, if {@link Connection} should operate in blocking mode,
     * or <tt>false</tt> otherwise.
     */
    void configureBlocking(boolean isBlocking);

    /**
     * @return the {@link Connection} mode. <tt>true</tt>, if {@link Connection} is operating in blocking mode, or
     * <tt>false</tt> otherwise.
     */
    boolean isBlocking();

    @Deprecated
    void configureStandalone(boolean isStandalone);

    @Deprecated
    boolean isStandalone();

    /**
     * Gets the {@link Processor}, which will process {@link Connection} I/O event. If {@link Processor} is <tt>null</tt>, -
     * then {@link Transport} will try to get {@link Processor} using {@link Connection}'s
     * {@link ProcessorSelector#select(IOEvent, Connection)}. If {@link ProcessorSelector}, associated withthe
     * {@link Connection} is also <tt>null</tt> - will ask {@link Transport} for a {@link Processor}.
     *
     * @param ioEvent event to obtain the processor for
     * @return the default {@link Processor}, which will process {@link Connection} I/O events.
     */
    Processor obtainProcessor(IOEvent ioEvent);

    /**
     * Gets the default {@link Processor}, which will process {@link Connection} I/O events. If {@link Processor} is
     * <tt>null</tt>, - then {@link Transport} will try to get {@link Processor} using {@link Connection}'s
     * {@link ProcessorSelector#select(IOEvent, Connection)}. If {@link ProcessorSelector}, associated withthe
     * {@link Connection} is also <tt>null</tt> - {@link Transport} will try to get {@link Processor} using own settings.
     *
     * @return the default {@link Processor}, which will process {@link Connection} I/O events.
     */
    Processor getProcessor();

    /**
     * Sets the default {@link Processor}, which will process {@link Connection} I/O events. If {@link Processor} is
     * <tt>null</tt>, - then {@link Transport} will try to get {@link Processor} using {@link Connection}'s
     * {@link ProcessorSelector#select(IOEvent, Connection)}. If {@link ProcessorSelector}, associated withthe
     * {@link Connection} is also <tt>null</tt> - {@link Transport} will try to get {@link Processor} using own settings.
     *
     * @param preferableProcessor the default {@link Processor}, which will process {@link Connection} I/O events.
     */
    void setProcessor(Processor preferableProcessor);

    /**
     * Gets the default {@link ProcessorSelector}, which will be used to get {@link Processor} to process {@link Connection}
     * I/O events, in case if this {@link Connection}'s {@link Processor} is <tt>null</tt>.
     *
     * @return the default {@link ProcessorSelector}, which will be used to get {@link Processor} to process
     * {@link Connection} I/O events, in case if this {@link Connection}'s {@link Processor} is <tt>null</tt>.
     */
    ProcessorSelector getProcessorSelector();

    /**
     * Sets the default {@link ProcessorSelector}, which will be used to get {@link Processor} to process {@link Connection}
     * I/O events, in case if this {@link Connection}'s {@link Processor} is <tt>null</tt>.
     *
     * @param preferableProcessorSelector the default {@link ProcessorSelector}, which will be used to get {@link Processor}
     * to process {@link Connection} I/O events, in case if this {@link Connection}'s {@link Processor} is <tt>null</tt>.
     */
    void setProcessorSelector(ProcessorSelector preferableProcessorSelector);

    /**
     * Returns the {@link Processor} state associated with this <tt>Connection</tt>.
     *
     * @param <E> state of the {@link Processor}
     * @param processor {@link Processor}
     * @param factory factory that is used to initialise the state
     *
     * @return the {@link Processor} state associated with this <tt>Connection</tt>.
     */
    <E> E obtainProcessorState(Processor processor, Supplier<E> factory);

    /**
     * Executes the {@link Runnable} in the thread, responsible for running the given type of event on this
     * <tt>Connection</tt>. The thread will be chosen based on {@link #getTransport() Transport} settings, especially
     * current I/O strategy.
     *
     * @param event event to get the thread pool from
     * @param runnable Runnable to run in the thread
     */
    void executeInEventThread(IOEvent event, Runnable runnable);

    /**
     * @return an associated {@link MemoryManager}. It's a shortcut for
     * {@link #getTransport()}{@link Transport#getMemoryManager() .getMemoryManager()}
     * @since 2.3.18
     */
    MemoryManager<?> getMemoryManager();

    /**
     * Get the connection peer address
     *
     * @return the connection peer address
     */
    L getPeerAddress();

    /**
     * Get the connection local address
     *
     * @return the connection local address
     */
    L getLocalAddress();

    /**
     * Get the default size of {@link Buffer}s, which will be allocated for reading data from {@link Connection}. The value
     * less or equal to zero will be ignored.
     *
     * @return the default size of {@link Buffer}s, which will be allocated for reading data from {@link Connection}.
     */
    int getReadBufferSize();

    /**
     * Set the default size of {@link Buffer}s, which will be allocated for reading data from {@link Connection}. The value
     * less or equal to zero will be ignored.
     *
     * @param readBufferSize the default size of {@link Buffer}s, which will be allocated for reading data from
     * {@link Connection}.
     */
    void setReadBufferSize(int readBufferSize);

    /**
     * Get the default size of {@link Buffer}s, which will be allocated for writing data to {@link Connection}.
     *
     * @return the default size of {@link Buffer}s, which will be allocated for writing data to {@link Connection}.
     */
    int getWriteBufferSize();

    /**
     * Set the default size of {@link Buffer}s, which will be allocated for writing data to {@link Connection}.
     *
     * @param writeBufferSize the default size of {@link Buffer}s, which will be allocated for writing data to
     * {@link Connection}.
     */
    void setWriteBufferSize(int writeBufferSize);

    /**
     * Get the max size (in bytes) of asynchronous write queue associated with connection.
     *
     * @return the max size (in bytes) of asynchronous write queue associated with connection.
     *
     * @since 2.2
     */
    int getMaxAsyncWriteQueueSize();

    /**
     * Set the max size (in bytes) of asynchronous write queue associated with connection.
     *
     * @param maxAsyncWriteQueueSize the max size (in bytes) of asynchronous write queue associated with connection.
     *
     * @since 2.2
     */
    void setMaxAsyncWriteQueueSize(int maxAsyncWriteQueueSize);

    /**
     * Returns the current value for the blocking read timeout converted to the provided {@link TimeUnit} specification. If
     * this value hasn't been explicitly set, it will default to 30 seconds.
     *
     * @param timeUnit the {@link TimeUnit} to convert the returned result to.
     * @return the read timeout value
     *
     * @since 2.3
     */
    long getReadTimeout(TimeUnit timeUnit);

    /**
     * Specifies the timeout for the blocking reads. This may be overridden on a per-connection basis. A value of zero or
     * less effectively disables the timeout.
     *
     * @param timeout the new timeout value
     * @param timeUnit the {@link TimeUnit} specification of the provided value.
     *
     * @see Connection#setReadTimeout(long, java.util.concurrent.TimeUnit)
     *
     * @since 2.3
     */
    void setReadTimeout(long timeout, TimeUnit timeUnit);

    /**
     * Returns the current value for the blocking write timeout converted to the provided {@link TimeUnit} specification. If
     * this value hasn't been explicitly set, it will default to 30 seconds.
     *
     * @param timeUnit the {@link TimeUnit} to convert the returned result to.
     * @return the write timeout value
     *
     * @since 2.3
     */
    long getWriteTimeout(TimeUnit timeUnit);

    /**
     * Specifies the timeout for the blocking writes. This may be overridden on a per-connection basis. A value of zero or
     * less effectively disables the timeout.
     *
     * @param timeout the new timeout value
     * @param timeUnit the {@link TimeUnit} specification of the provided value.
     *
     * @see Connection#setWriteTimeout(long, java.util.concurrent.TimeUnit)
     *
     * @since 2.3
     */
    void setWriteTimeout(long timeout, TimeUnit timeUnit);

    void simulateIOEvent(final IOEvent ioEvent) throws IOException;

    void enableIOEvent(final IOEvent ioEvent) throws IOException;

    void disableIOEvent(final IOEvent ioEvent) throws IOException;

    /**
     * @return the <tt>Connection</tt> monitoring configuration {@link MonitoringConfig}.
     */
    @Override
    MonitoringConfig<ConnectionProbe> getMonitoringConfig();

    /**
     * Close the {@link Connection} silently, no notification required on completion or failure.
     */
    @Override
    void terminateSilently();

    /**
     * Close the {@link Connection}
     *
     * @return {@link Future}, which could be checked in case, if close operation will be run asynchronously
     */
    @Override
    GrizzlyFuture<Closeable> terminate();

    /**
     * Closes the <tt>Connection</tt> and provides the reason description.
     *
     * This method is similar to {@link #terminateSilently()}, but additionally provides the reason why the
     * <tt>Connection</tt> will be closed.
     *
     */
    @Override
    void terminateWithReason(IOException reason);

    /**
     * Gracefully close the {@link Connection}
     *
     * @return {@link Future}, which could be checked in case, if close operation will be run asynchronously
     */
    @Override
    GrizzlyFuture<Closeable> close();

    /**
     * Gracefully close the {@link Connection}
     *
     * @param completionHandler {@link CompletionHandler} to be called, when the connection is closed.
     *
     * @deprecated use {@link #close()} with the following
     * {@link GrizzlyFuture#addCompletionHandler(org.glassfish.grizzly.CompletionHandler)}.
     */
    @Deprecated
    @Override
    void close(CompletionHandler<Closeable> completionHandler);

    /**
     * Gracefully close the {@link Connection} silently, no notification required on completion or failure.
     */
    @Override
    void closeSilently();

    /**
     * Gracefully closes the <tt>Connection</tt> and provides the reason description.
     *
     * This method is similar to {@link #closeSilently()}, but additionally provides the reason why the <tt>Connection</tt>
     * will be closed.
     *
     */
    @Override
    void closeWithReason(IOException reason);

    /**
     * Add the {@link CloseListener}, which will be notified once <tt>Connection</tt> will be closed.
     *
     * @param closeListener {@link CloseListener}.
     *
     * @since 2.3
     */
    @Override
    void addCloseListener(org.glassfish.grizzly.CloseListener closeListener);

    /**
     * Remove the {@link CloseListener}.
     *
     * @param closeListener {@link CloseListener}.
     *
     * @since 2.3
     */
    @Override
    boolean removeCloseListener(org.glassfish.grizzly.CloseListener closeListener);

    /**
     * Add the {@link CloseListener}, which will be notified once <tt>Connection</tt> will be closed.
     *
     * @param closeListener {@link CloseListener}
     *
     * @deprecated use {@link #addCloseListener(org.glassfish.grizzly.CloseListener)}
     */
    @Deprecated
    void addCloseListener(CloseListener closeListener);

    /**
     * Remove the {@link CloseListener}.
     *
     * @param closeListener {@link CloseListener}.
     * @return true if listener successfully removed
     *
     * @deprecated use {@link #removeCloseListener(org.glassfish.grizzly.CloseListener)}
     */
    @Deprecated
    boolean removeCloseListener(CloseListener closeListener);

    /**
     * Method gets invoked, when error occur during the <tt>Connection</tt> lifecycle.
     *
     * @param error {@link Throwable}.
     */
    void notifyConnectionError(Throwable error);

    // ------------------------------------------------------------------- Nested Classes

    /**
     * This interface will be removed in 3.0.
     *
     * @deprecated use {@link org.glassfish.grizzly.CloseListener}
     *
     * @see GenericCloseListener
     */
    @Deprecated
    interface CloseListener extends org.glassfish.grizzly.CloseListener<Connection, CloseType> {

        @Override
        void onClosed(Connection connection, CloseType type) throws IOException;

    }

    /**
     * This enum will be removed in 3.0.
     *
     * @deprecated use {@link org.glassfish.grizzly.CloseType}
     */
    @Deprecated
    enum CloseType implements ICloseType {
        LOCALLY, REMOTELY
    }

}
