/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http2.frames.ErrorCode;
import org.glassfish.grizzly.memory.ByteBufferManager;
import org.glassfish.grizzly.memory.MemoryManager;
import org.junit.Test;

/**
 * Verifies the minimal validation of field names and values required by RFC 9113, section 8.2.1.
 */
public class Http2FieldValidityTest {

    @Test
    public void nameWithSpaceIsRejected() throws Exception {
        assertRequestRejected("x y", "v");
    }

    @Test
    public void nameWithControlCharIsRejected() throws Exception {
        assertRequestRejected("x\u0001y", "v");
    }

    @Test
    public void nameWithDelIsRejected() throws Exception {
        assertRequestRejected("x\u007fy", "v");
    }

    @Test
    public void nameWithHighByteIsRejected() throws Exception {
        assertRequestRejected("x\u00e9y", "v");
    }

    @Test
    public void nameWithUpperCaseIsRejected() throws Exception {
        assertRequestRejected("x-A", "v");
    }

    @Test
    public void nameWithColonIsRejected() throws Exception {
        assertRequestRejected("x:y", "v");
    }

    @Test
    public void valueWithNulIsRejected() throws Exception {
        assertRequestRejected("x", "a\u0000b");
    }

    @Test
    public void valueWithCrIsRejected() throws Exception {
        assertRequestRejected("x", "a\rb");
    }

    @Test
    public void valueWithLfIsRejected() throws Exception {
        assertRequestRejected("x", "a\nb");
    }

    @Test
    public void valueWithControlCharIsRejected() throws Exception {
        assertRequestRejected("x", "a\u0001b");
    }

    @Test
    public void valueWithDelIsRejected() throws Exception {
        assertRequestRejected("x", "a\u007fb");
    }

    @Test
    public void valueWithLeadingSpaceIsRejected() throws Exception {
        assertRequestRejected("x", " v");
    }

    @Test
    public void valueWithTrailingSpaceIsRejected() throws Exception {
        assertRequestRejected("x", "v ");
    }

    @Test
    public void valueWithLeadingTabIsRejected() throws Exception {
        assertRequestRejected("x", "\tv");
    }

    @Test
    public void valueWithTrailingTabIsRejected() throws Exception {
        assertRequestRejected("x", "v\t");
    }

    @Test
    public void droppedHostHeaderIsValidated() throws Exception {
        assertRequestRejected("host", "a\rb");
    }

    @Test
    public void emptyNameIsRejected() throws Exception {
        assertRequestRejected("", "v");
    }

    @Test
    public void contentLengthNotANumberIsRejected() throws Exception {
        assertRequestRejected("content-length", "abc");
    }

    @Test
    public void contentLengthListIsRejected() throws Exception {
        assertRequestRejected("content-length", "1,1");
    }

    @Test
    public void contentLengthWithSignIsRejected() throws Exception {
        assertRequestRejected("content-length", "-1");
        assertRequestRejected("content-length", "+1");
    }

    @Test
    public void contentLengthTooLargeIsRejected() throws Exception {
        assertRequestRejected("content-length", "99999999999999999999");
    }

    @Test
    public void validContentLengthIsAccepted() throws Exception {
        assertEquals(1234L, decodeRequest("content-length", "1234").getContentLength());
    }

    @Test
    public void validFieldsAreAccepted() throws Exception {
        final Http2Request request = decodeRequest("x-tab", "a\tb", "x-space", "a b", "x-obs", "caf\u00e9", "x-empty", "", "x-token",
                "!#$%&'*+-.^_`|~09az");
        assertEquals("a\tb", request.getHeader("x-tab"));
        assertEquals("a b", request.getHeader("x-space"));
        assertEquals("caf\u00e9", request.getHeader("x-obs"));
        assertEquals("", request.getHeader("x-empty"));
        assertEquals("!#$%&'*+-.^_`|~09az", request.getHeader("x-token"));
    }

    @Test
    public void validNameCharsAreAccepted() throws Exception {
        assertEquals("v", decodeRequest("!#$%&'*+-.^_`|~09az", "v").getHeader("!#$%&'*+-.^_`|~09az"));
    }

    @Test
    public void authorityWithCrLfIsRejected() throws Exception {
        assertPseudoHeadersRejected("GET", "http", "/", "localhost\r\nx-injected: 1");
    }

    @Test
    public void pathWithCrLfIsRejected() throws Exception {
        assertPseudoHeadersRejected("GET", "http", "/a\r\nx-injected: 1", "localhost");
    }

