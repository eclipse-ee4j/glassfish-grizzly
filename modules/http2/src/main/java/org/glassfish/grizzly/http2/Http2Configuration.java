/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
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

import java.util.concurrent.ExecutorService;

import org.glassfish.grizzly.filterchain.Filter;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;

/**
 * Configuration information for HTTP/2 {@link Filter} implementations.
 *
 * @see Http2ClientFilter
 * @see Http2ServerFilter
 */
public class Http2Configuration {

    public static final int DEFAULT_MAX_HEADER_LIST_SIZE = 4096;
    public static final float DEFAULT_STREAMS_HIGH_WATER_MARK = 0.5f;
    public static final float DEFAULT_STREAMS_CLEAN_PERCENTAGE = 0.5f;
    public static final int DEFAULT_CLEAN_FREQUENCY_CHECK = 50;

    /**
     * The default number of {@code RST_STREAM} frames allowed in a burst on a server connection.
     *
     * @since 5.0.4
     */
    public static final int DEFAULT_RESET_STREAM_BURST = 1000;

    /**
     * The default number of {@code RST_STREAM} allowances restored per second on a server connection.
     *
     * @since 5.0.4
     */
    public static final int DEFAULT_RESET_STREAMS_PER_SECOND = 33;

    private volatile int maxConcurrentStreams;
    private volatile int initialWindowSize;
    private volatile int maxFramePayloadSize;
    private volatile int maxHeaderListSize;
    private volatile float streamsHighWaterMark;
    private volatile float cleanPercentage;
    private volatile int cleanFrequencyCheck;
    private volatile boolean disableCipherCheck;
    private volatile boolean priorKnowledge;
    private volatile boolean pushEnabled;
    private final int resetStreamBurst;
    private final int resetStreamsPerSecond;
    private final ExecutorService executorService;
    private final ThreadPoolConfig threadPoolConfig;

    // ----------------------------------------------------------- Constructors

    private Http2Configuration(final Http2ConfigurationBuilder builder) {
        maxConcurrentStreams = builder.maxConcurrentStreams;
        initialWindowSize = builder.initialWindowSize;
        maxFramePayloadSize = builder.maxFramePayloadSize;
        maxHeaderListSize = builder.maxHeaderListSize;
        disableCipherCheck = builder.disableCipherCheck;
        priorKnowledge = builder.priorKnowledge;
        threadPoolConfig = builder.threadPoolConfig;
        executorService = builder.executorService;
        pushEnabled = builder.pushEnabled;
        streamsHighWaterMark = builder.streamsHighWaterMark;
        cleanPercentage = builder.cleanPercentage;
        cleanFrequencyCheck = builder.cleanFrequencyCheck;
        resetStreamBurst = builder.resetStreamBurst;
        resetStreamsPerSecond = builder.resetStreamsPerSecond;
    }

    // --------------------------------------------------------- Public Methods

    /**
     * @return a new {@link Http2ConfigurationBuilder} instance.
     */
    public static Http2ConfigurationBuilder builder() {
        return new Http2ConfigurationBuilder();
    }

    /**
     * @return the default maximum number of concurrent streams allowed for one session. Negative value means "unlimited".
     */
    public int getMaxConcurrentStreams() {
        return maxConcurrentStreams;
    }

