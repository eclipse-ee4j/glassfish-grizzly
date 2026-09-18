/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2014, 2020 Oracle and/or its affiliates. All rights reserved.
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.http2.HeaderDecodingException.ErrorType;
import org.glassfish.grizzly.http2.frames.ErrorCode;
import org.glassfish.grizzly.http2.hpack.DecodingCallback;

/**
 * Http2Frames -> HTTP Packet decoder utils.
 *
 * @author Grizzly team
 */
class DecoderUtils extends EncoderDecoderUtilsBase {
    private final static Logger LOGGER = Grizzly.logger(DecoderUtils.class);

    private static final String INVALID_CHARACTER_MESSAGE = "Invalid character 0x%02x at index '%s' found in header %s [%s: %s]";

    static void decodeRequestHeaders(final Http2Session http2Session, final HttpRequestPacket request, final Map<String, String> capture)
            throws IOException, HeaderDecodingException {

        final Set<String> serviceHeaders = new HashSet<>();
        final AtomicBoolean noMoreServiceHeaders = new AtomicBoolean();
        try {
            http2Session.getHeadersDecoder().decode(new DecodingCallback() {

                @Override
                public void onDecoded(CharSequence name, CharSequence value) {
                    if (capture != null) {
                        capture.put(name.toString(), value.toString());
                    }
                    for (int i = 0, len = name.length(); i < len; i++) {
                        if (Character.isUpperCase(name.charAt(i))) {
                            throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM);
                        }
                    }
                    if (name.charAt(0) == ':') {
                        if (noMoreServiceHeaders.get()) {
                            throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM);
                        }
                        processServiceRequestHeader(request, serviceHeaders, name.toString(), value.toString());
                    } else {
                        noMoreServiceHeaders.compareAndSet(false, true);
                        processNormalHeader(request, name.toString(), value.toString());
                    }
                }

            });
            if (serviceHeaders.size() != 3) {
                throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM);
            }
        } catch (RuntimeException re) {
            if (re instanceof HeaderDecodingException) {
                throw re;
            }
            throw new IOException(re);
        } finally {
            request.setProtocol(Protocol.HTTP_2_0);
            request.getResponse().setProtocol(Protocol.HTTP_2_0);
        }
    }

    /**
     * Decodes the pending header block and discards the headers. HPACK decoding updates the dynamic table shared by
     * all the streams of the session, so the header block of a refused stream still has to be decoded, otherwise the
     * header blocks of the following streams are decoded against a wrong table.
     *
     * @param http2Session the {@link Http2Session} which received the header block
     * @throws Http2SessionException if the header block cannot be decoded
     */
    static void skipHeaders(final Http2Session http2Session) throws Http2SessionException {
        try {
            http2Session.getHeadersDecoder().decode(new DecodingCallback() {

                @Override
                public void onDecoded(final CharSequence name, final CharSequence value) {
                }

            });
        } catch (RuntimeException re) {
            throw new Http2SessionException(ErrorCode.COMPRESSION_ERROR, re.getMessage());
        }
    }

    static void decodeResponseHeaders(final Http2Session http2Session, final HttpResponsePacket response, final Map<String, String> capture)
            throws IOException {

        try {
            http2Session.getHeadersDecoder().decode(new DecodingCallback() {

                @Override
                public void onDecoded(final CharSequence name, final CharSequence value) {
                    if (capture != null) {
                        capture.put(name.toString(), value.toString());
                    }
                    if (name.charAt(0) == ':') {
                        processServiceResponseHeader(response, name.toString(), value.toString());
                    } else {
                        processNormalHeader(response, name.toString(), value.toString());
                    }
                }

            });
        } catch (RuntimeException re) {
            throw new IOException(re);
        } finally {
            response.setProtocol(Protocol.HTTP_2_0);
            response.getRequest().setProtocol(Protocol.HTTP_2_0);
        }

    }

    static void decodeTrailerHeaders(final Http2Session http2Session, final HttpHeader header, final Map<String, String> capture) throws IOException {
        try {
            final MimeHeaders headers = header.getHeaders();
            http2Session.getHeadersDecoder().decode(new DecodingCallback() {

                @Override
                public void onDecoded(final CharSequence name, final CharSequence value) {
                    if (capture != null) {
                        capture.put(name.toString(), value.toString());
                    }
                    // TODO trailer validation
                    headers.addValue(name.toString()).setString(value.toString());
                }

            });
        } catch (RuntimeException re) {
            throw new IOException(re);
        }
    }

    private static void processServiceRequestHeader(final HttpRequestPacket request, final Set<String> serviceHeaders, final String name, final String value) {

        final int valueLen = value.length();

        switch (name) {
        case PATH_HEADER: {
            if (!serviceHeaders.add(name)) {
                throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM, "Duplicate " + PATH_HEADER);
            }
            if (value.isEmpty()) {
                throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM, "Empty " + PATH_HEADER);
            }
            int questionIdx = value.indexOf('?');

            if (questionIdx == -1) {
                request.getRequestURIRef().init(value);
            } else {
                request.getRequestURIRef().init(value.substring(0, questionIdx));
                if (questionIdx < valueLen - 1) {
                    request.getQueryStringDC().setString(value.substring(questionIdx + 1));
                }
            }

            return;
        }
        case METHOD_HEADER: {
            if (!serviceHeaders.add(name)) {
                throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM, "Duplicate " + METHOD_HEADER);
            }
            request.getMethodDC().setString(value);
            return;
        }
        case SCHEMA_HEADER: {
            if (!serviceHeaders.add(name)) {
                throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM, "Duplicate " + SCHEMA_HEADER);
            }
            request.setSecure(valueLen == 5); // support http and https only
            return;
        }
        case AUTHORITY_HEADER: {
            request.getHeaders().setValue(Header.Host).setString(value);
            return;
        }
        }

        throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM, "Unknown service header: " + name);
    }

    private static void processServiceResponseHeader(final HttpResponsePacket response, final String name, final String value) {
        validateHeaderCharacters(name, value);
        final int valueLen = value.length();
        switch (name) {
        case STATUS_HEADER: {
            if (valueLen != 3) {
                throw new IllegalStateException("Unexpected status code: " + value);
            }

            response.setStatus(Integer.parseInt(value));
        }
        }

        LOGGER.log(Level.FINE, "Skipping unknown service header[{0}={1}", new Object[] { name, value });
    }

    private static void processNormalHeader(final HttpHeader httpHeader, final String name, final String value) {
        if (name.equals(Header.Host.getLowerCase())) {
            return;
        }
        final MimeHeaders mimeHeaders = httpHeader.getHeaders();

        final DataChunk valueChunk = mimeHeaders.addValue(name);

        validateHeaderCharacters(name, value);
        valueChunk.setString(value);
        finalizeKnownHeader(httpHeader, name, value);
    }

    private static void finalizeKnownHeader(final HttpHeader httpHeader, final String name, final String value) {

        switch (name) {
        case "content-length": {
            httpHeader.setContentLengthLong(Long.parseLong(value));
            return;
        }

        case "upgrade": {
            httpHeader.getUpgradeDC().setString(value);
            return;
        }

        case "expect": {
            ((Http2Request) httpHeader).requiresAcknowledgement(true);
            return;
        }

        case "connection": {
            throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM, "Invalid use of connection header.");
        }

        case "te": {
            if (!"trailers".equals(value)) {
                throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM, "TE header only allowed a value of trailers.");
            }
            return;
        }
        }
    }

    private static void validateHeaderCharacters(final CharSequence name, final CharSequence value) {
        assert name != null;
        assert value != null;
        int idx = ensureRange(name);
        if (idx != -1) {
            final String msg = String.format(INVALID_CHARACTER_MESSAGE, (int) name.charAt(idx), idx, "name", name, value);
            throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM, msg);
        }
        idx = ensureRange(value);
        if (idx != -1) {
            final String msg = String.format(INVALID_CHARACTER_MESSAGE, (int) name.charAt(idx), idx, "value", name, value);
            throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM, msg);
        }
    }

    private static int ensureRange(final CharSequence cs) {
        for (int i = 0, len = cs.length(); i < len; i++) {
            final char c = cs.charAt(i);
            if (c < 0x20 || c > 0xFF) {
                return i;
            }
        }
        return -1;
    }

}