    @Test
    public void methodWithLfIsRejected() throws Exception {
        assertPseudoHeadersRejected("GE\nT", "http", "/", "localhost");
    }

    @Test
    public void schemeWithCrIsRejected() throws Exception {
        assertPseudoHeadersRejected("GET", "ht\rp", "/", "localhost");
    }

    @Test
    public void authorityWithTrailingSpaceIsRejected() throws Exception {
        assertPseudoHeadersRejected("GET", "http", "/", "localhost ");
    }

    @Test
    public void validPseudoHeadersAreAccepted() throws Exception {
        final Http2Request request = decodeRequestWithPseudoHeaders(new String[] { ":method", "POST", ":scheme", "https", ":path", "/a/b?c=d&e", ":authority",
                "example.com:8443" });
        assertEquals("POST", request.getMethod().getMethodString());
        assertEquals("/a/b", request.getRequestURI());
        assertEquals("c=d&e", request.getQueryString());
        assertEquals("example.com:8443", request.getHeader("host"));
    }

    @Test
    public void responseNameWithUpperCaseIsRejected() throws Exception {
        assertResponseRejected("x-A", "v");
    }

    @Test
    public void responseValueWithLeadingSpaceIsRejected() throws Exception {
        assertResponseRejected("x", " v");
    }

    @Test
    public void responseStatusIsAccepted() throws Exception {
        assertEquals(204, decodeResponse("x", "v").getStatus());
    }

    private void assertRequestRejected(final String name, final String value) throws Exception {
        try {
            decodeRequest(name, value);
            fail("Expected a stream error for [" + name + ": " + value + "]");
        } catch (HeaderDecodingException e) {
            assertEquals(ErrorCode.PROTOCOL_ERROR, e.getErrorCode());
            assertEquals(HeaderDecodingException.ErrorType.STREAM, e.getErrorType());
        }
    }

    private void assertResponseRejected(final String name, final String value) throws Exception {
        try {
            decodeResponse(name, value);
            fail("Expected an error for [" + name + ": " + value + "]");
        } catch (IOException e) {
            assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof HeaderDecodingException);
        }
    }

    private void assertPseudoHeadersRejected(final String method, final String scheme, final String path, final String authority) throws Exception {
        try {
            decodeRequestWithPseudoHeaders(new String[] { ":method", method, ":scheme", scheme, ":path", path, ":authority", authority });
            fail("Expected a stream error for " + method + " " + scheme + " " + path + " " + authority);
        } catch (HeaderDecodingException e) {
            assertEquals(ErrorCode.PROTOCOL_ERROR, e.getErrorCode());
            assertEquals(HeaderDecodingException.ErrorType.STREAM, e.getErrorType());
        }
    }

    private Http2Request decodeRequest(final String... namesAndValues) throws Exception {
        return decodeRequestWithPseudoHeaders(new String[] { ":method", "GET", ":scheme", "http", ":path", "/", ":authority", "localhost" }, namesAndValues);
    }

    private Http2Request decodeRequestWithPseudoHeaders(final String[] pseudoHeaders, final String... namesAndValues) throws Exception {
        final MemoryManager<?> memoryManager = new ByteBufferManager();
        final Http2Session session = TestUtils.newServerSession(memoryManager);
        final HeadersEncoder encoder = new HeadersEncoder(memoryManager, 4096);
        for (int i = 0; i < pseudoHeaders.length; i += 2) {
            encoder.encodeHeader(pseudoHeaders[i], pseudoHeaders[i + 1], null);
        }
        for (int i = 0; i < namesAndValues.length; i += 2) {
            encoder.encodeHeader(namesAndValues[i], namesAndValues[i + 1], null);
        }
        session.getHeadersDecoder().append(encoder.flushHeaders());

        final Http2Request request = Http2Request.create();
        DecoderUtils.decodeRequestHeaders(session, request, null);
        return request;
    }

    private HttpResponsePacket decodeResponse(final String name, final String value) throws Exception {
        final MemoryManager<?> memoryManager = new ByteBufferManager();
        final Http2Session session = TestUtils.newServerSession(memoryManager);
        final HeadersEncoder encoder = new HeadersEncoder(memoryManager, 4096);
        encoder.encodeHeader(":status", "204", null);
        encoder.encodeHeader(name, value, null);
        session.getHeadersDecoder().append(encoder.flushHeaders());

        final HttpResponsePacket response = Http2Request.create().getResponse();
        DecoderUtils.decodeResponseHeaders(session, response, null);
        return response;
    }
}
