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
import org.glassfish.grizzly.http.util.Ascii;
import org.glassfish.grizzly.http.util.Constants;
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
        // A malformed request is a stream error, but the HPACK dynamic table is shared by all the streams of the
        // session: the rest of the header block still has to be decoded, otherwise the entries it adds to the table are
        // lost and the header blocks of the following streams are decoded against a wrong table.
        final HeaderDecodingException[] streamError = new HeaderDecodingException[1];
        try {
            http2Session.getHeadersDecoder().decode(new DecodingCallback() {

                @Override
                public void onDecoded(CharSequence name, CharSequence value) {
                    if (streamError[0] != null) {
                        return;
                    }
                    try {
                        processRequestHeader(request, serviceHeaders, noMoreServiceHeaders, name, value, capture);
                    } catch (HeaderDecodingException hde) {
                        if (hde.getErrorType() != ErrorType.STREAM) {
                            throw hde;
                        }
                        streamError[0] = hde;
                    }
                }

            });
            if (streamError[0] != null) {
                throw streamError[0];
            }
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

    private static void processRequestHeader(final HttpRequestPacket request, final Set<String> serviceHeaders, final AtomicBoolean noMoreServiceHeaders,
            final CharSequence name, final CharSequence value, final Map<String, String> capture) {
        if (capture != null) {
            capture.put(name.toString(), value.toString());
        }
        if (name.length() == 0) {
            throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM, "Empty header name");
        }
        // the characters of the name, upper case ones included, are validated by validateHeaderCharacters()
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

    /**
     * Decodes the pending header block and discards the headers. HPACK decoding updates the dynamic table shared by
     * all the streams of the session, so a header block that is not processed (a refused stream, a stream that is
     * unknown or ignored after GOAWAY) still has to be decoded (RFC 9113, section 4.3), otherwise the header blocks of
     * the following streams are decoded against a wrong table.
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
        validateHeaderCharacters(name, value);

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
            return;
        }
        }

        LOGGER.log(Level.FINE, "Skipping unknown service header[{0}={1}]", new Object[] { name, value });
    }

    private static void processNormalHeader(final HttpHeader httpHeader, final String name, final String value) {
        // a field that is dropped still makes the message malformed (RFC 9113, section 8.2.1)
        validateHeaderCharacters(name, value);
        if (name.equals(Header.Host.getLowerCase())) {
            return;
        }
        final MimeHeaders mimeHeaders = httpHeader.getHeaders();

        final DataChunk valueChunk = mimeHeaders.addValue(name);

        valueChunk.setString(value);
        finalizeKnownHeader(httpHeader, name, value);
    }

    private static void finalizeKnownHeader(final HttpHeader httpHeader, final String name, final String value) {

        switch (name) {
        case "content-length": {
            // Content-Length = 1*DIGIT (RFC 9110, section 8.6); an invalid value makes the message malformed, which is a
            // stream error (RFC 9113, section 8.1.1)
            try {
                httpHeader.setContentLengthLong(Ascii.parseLong(value, 0, value.length()));
            } catch (NumberFormatException e) {
                throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM, "Invalid content-length: " + value);
            }
            return;
        }

        case "expect": {
            // a request header; a response may still carry it
            if (httpHeader.isRequest()) {
                ((Http2Request) httpHeader).requiresAcknowledgement(true);
            }
            return;
        }

        // RFC 9113, section 8.2.2: connection-specific header fields must not be used in HTTP/2. A message that
        // carries any of them has to be treated as malformed.
        case "connection":
        case "proxy-connection":
        case "keep-alive":
        case "transfer-encoding":
        case "upgrade": {
            throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM, "Connection-specific header field not allowed in HTTP/2: " + name);
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
        int idx = indexOfInvalidNameChar(name);
        if (idx != -1) {
            final String msg = String.format(INVALID_CHARACTER_MESSAGE, (int) name.charAt(idx), idx, "name", name, value);
            throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM, msg);
        }
        idx = indexOfInvalidValueChar(value);
        if (idx != -1) {
            final String msg = String.format(INVALID_CHARACTER_MESSAGE, (int) value.charAt(idx), idx, "value", name, value);
            throw new HeaderDecodingException(ErrorCode.PROTOCOL_ERROR, ErrorType.STREAM, msg);
        }
    }

    /**
     * RFC 9113, section 8.2.1: a field name must not contain 0x00-0x20, 0x41-0x5a ('A' to 'Z') or 0x7f-0xff, nor a
     * colon other than the one that starts the name of a pseudo-header field.
     *
     * @return the index of the first invalid character, or {@code -1}
     */
    private static int indexOfInvalidNameChar(final CharSequence name) {
        for (int i = 0, len = name.length(); i < len; i++) {
            final char c = name.charAt(i);
            if (c <= Constants.SP || c >= 0x7F || (c >= Constants.A && c <= Constants.Z) || (c == Constants.COLON && i > 0)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * RFC 9113, section 8.2.1: a field value must not contain NUL, LF or CR, nor start or end with SP or HTAB. The
     * other control characters (0x01-0x1f and DEL) are rejected too, as in Section 5.5 of RFC 9110; HTAB is allowed
     * inside the value.
     *
     * @return the index of the first invalid character, or {@code -1}
     */
    private static int indexOfInvalidValueChar(final CharSequence value) {
        final int len = value.length();
        if (len == 0) {
            return -1;
        }
        if (isSpaceOrTab(value.charAt(0))) {
            return 0;
        }
        if (isSpaceOrTab(value.charAt(len - 1))) {
            return len - 1;
        }
        for (int i = 0; i < len; i++) {
            final char c = value.charAt(i);
            if ((c < Constants.SP && c != Constants.HT) || c == 0x7F || c > 0xFF) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isSpaceOrTab(final char c) {
        return c == Constants.SP || c == Constants.HT;
    }

}