    /**
     * Sets the default maximum number of concurrent streams allowed for one session. Negative value means "unlimited".
     */
    public void setMaxConcurrentStreams(final int maxConcurrentStreams) {
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    /**
     * @return the default initial stream window size (in bytes) for new HTTP2 connections.
     */
    public int getInitialWindowSize() {
        return initialWindowSize;
    }

    /**
     * Sets the default initial stream window size (in bytes) for new HTTP2 connections.
     */
    public void setInitialWindowSize(final int initialWindowSize) {
        this.initialWindowSize = initialWindowSize;
    }

    /**
     * @return the maximum allowed HTTP2 frame payload size.
     */
    public int getMaxFramePayloadSize() {
        return maxFramePayloadSize;
    }

    /**
     * Sets the maximum allowed HTTP2 frame payload size.
     */
    public void setMaxFramePayloadSize(final int maxFramePayloadSize) {
        this.maxFramePayloadSize = maxFramePayloadSize;
    }

    /**
     * @return the maximum size, in bytes, of header list. If not explicitly configured, the default of
     * {@link #DEFAULT_MAX_HEADER_LIST_SIZE} is used.
     */
    public int getMaxHeaderListSize() {
        return maxHeaderListSize;
    }

    /**
     * Set the maximum size, in bytes, of the header list.
     */
    public void setMaxHeaderListSize(final int maxHeaderListSize) {
        this.maxHeaderListSize = maxHeaderListSize;
    }

    /**
     * @return whether or not strict cipher suite checking against RFC 7540's blacklist is performed or not. If not
     * explicitly configured, checking will be performed.
     */
    public boolean isDisableCipherCheck() {
        return disableCipherCheck;
    }

    /**
     * Allows the developer to disable strict cipher suite checking of the connection against RFC 7540's blacklist.
     *
     * @param disableCipherCheck pass <code>true</code> to disable the checking.
     */
    public void setDisableCipherCheck(final boolean disableCipherCheck) {
        this.disableCipherCheck = disableCipherCheck;
    }

    /**
     * @return <code>true</code> if this filter will bypass using the HTTP/1.1 upgrade mechanism and send the client preface
     * immediately with the knowledge that the remote endpoint supports HTTP/2.
     */
    public boolean isPriorKnowledge() {
        return priorKnowledge;
    }

    /**
     * Control how the HTTP/2 connection is established with the server.
     * 
     * @param priorKnowledge <code>true</code> if it's known that the server supports HTTP/2. By default no prior knowledge
     * is assumed.
     */
    public void setPriorKnowledge(final boolean priorKnowledge) {
        this.priorKnowledge = priorKnowledge;
    }

    /**
     * @return <code>true</code> if push to be allowed by the server endpoint, otherwise <code>false</code>.
     */
    public boolean isPushEnabled() {
        return pushEnabled;
    }

    /**
     * Configure whether push should be allowed by the server endpoint.
     */
    public void setPushEnabled(final boolean pushEnabled) {
        this.pushEnabled = pushEnabled;
    }

    /**
     * @return the high-water mark indicating streams old closed streams should be cleaned up. If not explicitly configured,
     * this returns {@value #DEFAULT_STREAMS_HIGH_WATER_MARK}.
     */
    public float getStreamsHighWaterMark() {
        return streamsHighWaterMark;
    }

    /**
     * Streams are maintained and periodically cleaned when the stream count (open or otherwise) surpasses the the specified
     * high-water mark. This value is applied against the max concurrent streams for the endpoint which HTTP2 is being
     * configured for. For example, if the max concurrent streams is 100 and the mark percentage is .5, then the high water
     * mark for a clean attempt would be 50.
     */
    public void setStreamsHighWaterMark(final float streamsHighWaterMark) {
        this.streamsHighWaterMark = streamsHighWaterMark;
    }

    /**
     * @return the number of streams to attempt to remove from the streams structure. Note that only closed streams will
     * ultimately be removed. If not explicitly configured, this returns {@value #DEFAULT_STREAMS_CLEAN_PERCENTAGE}.
     */
    public float getCleanPercentage() {
        return cleanPercentage;
    }

    /**
     * The number of streams to attempt to remove from the streams structure. Note that only closed streams will ultimately
     * be removed. This value is applied against the computed result for the streams high water mark. For example, if the
     * max concurrent streams is 100 and the mark percentage is .5, then the high water mark for a clean attempt would be
     * 50. The number of streams to process in the clean attempt, assuming the clean percentage is .5, would be 25.
     */
    public void setCleanPercentage(final float cleanPercentage) {
        this.cleanPercentage = cleanPercentage;
    }

    /**
     * @return how often, in terms of closed streams, the streams structure will be checked for cleaning. If not explicitly
     * configured, this returns {@value #DEFAULT_CLEAN_FREQUENCY_CHECK}
     */
    public int getCleanFrequencyCheck() {
        return cleanFrequencyCheck;
    }

    /**
     * Set the number of streams that must be closed before checking if the number of streams exceeds the high-water mark.
     */
    public void setCleanFrequencyCheck(final int cleanFrequencyCheck) {
        this.cleanFrequencyCheck = cleanFrequencyCheck;
    }

    /**
     * @return the thread pool configuration for servicing HTTP/2 streams, if any.
     */
    public ThreadPoolConfig getThreadPoolConfig() {
        return threadPoolConfig;
    }

    /**
     * @return a pre-existing {@link ExecutorService}, if any.
     */
    public ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * @return the number of {@code RST_STREAM} frames allowed in a burst on a server connection, or zero if disabled
     * @since 5.0.4
     */
    public int getResetStreamBurst() {
        return resetStreamBurst;
    }

    /**
     * @return the number of {@code RST_STREAM} allowances restored per second on a server connection
     * @since 5.0.4
     */
    public int getResetStreamsPerSecond() {
        return resetStreamsPerSecond;
    }

    // --------------------------------------------------------- Nested Classes

    public static final class Http2ConfigurationBuilder {
        private int maxConcurrentStreams = -1;
        private int initialWindowSize = -1;
        private int maxFramePayloadSize = -1;
        private int maxHeaderListSize = DEFAULT_MAX_HEADER_LIST_SIZE;
        private boolean disableCipherCheck;
        private boolean priorKnowledge;
        private boolean pushEnabled = true;
        private float streamsHighWaterMark = DEFAULT_STREAMS_HIGH_WATER_MARK;
        private float cleanPercentage = DEFAULT_STREAMS_CLEAN_PERCENTAGE;
        private int cleanFrequencyCheck = DEFAULT_CLEAN_FREQUENCY_CHECK;
        private int resetStreamBurst = DEFAULT_RESET_STREAM_BURST;
        private int resetStreamsPerSecond = DEFAULT_RESET_STREAMS_PER_SECOND;
        private ThreadPoolConfig threadPoolConfig;
        private ExecutorService executorService;

        private Http2ConfigurationBuilder() {
        }

        /**
         * @see #setMaxConcurrentStreams(int)
         */
        public Http2ConfigurationBuilder maxConcurrentStreams(final int val) {
            maxConcurrentStreams = val;
            return this;
        }

        /**
         * @see #setInitialWindowSize(int)
         */
        public Http2ConfigurationBuilder initialWindowSize(final int val) {
            initialWindowSize = val;
            return this;
        }

        /**
         * @see #setMaxFramePayloadSize(int)
         */
        public Http2ConfigurationBuilder maxFramePayloadSize(final int val) {
            maxFramePayloadSize = val;
            return this;
        }

        /**
         * @see #setMaxHeaderListSize(int)
         */
        public Http2ConfigurationBuilder maxHeaderListSize(final int val) {
            maxHeaderListSize = val;
            return this;
        }

        /**
         * @see #setDisableCipherCheck(boolean)
         */
        public Http2ConfigurationBuilder disableCipherCheck(final boolean val) {
            disableCipherCheck = val;
            return this;
        }

        /**
         * Specifies a thread pool configuration that will be used to create a new {@link ExecutorService} handling HTTP/2
         * streams.
         */
        public Http2ConfigurationBuilder threadPoolConfig(final ThreadPoolConfig val) {
            threadPoolConfig = val;
            return this;
        }

        /**
         * Specifies a pre-existing {@link ExecutorService} for handling HTTP/2 streams. If called, it will clear any value set
         * previously by {@link #threadPoolConfig(ThreadPoolConfig)}.
         */
        public Http2ConfigurationBuilder executorService(final ExecutorService val) {
            threadPoolConfig = null;
            executorService = val;
            return this;
        }

        /**
         * @see #setPriorKnowledge(boolean)
         */
        public Http2ConfigurationBuilder priorKnowledge(final boolean val) {
            priorKnowledge = val;
            return this;
        }

        /**
         * @see #setPushEnabled(boolean)
         */
        public Http2ConfigurationBuilder enablePush(final boolean val) {
            pushEnabled = val;
            return this;
        }

        /**
         * @see #setStreamsHighWaterMark(float)
         */
        public Http2ConfigurationBuilder streamsHighWaterMark(final float val) {
            streamsHighWaterMark = val;
            return this;
        }

        /**
         * @see #setCleanPercentage(float)
         */
        public Http2ConfigurationBuilder cleanPercentage(final float val) {
            cleanPercentage = val;
            return this;
        }

        /**
         * @see #setCleanFrequencyCheck(int)
         */
        public Http2ConfigurationBuilder cleanFrequencyCheck(final int val) {
            cleanFrequencyCheck = val;
            return this;
        }

        /**
         * Sets the limit for incoming {@code RST_STREAM} frames on each server connection. The budget starts at the
         * burst size and refills continuously at the given rate up to that size. Exceeding the available budget closes
         * the connection with a best effort GOAWAY frame. Resets of streams that are already closed are counted too. The
         * default burst size is {@value Http2Configuration#DEFAULT_RESET_STREAM_BURST} and the default refill rate is
         * {@value Http2Configuration#DEFAULT_RESET_STREAMS_PER_SECOND} per second.
         *
         * @param burst maximum burst size, or zero to disable the limit
         * @param refillPerSecond number of allowances restored per second, or zero for a budget that never refills
         * @return this builder
         * @throws IllegalArgumentException if either value is negative
         * @since 5.0.4
         */
        public Http2ConfigurationBuilder resetStreamRateLimit(final int burst, final int refillPerSecond) {
            if (burst < 0 || refillPerSecond < 0) {
                throw new IllegalArgumentException("Reset stream burst and refill rate must be non-negative");
            }
            resetStreamBurst = burst;
            resetStreamsPerSecond = refillPerSecond;
            return this;
        }

        /**
         * @return a new {@link Http2Configuration} instances based on the values of this builder.
         */
        public Http2Configuration build() {
            return new Http2Configuration(this);
        }
    }
}
