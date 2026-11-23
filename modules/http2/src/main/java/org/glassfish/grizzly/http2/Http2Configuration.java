/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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
    public static final int DEFAULT_MAX_RESET_BURST = 1000;
    public static final int DEFAULT_MAX_RESETS_PER_SECOND = 100;
    public static final int DEFAULT_MAX_CONTROL_FRAME_BURST = 1000;
    public static final int DEFAULT_MAX_CONTROL_FRAMES_PER_SECOND = 100;
    public static final int DEFAULT_MAX_CONTINUATION_FRAMES = 64;

    private volatile int maxConcurrentStreams;
    private volatile int initialWindowSize;
    private volatile int maxFramePayloadSize;
    private volatile int maxHeaderListSize;
    private volatile float streamsHighWaterMark;
    private volatile float cleanPercentage;
    private volatile int cleanFrequencyCheck;
    private volatile int maxResetBurst;
    private volatile int maxResetsPerSecond;
    private volatile int maxControlFrameBurst;
    private volatile int maxControlFramesPerSecond;
    private volatile int maxContinuationFrames;
    private volatile boolean disableCipherCheck;
    private volatile boolean priorKnowledge;
    private volatile boolean pushEnabled;
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
        maxResetBurst = builder.maxResetBurst;
        maxResetsPerSecond = builder.maxResetsPerSecond;
        maxControlFrameBurst = builder.maxControlFrameBurst;
        maxControlFramesPerSecond = builder.maxControlFramesPerSecond;
        maxContinuationFrames = builder.maxContinuationFrames;
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
     * @return how many stream resets a peer may cause on one connection in a burst, before
     * {@link #getMaxResetsPerSecond()} applies. If not explicitly configured, this returns
     * {@value #DEFAULT_MAX_RESET_BURST}.
     * @see #setMaxResetBurst(int)
     */
    public int getMaxResetBurst() {
        return maxResetBurst;
    }

    /**
     * Sets how many stream resets a peer may cause on one server connection in a burst. Every {@code RST_STREAM} frame
     * received from the client counts, except the first one of a stream the server has pushed, which declines the push,
     * and so does every stream the
     * server resets because of a stream error in the client's frames. A reset stream frees its slot of
     * {@link #getMaxConcurrentStreams()} right away, while its request may still be in progress, so that limit alone does
     * not bound the requests a connection may start and abandon over time; this budget does. Once the budget is used up,
     * the server ends the connection with {@code GOAWAY(ENHANCE_YOUR_CALM)}.
     * <p>
     * A {@code RST_STREAM} the server sends in answer to a frame on a stream that is closed already, e.g. DATA a client
     * sent before it learned that the stream was refused, does not count, so a well-behaved client is not disconnected
     * for it. Such answers come out of a second budget of the same size; past it, such frames are ignored without an
     * answer, and the connection stays open.
     * <p>
     * The budget is a token bucket: it holds up to this many resets and is refilled at
     * {@link #getMaxResetsPerSecond()}. A value of zero or less disables the limit.
     * <p>
     * A connection takes its budget from this configuration when it is created, so a change only applies to connections
     * created afterwards.
     */
    public void setMaxResetBurst(final int maxResetBurst) {
        this.maxResetBurst = maxResetBurst;
    }

    /**
     * @return how many stream resets per second a peer may cause on one connection over time. If not explicitly
     * configured, this returns {@value #DEFAULT_MAX_RESETS_PER_SECOND}.
     * @see #setMaxResetBurst(int)
     */
    public int getMaxResetsPerSecond() {
        return maxResetsPerSecond;
    }

    /**
     * Sets how many stream resets per second a peer may cause on one server connection over time, see
     * {@link #setMaxResetBurst(int)}. A value of zero or less means the budget is never refilled: a connection may cause
     * at most {@link #getMaxResetBurst()} resets in total.
     */
    public void setMaxResetsPerSecond(final int maxResetsPerSecond) {
        this.maxResetsPerSecond = maxResetsPerSecond;
    }

    /**
     * @return how many control frames a peer may send on one connection in a burst, before
     * {@link #getMaxControlFramesPerSecond()} applies. If not explicitly configured, this returns
     * {@value #DEFAULT_MAX_CONTROL_FRAME_BURST}.
     * @see #setMaxControlFrameBurst(int)
     */
    public int getMaxControlFrameBurst() {
        return maxControlFrameBurst;
    }

    /**
     * Sets how many control frames, that is frames which carry no request or response data but make this endpoint respond
     * or do work, the peer may send on one connection in a burst: {@code PING} and {@code SETTINGS} frames without the
     * ACK flag, which this endpoint acknowledges, {@code PRIORITY} frames, {@code DATA}, {@code HEADERS} and
     * {@code CONTINUATION} frames without any payload that neither end the stream nor the header block, and frames of
     * unknown types, which this endpoint ignores. Once the budget is used up, the connection is ended with
     * {@code GOAWAY(ENHANCE_YOUR_CALM)}.
     * <p>
     * {@code WINDOW_UPDATE} frames and {@code DATA} frames carrying data are not counted: a regular peer sends them at a
     * rate that grows with the amount of data transferred, so any fixed budget would end fast, legitimate transfers, and
     * flow control already bounds the data they let through.
     * <p>
     * The budget is a token bucket: it holds up to this many frames and is refilled at
     * {@link #getMaxControlFramesPerSecond()}. A value of zero or less disables the limit.
     * <p>
     * A connection takes its budget from this configuration when it is created, so a change only applies to connections
     * created afterwards.
     */
    public void setMaxControlFrameBurst(final int maxControlFrameBurst) {
        this.maxControlFrameBurst = maxControlFrameBurst;
    }

    /**
     * @return how many control frames per second a peer may send on one connection over time. If not explicitly
     * configured, this returns {@value #DEFAULT_MAX_CONTROL_FRAMES_PER_SECOND}.
     * @see #setMaxControlFrameBurst(int)
     */
    public int getMaxControlFramesPerSecond() {
        return maxControlFramesPerSecond;
    }

    /**
     * Sets how many control frames per second a peer may send on one connection over time, see
     * {@link #setMaxControlFrameBurst(int)}. A value of zero or less means the budget is never refilled: a connection
     * may send at most {@link #getMaxControlFrameBurst()} such frames in total.
     */
    public void setMaxControlFramesPerSecond(final int maxControlFramesPerSecond) {
        this.maxControlFramesPerSecond = maxControlFramesPerSecond;
    }

    /**
     * @return the maximum number of {@code CONTINUATION} frames in one header block. If not explicitly configured, this
     * returns {@value #DEFAULT_MAX_CONTINUATION_FRAMES}.
     */
    public int getMaxContinuationFrames() {
        return maxContinuationFrames;
    }

    /**
     * Sets the maximum number of {@code CONTINUATION} frames the peer may send in one header block. The header list size
     * limits the bytes of a header block; this limits the number of frames it is split into, which small or empty
     * {@code CONTINUATION} frames do not add to the size. A header block with more frames ends the connection with
     * {@code GOAWAY(ENHANCE_YOUR_CALM)}. A value of zero or less disables the limit.
     * <p>
     * Unlike the reset and control frame budgets, a change applies to open connections as well.
     */
    public void setMaxContinuationFrames(final int maxContinuationFrames) {
        this.maxContinuationFrames = maxContinuationFrames;
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
        private int maxResetBurst = DEFAULT_MAX_RESET_BURST;
        private int maxResetsPerSecond = DEFAULT_MAX_RESETS_PER_SECOND;
        private int maxControlFrameBurst = DEFAULT_MAX_CONTROL_FRAME_BURST;
        private int maxControlFramesPerSecond = DEFAULT_MAX_CONTROL_FRAMES_PER_SECOND;
        private int maxContinuationFrames = DEFAULT_MAX_CONTINUATION_FRAMES;
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
         * @see #setMaxResetBurst(int)
         */
        public Http2ConfigurationBuilder maxResetBurst(final int val) {
            maxResetBurst = val;
            return this;
        }

        /**
         * @see #setMaxResetsPerSecond(int)
         */
        public Http2ConfigurationBuilder maxResetsPerSecond(final int val) {
            maxResetsPerSecond = val;
            return this;
        }

        /**
         * @see #setMaxControlFrameBurst(int)
         */
        public Http2ConfigurationBuilder maxControlFrameBurst(final int val) {
            maxControlFrameBurst = val;
            return this;
        }

        /**
         * @see #setMaxControlFramesPerSecond(int)
         */
        public Http2ConfigurationBuilder maxControlFramesPerSecond(final int val) {
            maxControlFramesPerSecond = val;
            return this;
        }

        /**
         * @see #setMaxContinuationFrames(int)
         */
        public Http2ConfigurationBuilder maxContinuationFrames(final int val) {
            maxContinuationFrames = val;
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
