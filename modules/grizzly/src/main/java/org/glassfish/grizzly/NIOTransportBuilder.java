/*
 * Copyright (c) 2011, 2025 Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2018 Payara Services Ltd.
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

import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.asyncqueue.AsyncQueueWriter;
import org.glassfish.grizzly.attributes.AttributeBuilder;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.NIOChannelDistributor;
import org.glassfish.grizzly.nio.NIOTransport;
import org.glassfish.grizzly.nio.SelectionKeyHandler;
import org.glassfish.grizzly.nio.SelectorHandler;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;

/**
 * This builder is responsible for creating {@link NIOTransport} implementations as well as providing basic
 * configuration for <code>IOStrategies</code> and thread pools.
 *
 * @see NIOTransport
 * @see IOStrategy
 * @see ThreadPoolConfig
 *
 * @since 2.0
 */
@SuppressWarnings("UnusedDeclaration")
public abstract class NIOTransportBuilder<T extends NIOTransportBuilder> {

    protected final Class<? extends NIOTransport> transportClass;
    protected ThreadPoolConfig workerConfig;
    protected ThreadPoolConfig kernelConfig;
    protected SelectorProvider selectorProvider;
    protected SelectorHandler selectorHandler = SelectorHandler.DEFAULT_SELECTOR_HANDLER;
    protected SelectionKeyHandler selectionKeyHandler = SelectionKeyHandler.DEFAULT_SELECTION_KEY_HANDLER;
    protected MemoryManager memoryManager = MemoryManager.DEFAULT_MEMORY_MANAGER;
    protected AttributeBuilder attributeBuilder = AttributeBuilder.DEFAULT_ATTRIBUTE_BUILDER;
    protected IOStrategy ioStrategy = WorkerThreadIOStrategy.getInstance();
    protected int selectorRunnerCount = NIOTransport.DEFAULT_SELECTOR_RUNNER_COUNT;
    protected NIOChannelDistributor nioChannelDistributor;
    protected String name;
    protected Processor processor;
    protected ProcessorSelector processorSelector;
    protected int readBufferSize = Transport.DEFAULT_READ_BUFFER_SIZE;
    protected int writeBufferSize = Transport.DEFAULT_WRITE_BUFFER_SIZE;
    protected int clientSocketSoTimeout = NIOTransport.DEFAULT_CLIENT_SOCKET_SO_TIMEOUT;
    protected int connectionTimeout = NIOTransport.DEFAULT_CONNECTION_TIMEOUT;
    protected boolean reuseAddress = NIOTransport.DEFAULT_REUSE_ADDRESS;
    protected boolean reusePort = NIOTransport.DEFAULT_REUSE_PORT;
    protected int maxPendingBytesPerConnection = AsyncQueueWriter.AUTO_SIZE;
    protected boolean optimizedForMultiplexing = NIOTransport.DEFAULT_OPTIMIZED_FOR_MULTIPLEXING;

