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
    public static final int DEFAULT_MAX_RESETS_PER_WINDOW = 200;
    public static final int DEFAULT_RESET_WINDOW_MILLIS = 30000;

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
    private volatile int maxResetsPerWindow;
    private volatile int resetWindowMillis;
    private volatile boolean holdSlotUntilProcessingComplete;
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
        maxResetsPerWindow = builder.maxResetsPerWindow;
        resetWindowMillis = builder.resetWindowMillis;
        holdSlotUntilProcessingComplete = builder.holdSlotUntilProcessingComplete;
    }

    // --------------------------------------------------------- Public Methods

    /**
     * @return a new {@link Http2ConfigurationBuilder} instance.
     */
    public static Http2ConfigurationBuilder builder() {
        return new Http2ConfigurationBuilder();
    }

    /**
     * @return the default maximum number of concurrent streams allowed for one session, -1 (the default) for
     * {@link Http2Session#getDefaultMaxConcurrentStreams() the default of 100}.
     *
     * @see #setMaxConcurrentStreams(int)
     */
    public int getMaxConcurrentStreams() {
        return maxConcurrentStreams;
    }

    /**
     * Sets the default maximum number of concurrent streams allowed for one session. -1, the default, stands for
     * {@link Http2Session#getDefaultMaxConcurrentStreams() the default of 100}: the number of streams can't be unlimited.
     * <p>
     * The limit applies to the requests being processed by the server as well, not just to the streams which are open: a
     * request whose stream the client has reset keeps counting until the server has finished processing it, and a request
     * over the limit waits for another one to finish, see {@link #setHoldSlotUntilProcessingComplete(boolean)}. So the
     * number of requests a connection has in progress at any moment never exceeds this value, however fast the client
     * resets streams.
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
     * @return the number of stream resets a connection may cause per {@link #getResetWindowMillis() window}. Negative
     * value means "unlimited". The default is {@link #DEFAULT_MAX_RESETS_PER_WINDOW}.
     *
     * @see #setMaxResetsPerWindow(int)
     */
    public int getMaxResetsPerWindow() {
        return maxResetsPerWindow;
    }

    /**
     * Sets the number of stream resets a connection may cause per {@link #getResetWindowMillis() window}. When a
     * connection exceeds it, the server sends a GOAWAY frame with the {@code ENHANCE_YOUR_CALM} error code and closes the
     * connection, and drops its requests which haven't been passed to the handler yet. A reset counts if the peer resets
     * a stream it has opened before the whole response to it has been sent (a response waiting for flow-control window
     * hasn't been), if the server resets a stream, which is still open, because of a protocol error of the peer, and if
     * the server refuses a stream for exceeding {@code SETTINGS_MAX_CONCURRENT_STREAMS}. The peer declining a pushed
     * stream doesn't count. Nor does a RST_STREAM the server sends in answer to a frame for a stream which is already
     * closed: these come out of a budget of their own, of the same size, past which such frames are ignored without an
     * answer, and the connection stays up. Together with the bound on the requests in progress (see
     * {@link #setHoldSlotUntilProcessingComplete(boolean)}), this protects against the HTTP/2 Rapid Reset attack
     * (CVE-2023-44487) and its variants. Negative value means "unlimited", which switches the check off.
     */
    public void setMaxResetsPerWindow(final int maxResetsPerWindow) {
        this.maxResetsPerWindow = maxResetsPerWindow;
    }

    /**
     * @return the length of the window, in milliseconds, {@link #getMaxResetsPerWindow()} is counted over. The default is
     * {@link #DEFAULT_RESET_WINDOW_MILLIS}.
     *
     * @see #setResetWindowMillis(int)
     */
    public int getResetWindowMillis() {
        return resetWindowMillis;
    }

    /**
     * Sets the length of the window, in milliseconds, {@link #getMaxResetsPerWindow()} is counted over. A connection may
     * cause that many resets at once, after which it is allowed {@code maxResetsPerWindow / resetWindowMillis} more per
     * millisecond. A value which is not positive switches the check off.
     */
    public void setResetWindowMillis(final int resetWindowMillis) {
        this.resetWindowMillis = resetWindowMillis;
    }

    /**
     * @return {@code true} (the default) if the number of requests a connection has in progress is bounded by
     * {@link #getMaxConcurrentStreams()}, including the requests whose stream has been reset.
     *
     * @see #setHoldSlotUntilProcessingComplete(boolean)
     */
    public boolean isHoldSlotUntilProcessingComplete() {
        return holdSlotUntilProcessingComplete;
    }

    /**
     * Sets whether the number of requests a connection has in progress is bounded by {@link #getMaxConcurrentStreams()}.
     * A request is in progress from the moment it is passed to the processing chain until the processing is complete
     * (the response has been completed, or the request has been recycled), even if the client resets its stream in the
     * meantime. A request which arrives when the connection has as many requests in progress as it may have waits until
     * one of them finishes; it is dropped if its stream is reset while it waits. It isn't refused: the client considers a
     * reset stream closed, so a well-behaved client may open a new stream in its place right away.
     * <p>
     * This is the fix for the HTTP/2 Rapid Reset attack (CVE-2023-44487): a client which opens streams and resets them
     * right away would otherwise keep the number of open streams low, while making the server process an unlimited
     * number of requests. A request, which is suspended and has no timeout, counts for as long as it is suspended, or
     * until its stream is reset, which cancels it.
     * <p>
     * Only switch it off if the number of requests in progress is bounded some other way. The default is {@code true}.
     */
    public void setHoldSlotUntilProcessingComplete(final boolean holdSlotUntilProcessingComplete) {
        this.holdSlotUntilProcessingComplete = holdSlotUntilProcessingComplete;
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
        private int maxResetsPerWindow = DEFAULT_MAX_RESETS_PER_WINDOW;
        private int resetWindowMillis = DEFAULT_RESET_WINDOW_MILLIS;
        private boolean holdSlotUntilProcessingComplete = true;
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
         * @see #setMaxResetsPerWindow(int)
         */
        public Http2ConfigurationBuilder maxResetsPerWindow(final int val) {
            maxResetsPerWindow = val;
            return this;
        }

        /**
         * @see #setResetWindowMillis(int)
         */
        public Http2ConfigurationBuilder resetWindowMillis(final int val) {
            resetWindowMillis = val;
            return this;
        }

        /**
         * @see #setHoldSlotUntilProcessingComplete(boolean)
         */
        public Http2ConfigurationBuilder holdSlotUntilProcessingComplete(final boolean val) {
            holdSlotUntilProcessingComplete = val;
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
