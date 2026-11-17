/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
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

import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.ProcessingState;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.http.util.Header;

/**
 *
 * @author oleksiys
 */
class Http2Request extends HttpRequestPacket implements Http2Header {

    private static final ThreadCache.CachedTypeIndex<Http2Request> CACHE_IDX = ThreadCache.obtainIndex(Http2Request.class, 2);

    public static Http2Request create() {
        Http2Request http2Request = ThreadCache.takeFromCache(CACHE_IDX);
        if (http2Request == null) {
            http2Request = new Http2Request();
        }

        return http2Request.init();
    }

    private final ProcessingState processingState = new ProcessingState();

    private final Http2Response response;

    /**
     * Char encoding parsed flag.
     */
    private boolean contentTypeParsed;

    Http2Request() {
        this.response = new Http2Response();
    }

    @Override
    public ProcessingState getProcessingState() {
        return processingState;
    }

    private Http2Request init() {
        setResponse(response);
        response.setRequest(this);

        setChunkingAllowed(true);
        response.setChunkingAllowed(true);

        return this;
    }

    @Override
    public Http2Stream getHttp2Stream() {
        return Http2Stream.getStreamFor(this);
    }

    /**
     * @return an empty string if the {@link #getHttp2Stream()} returns null, it's id otherwise.
     */
    @Override
    public String getProtocolRequestId() {
        Http2Stream stream = getHttp2Stream();
        if (stream == null) {
            return "";
        }
        return Integer.toString(stream.getId());
    }

    @Override
    public String getCharacterEncoding() {
        if (!contentTypeParsed) {
            parseContentTypeHeader();
        }

        return super.getCharacterEncoding();
    }

    @Override
    public void setCharacterEncoding(final String charset) {
        if (!contentTypeParsed) {
            parseContentTypeHeader();
        }

        super.setCharacterEncoding(charset);
    }

    @Override
    public String getContentType() {
        if (!contentTypeParsed) {
            parseContentTypeHeader();
        }

        return super.getContentType();
    }

    private void parseContentTypeHeader() {
        contentTypeParsed = true;

        if (!contentType.isSet()) {
            final DataChunk dc = headers.getValue(Header.ContentType);

            if (dc != null && !dc.isNull()) {
                setContentType(dc.toString());
            }
        }
    }

    @Override
    public Object getAttribute(final String name) {
        if (Http2Stream.HTTP2_STREAM_ATTRIBUTE.equals(name)) {
            return response.getHttp2Stream();
        }

        return super.getAttribute(name);
    }

    @Override
    protected void reset() {
        contentTypeParsed = false;

        processingState.recycle();

        super.reset();
    }

    @Override
    public void recycle() {
        // Tell the stream before the state is reset: the stream must stop touching this request, which may be reused for
        // another stream from now on, and a request which never made it to the end of its processing (e.g. rejected by a
        // filter before the handler) must stop counting toward the requests in progress.
        final Http2Stream stream = getHttp2Stream();
        if (stream != null) {
            stream.onRequestRecycled();
        }

        reset();

        ThreadCache.putToCache(CACHE_IDX, this);
    }

    @Override
    public void setExpectContent(final boolean isExpectContent) {
        super.setExpectContent(isExpectContent);
    }

    @Override
    protected void requiresAcknowledgement(final boolean requiresAcknowledgement) {
        super.requiresAcknowledgement(requiresAcknowledgement);
    }

    /**
     * @param unparsedHostC the unparsedHostC to set
     */
    public void setUnparsedHostC(DataChunk unparsedHostC) {
        this.unparsedHostC = unparsedHostC;
    }
}