    protected long readTimeout = TimeUnit.MILLISECONDS.convert(Transport.DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
    protected long writeTimeout = TimeUnit.MILLISECONDS.convert(Transport.DEFAULT_WRITE_TIMEOUT, TimeUnit.SECONDS);

    // ------------------------------------------------------------ Constructors

    /**
     * <p>
     * Constructs a new <code>NIOTransport</code> using the given <code>transportClass</code> and {@link IOStrategy}.
     * </p>
     *
     * <p>
     * The builder's worker thread pool configuration will be based on the return value of
     * {@link IOStrategy#createDefaultWorkerPoolConfig(Transport)}. If worker thread configuration is non-null, the initial
     * selector thread pool configuration will be cloned from it, otherwise a default configuration will be chosen.
     * </p>
     *
     * @param transportClass the class of the {@link NIOTransport} implementation to be used.
     */
    protected NIOTransportBuilder(final Class<? extends NIOTransport> transportClass) {

        this.transportClass = transportClass;

    }

    // ---------------------------------------------------------- Public Methods

    /**
     * @return the number of {@link java.nio.channels.Selector}s to be created to serve Transport connections. <tt>-1</tt>
     * is the default value, which lets the Transport to pick the value, usually it's equal to the number of CPU cores
     * {@link Runtime#availableProcessors()}
     */
    public int getSelectorRunnersCount() {
        return selectorRunnerCount;
    }

    /**
     * Sets the number of {@link java.nio.channels.Selector}s to be created to serve Transport connections. <tt>-1</tt> is
     * the default value, which lets the Transport to pick the value, usually it's equal to the number of CPU cores
     * {@link Runtime#availableProcessors()}.
     *
     * @param selectorRunnersCount number of channels
     * @return the builder
     */
    public T setSelectorRunnersCount(final int selectorRunnersCount) {
        this.selectorRunnerCount = selectorRunnersCount;
        return getThis();
    }

    /**
     * @return the {@link ThreadPoolConfig} that will be used to construct the {@link java.util.concurrent.ExecutorService}
     * for <code>IOStrategies</code> that require worker threads. This method will return <code>null</code> if a
     * {@link ThreadPoolConfig} had not been previously set.
     */
    public ThreadPoolConfig getWorkerThreadPoolConfig() {
        return workerConfig;
    }

    /**
     * Sets the {@link ThreadPoolConfig} that will be used to construct the {@link java.util.concurrent.ExecutorService} for
     * <code>IOStrategies</code> that require worker threads
     * 
     * @param workerConfig the config
     * @return this builder
     */
    public T setWorkerThreadPoolConfig(final ThreadPoolConfig workerConfig) {
        this.workerConfig = workerConfig;
        return getThis();
    }

    /**
     * @return the {@link ThreadPoolConfig} that will be used to construct the {@link java.util.concurrent.ExecutorService}
     * which will run the {@link NIOTransport}'s {@link org.glassfish.grizzly.nio.SelectorRunner}s.
     */
    public ThreadPoolConfig getSelectorThreadPoolConfig() {
        return kernelConfig;
    }

    /**
     * Sets the {@link ThreadPoolConfig} that will be used to construct the {@link java.util.concurrent.ExecutorService}
     * which will run the {@link NIOTransport}'s {@link org.glassfish.grizzly.nio.SelectorRunner}s.
     * 
     * @param kernelConfig the config
     * @return this builder
     */
    public T setSelectorThreadPoolConfig(final ThreadPoolConfig kernelConfig) {
        this.kernelConfig = kernelConfig;
        return getThis();
    }

    /**
     * @return the {@link IOStrategy} that will be used by the created {@link NIOTransport}.
     */
    public IOStrategy getIOStrategy() {
        return ioStrategy;
    }

    /**
     * <p>
     * Changes the {@link IOStrategy} that will be used. Invoking this method may change the return value of
     * {@link #getWorkerThreadPoolConfig()}
     *
     * @param ioStrategy the {@link IOStrategy} to use.
     *
     * @return this <code>NIOTransportBuilder</code>
     */
    public T setIOStrategy(final IOStrategy ioStrategy) {
        this.ioStrategy = ioStrategy;
        return getThis();
    }

    /**
     * @return the {@link MemoryManager} that will be used by the created {@link NIOTransport}. If not explicitly set, then
     * {@link MemoryManager#DEFAULT_MEMORY_MANAGER} will be used.
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    /**
     * Set the {@link MemoryManager} to be used by the created {@link NIOTransport}.
     *
     * @param memoryManager the {@link MemoryManager}.
     *
     * @return this <code>NIOTransportBuilder</code>
     */
    public T setMemoryManager(final MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
        return getThis();
    }

    /**
     * @return the {@link SelectorHandler} that will be used by the created {@link NIOTransport}. If not explicitly set,
     * then {@link SelectorHandler#DEFAULT_SELECTOR_HANDLER} will be used.
     */
    public SelectorHandler getSelectorHandler() {
        return selectorHandler;
    }

    /**
     * Set the {@link SelectorHandler} to be used by the created {@link NIOTransport}.
     *
     * @param selectorHandler the {@link SelectorHandler}.
     *
     * @return this <code>NIOTransportBuilder</code>
     */
    public T setSelectorHandler(final SelectorHandler selectorHandler) {
        this.selectorHandler = selectorHandler;
        return getThis();
    }

    /**
     * @return the {@link SelectionKeyHandler} that will be used by the created {@link NIOTransport}. If not explicitly set,
     * then {@link SelectionKeyHandler#DEFAULT_SELECTION_KEY_HANDLER} will be used.
     */
    public SelectionKeyHandler getSelectionKeyHandler() {
        return selectionKeyHandler;
    }

    /**
     * Set the {@link SelectionKeyHandler} to be used by the created {@link NIOTransport}.
     *
     * @param selectionKeyHandler the {@link SelectionKeyHandler}.
     *
     * @return this <code>NIOTransportBuilder</code>
     */
    public T setSelectionKeyHandler(final SelectionKeyHandler selectionKeyHandler) {
        this.selectionKeyHandler = selectionKeyHandler;
        return getThis();
    }

    /**
     * @return the {@link AttributeBuilder} that will be used by the created {@link NIOTransport}. If not explicitly set,
     * then {@link AttributeBuilder#DEFAULT_ATTRIBUTE_BUILDER} will be used.
     */
    public AttributeBuilder getAttributeBuilder() {
        return attributeBuilder;
    }

    /**
     * Set the {@link AttributeBuilder} to be used by the created {@link NIOTransport}.
     *
     * @param attributeBuilder the {@link AttributeBuilder}.
     *
     * @return this <code>NIOTransportBuilder</code>
     */
    public T setAttributeBuilder(AttributeBuilder attributeBuilder) {
        this.attributeBuilder = attributeBuilder;
        return getThis();
    }

    /**
     * @return the {@link NIOChannelDistributor} that will be used by the created {@link NIOTransport}. If not explicitly
     * set, then {@link AttributeBuilder#DEFAULT_ATTRIBUTE_BUILDER} will be used.
     */
    public NIOChannelDistributor getNIOChannelDistributor() {
        return nioChannelDistributor;
    }

    /**
     * Set the {@link NIOChannelDistributor} to be used by the created {@link NIOTransport}.
     *
     * @param nioChannelDistributor the {@link NIOChannelDistributor}.
     *
     * @return this <code>NIOTransportBuilder</code>
     */
    public T setNIOChannelDistributor(NIOChannelDistributor nioChannelDistributor) {
        this.nioChannelDistributor = nioChannelDistributor;
        return getThis();
    }

    /**
     * @return the {@link SelectorProvider} that will be used by the created {@link NIOTransport}. If not explicitly set,
     * then {@link SelectorProvider#provider()} will be used.
     */
    public SelectorProvider getSelectorProvider() {
        return selectorProvider;
    }

    /**
     * Set the {@link SelectorProvider} to be used by the created {@link NIOTransport}.
     *
     * @param selectorProvider the {@link SelectorProvider}.
     *
     * @return this <code>NIOTransportBuilder</code>
     */
    public T setSelectorProvider(SelectorProvider selectorProvider) {
        this.selectorProvider = selectorProvider;
        return getThis();
    }

    /**
     * @return the Transport name
     * @see Transport#getName()
     */
    public String getName() {
        return name;
    }

    /**
     * @see Transport#setName(String)
     * @param name the {@link Transport} name
     * @return this <code>NIOTransportBuilder</code>
     */
    public T setName(String name) {
        this.name = name;
        return getThis();
    }

    /**
     * @return the default {@link Processor} if a {@link Connection} does not specify a preference
     * @see Transport#getProcessor()
     */
    public Processor getProcessor() {
        return processor;
    }

    /**
     * @param processor the default {@link Processor} if a {@link Connection} does not specify a preference
     * @see Transport#setProcessor(Processor)
     *
     * @return this <code>NIOTransportBuilder</code>
     */
    public T setProcessor(Processor processor) {
        this.processor = processor;
        return getThis();
    }

    /**
     * @return the default {@link ProcessorSelector}
     * @see Transport#getProcessorSelector()
     */
    public ProcessorSelector getProcessorSelector() {
        return processorSelector;
    }

    /**
     * @see Transport#setProcessorSelector(ProcessorSelector)
     * @param processorSelector the default {@link ProcessorSelector}
     * @return this <code>NIOTransportBuilder</code>
     */
    public T setProcessorSelector(ProcessorSelector processorSelector) {
        this.processorSelector = processorSelector;
        return getThis();
    }

    /**
     * @return the default buffer size
     * @see Transport#getReadBufferSize()
     */
    public int getReadBufferSize() {
        return readBufferSize;
    }

    /**
     * @see Transport#setReadBufferSize(int)
     * @param readBufferSize the new buffer size
     * @return this <code>NIOTransportBuilder</code>
     */
    public T setReadBufferSize(int readBufferSize) {
        this.readBufferSize = readBufferSize;
        return getThis();
    }

    /**
     * @return the default buffer size
     * @see Transport#getWriteBufferSize()
     */
    public int getWriteBufferSize() {
        return writeBufferSize;
    }

    /**
     * @see Transport#setWriteBufferSize(int)
     * @param writeBufferSize the new write buffer size
     * @return this <code>NIOTransportBuilder</code>
     */
    public T setWriteBufferSize(int writeBufferSize) {
        this.writeBufferSize = writeBufferSize;
        return getThis();
    }

    /**
     * @return gets the timeout on socket blocking operations on the client
     * @see java.net.Socket#getSoTimeout()
     */
    public int getClientSocketSoTimeout() {
        return clientSocketSoTimeout;
    }

    /**
     * Sets the timeout on socket blocking operations for the client
     * 
     * @param clientSocketSoTimeout the specified timeout in milliseconds
     * @return this <code>NIOTransportBuilder</code>
     * @see java.net.Socket#setSoTimeout(int)
     */
    public T setClientSocketSoTimeout(int clientSocketSoTimeout) {
        this.clientSocketSoTimeout = clientSocketSoTimeout;
        return getThis();
    }

    /**
     * @return value of the connectio timeout in milliseconds
     * @see java.net.URLConnection#getConnectTimeout()
     */
    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    /**
     * @param connectionTimeout the value of the connection timeout in milliseconds
     * @return this <code>NIOTransportBuilder</code>
     * @see NIOTransport#setConnectionTimeout(int)
     */
    public T setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
        return getThis();
    }

