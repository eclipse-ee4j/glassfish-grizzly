/*
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
    // the error has made this side close the stream, which was open until then (and the exception is to be answered
    // with a RST_STREAM nevertheless), see Http2Session.resetLocally
    private boolean closedOpenStream;

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

    boolean isClosedOpenStream() {
        return closedOpenStream;
    }

    Http2StreamException setClosedOpenStream(final boolean closedOpenStream) {
        this.closedOpenStream = closedOpenStream;
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
