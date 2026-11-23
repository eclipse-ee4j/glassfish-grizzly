/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2013, 2020 Oracle and/or its affiliates. All rights reserved.
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

import org.glassfish.grizzly.http2.frames.ErrorCode;

/**
 * HTTP/2 Stream exception.
 *
 * @author Alexey Stashok
 */
public final class Http2StreamException extends IOException {
    private final int streamId;
    private final ErrorCode errorCode;
    // the frame causing the error was sent on a stream that was already closed on this side, see isOnClosedStream()
    private boolean onClosedStream;

    public Http2StreamException(final int streamId, final ErrorCode errorCode) {
        this.streamId = streamId;
        this.errorCode = errorCode;
    }

    public Http2StreamException(final int streamId, final ErrorCode errorCode, final String description) {
        super(description);

        this.streamId = streamId;
        this.errorCode = errorCode;
    }

    public Http2StreamException(final int streamId, final ErrorCode errorCode, final Throwable cause) {
        super(cause);

        this.streamId = streamId;
        this.errorCode = errorCode;
    }

    public Http2StreamException(final int streamId, final ErrorCode errorCode, final String description, final Throwable cause) {
        super(description, cause);

        this.streamId = streamId;
        this.errorCode = errorCode;
    }

    public int getStreamId() {
        return streamId;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * @return {@code true} if the error is about a frame the peer sent on a stream that was already closed on this side,
     * e.g. DATA the peer had sent before it learned that the stream was refused or answered. Such a frame costs this
     * endpoint nothing but the RST_STREAM in answer, so it does not count toward the peer's reset budget, see
     * {@link Http2Session#onFrameOnClosedStream()}.
     */
    boolean isOnClosedStream() {
        return onClosedStream;
    }

    Http2StreamException onClosedStream(final boolean onClosedStream) {
        this.onClosedStream = onClosedStream;
        return this;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(128);
        sb.append(getClass().getName()).append(" streamId=").append(streamId).append(" errorCode=").append(errorCode);

        String message = getLocalizedMessage();

        return message != null ? sb.append(": ").append(message).toString() : sb.toString();
    }
}