    /**
     * @param timeUnit the {@link TimeUnit} to convert the result to
     * @return the blocking read timeout in the specified {@link TimeUnit}
     * @see Transport#getReadTimeout(java.util.concurrent.TimeUnit)
     */
    public long getReadTimeout(final TimeUnit timeUnit) {
        if (readTimeout <= 0) {
            return -1;
        } else {
            return timeUnit.convert(readTimeout, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Sets the value of the blocking read timeout
     * 
     * @param timeout the new timeout value
     * @param timeUnit the unit of the new timeout value
     * @return this NioTransportBuilder
     * @see Transport#setReadTimeout(long, java.util.concurrent.TimeUnit)
     */
    public T setReadTimeout(final long timeout, final TimeUnit timeUnit) {
        if (timeout <= 0) {
            readTimeout = -1;
        } else {
            readTimeout = TimeUnit.MILLISECONDS.convert(timeout, timeUnit);
        }
        return getThis();
    }

    /**
     * @param timeUnit the {@link TimeUnit} to convert the result to
     * @return the value of the write timeout
     * @see Transport#getWriteTimeout(java.util.concurrent.TimeUnit)
     */
    public long getWriteTimeout(final TimeUnit timeUnit) {
        if (writeTimeout <= 0) {
            return -1;
        } else {
            return timeUnit.convert(writeTimeout, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * @param timeout the new write timeout value
     * @param timeUnit the {@link TimeUnit} of the timeout value
     * @return this NIOTransportBuilder
     * @see Transport#setWriteTimeout(long, java.util.concurrent.TimeUnit)
     */
    public T setWriteTimeout(final long timeout, final TimeUnit timeUnit) {
        if (timeout <= 0) {
            writeTimeout = -1;
        } else {
            writeTimeout = TimeUnit.MILLISECONDS.convert(timeout, timeUnit);
        }
        return getThis();
    }

    /**
     * Whether address may be reused for multiple sockets
     * 
     * @return SO_REUSEADDR
     * @see <a href="http://man7.org/linux/man-pages/man7/socket.7.html">Socket man page</a>
     */
    public boolean isReuseAddress() {
        return reuseAddress;
    }

    /**
     * Sets whether address may be reused for multiple sockets
     * 
     * @param reuseAddress SO_REUSEADDR
     * @return this <code>TCPNIOTransportBuilder</code>
     * @see <a href="http://man7.org/linux/man-pages/man7/socket.7.html">Socket man page</a>
     */
    public T setReuseAddress(boolean reuseAddress) {
        this.reuseAddress = reuseAddress;
        return getThis();
    }

    /**
     * Whether both same address and same port may be reused for multiple sockets
     *
     * @return SO_REUSEPORT
     * @see <a href="http://man7.org/linux/man-pages/man7/socket.7.html">Socket man page</a>
     */
    public boolean isReusePort() {
        return reusePort;
    }

    /**
     * Sets whether both same address and same port may be reused for multiple sockets
     *
     * @param reusePort SO_REUSEPORT
     * @return this <code>TCPNIOTransportBuilder</code>
     * @see <a href="http://man7.org/linux/man-pages/man7/socket.7.html">Socket man page</a>
     */
    public T setReusePort(boolean reusePort) {
        this.reusePort = reusePort;
        return getThis();
    }

    /**
     * Max asynchronous write queue size in bytes
     * 
     * @return the value is per connection, not transport total.
     * @see org.glassfish.grizzly.asyncqueue.AsyncQueueWriter#getMaxPendingBytesPerConnection()
     */
    public int getMaxAsyncWriteQueueSizeInBytes() {
        return maxPendingBytesPerConnection;
    }

    /**
     * @param maxAsyncWriteQueueSizeInBytes the value is per connection, not transport total.
     * @return this <code>TCPNIOTransportBuilder</code>
     * @see org.glassfish.grizzly.asyncqueue.AsyncQueueWriter#setMaxPendingBytesPerConnection(int)
     */
    public T setMaxAsyncWriteQueueSizeInBytes(final int maxAsyncWriteQueueSizeInBytes) {
        this.maxPendingBytesPerConnection = maxAsyncWriteQueueSizeInBytes;
        return getThis();
    }

    /**
     * @return true, if NIOTransport is configured to use AsyncQueueWriter, optimized to be used in connection multiplexing
     * mode, or false otherwise.
     * @see org.glassfish.grizzly.nio.NIOTransport#isOptimizedForMultiplexing()
     */
    public boolean isOptimizedForMultiplexing() {
        return optimizedForMultiplexing;
    }

    /**
     * @param optimizedForMultiplexing Configure NIOTransport to be optimized for connection multiplexing
     * @see org.glassfish.grizzly.nio.NIOTransport#setOptimizedForMultiplexing(boolean)
     *
     * @return this <code>TCPNIOTransportBuilder</code>
     */
    public T setOptimizedForMultiplexing(final boolean optimizedForMultiplexing) {
        this.optimizedForMultiplexing = optimizedForMultiplexing;
        return getThis();
    }

    /**
     * @return an {@link NIOTransport} based on the builder's configuration.
     */
    public NIOTransport build() {
        NIOTransport transport = create(name);
        transport.setIOStrategy(ioStrategy);
        if (workerConfig != null) {
            transport.setWorkerThreadPoolConfig(workerConfig.copy());
        }
        if (kernelConfig != null) {
            transport.setKernelThreadPoolConfig(kernelConfig.copy());
        }
        transport.setSelectorProvider(selectorProvider);
        transport.setSelectorHandler(selectorHandler);
        transport.setSelectionKeyHandler(selectionKeyHandler);
        transport.setMemoryManager(memoryManager);
        transport.setAttributeBuilder(attributeBuilder);
        transport.setSelectorRunnersCount(selectorRunnerCount);
        transport.setNIOChannelDistributor(nioChannelDistributor);
        transport.setProcessor(processor);
        transport.setProcessorSelector(processorSelector);
        transport.setClientSocketSoTimeout(clientSocketSoTimeout);
        transport.setConnectionTimeout(connectionTimeout);
        transport.setReadTimeout(readTimeout, TimeUnit.MILLISECONDS);
        transport.setWriteTimeout(writeTimeout, TimeUnit.MILLISECONDS);
        transport.setReadBufferSize(readBufferSize);
        transport.setWriteBufferSize(writeBufferSize);
        transport.setReuseAddress(reuseAddress);
        transport.setReusePort(reusePort);
        transport.setOptimizedForMultiplexing(isOptimizedForMultiplexing());
        transport.getAsyncQueueIO().getWriter().setMaxPendingBytesPerConnection(maxPendingBytesPerConnection);
        return transport;
    }

    // ------------------------------------------------------- Protected Methods

    /**
     * @return this NIOTransportBuilder
     * @see <a href=
     * "http://www.angelikalanger.com/GenericsFAQ/FAQSections/ProgrammingIdioms.html#FAQ205">http://www.angelikalanger.com/GenericsFAQ/FAQSections/ProgrammingIdioms.html#FAQ205</a>
     */
    protected abstract T getThis();

    protected abstract NIOTransport create(String name);
}
