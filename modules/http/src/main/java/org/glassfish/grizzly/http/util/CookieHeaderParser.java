/*
 * Copyright (c) 2022, 2024 Contributors to the Eclipse Foundation
 * Copyright 2004, 2022 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glassfish.grizzly.http.util;

import org.glassfish.grizzly.http.Cookies;
import org.glassfish.grizzly.http.LazyCookieState;

/**
 * <p>Cookie header parser based on RFC6265</p>
 * <p>The parsing of cookies using RFC6265 is more relaxed that the
 * specification in the following ways:</p>
 * <ul>
 *   <li>Values 0x80 to 0xFF are permitted in cookie-octet to support the use of
 *       UTF-8 in cookie values as used by HTML 5.</li>
 *   <li>For cookies without a value, the '=' is not required after the name as
 *       some browsers do not sent it.</li>
 * </ul>
 *
 * <p>Implementation note:<br>
 * This class has been carefully tuned. </p>
 *
 * @author The Tomcat team
 * @author Arjan Tijms
 */
public class CookieHeaderParser {

    private static final boolean isCookieOctet[] = new boolean[256];
    private static final boolean isText[] = new boolean[256];
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final byte TAB_BYTE = (byte) 0x09;
    private static final byte SPACE_BYTE = (byte) 0x20;
    private static final byte QUOTE_BYTE = (byte) 0x22;
    private static final byte COMMA_BYTE = (byte) 0x2C;
    private static final byte SEMICOLON_BYTE = (byte) 0x3B;
    private static final byte EQUALS_BYTE = (byte) 0x3D;
    private static final byte SLASH_BYTE = (byte) 0x5C;
    private static final byte DEL_BYTE = (byte) 0x7F;

    private static final int ARRAY_SIZE = 128;
    private static final boolean[] IS_CONTROL = new boolean[ARRAY_SIZE];
    private static final boolean[] IS_SEPARATOR = new boolean[ARRAY_SIZE];
    private static final boolean[] IS_TOKEN = new boolean[ARRAY_SIZE];


    static {
        // %x21 / %x23-2B / %x2D-3A / %x3C-5B / %x5D-7E (RFC6265)
        // %x80 to %xFF                                 (UTF-8)
        for (int i = 0; i < 256; i++) {
            if (i < 0x21 || i == QUOTE_BYTE || i == COMMA_BYTE || i == SEMICOLON_BYTE || i == SLASH_BYTE || i == DEL_BYTE) {
                isCookieOctet[i] = false;
            } else {
                isCookieOctet[i] = true;
            }
        }

        for (int i = 0; i < 256; i++) {
            if (i < TAB_BYTE || (i > TAB_BYTE && i < SPACE_BYTE) || i == DEL_BYTE) {
                isText[i] = false;
            } else {
                isText[i] = true;
            }
        }

        for (int i = 0; i < ARRAY_SIZE; i++) {
            // Control> 0-31, 127
            if (i < 32 || i == 127) {
                IS_CONTROL[i] = true;
            }

            // Separator
            if (    i == '(' || i == ')' || i == '<' || i == '>'  || i == '@'  ||
                    i == ',' || i == ';' || i == ':' || i == '\\' || i == '\"' ||
                    i == '/' || i == '[' || i == ']' || i == '?'  || i == '='  ||
                    i == '{' || i == '}' || i == ' ' || i == '\t') {
                IS_SEPARATOR[i] = true;
            }

            // Token: Anything 0-127 that is not a control and not a separator
            if (!IS_CONTROL[i] && !IS_SEPARATOR[i] && i < 128) {
                IS_TOKEN[i] = true;
            }
        }
    }


    private CookieHeaderParser() {
        // Hide default constructor
    }


    public static void parseCookie(byte[] bytes, int offset, int len, Cookies serverCookies) {

        // ByteBuffer is used throughout this parser as it allows the byte[]
        // and position information to be easily passed between parsing methods
        ByteBuffer byteBuffer = new ByteBuffer(bytes, offset, len);

        boolean moreToProcess = true;

        while (moreToProcess) {
            skipWhiteSpace(byteBuffer);

            ByteBuffer name = readToken(byteBuffer);
            ByteBuffer value = null;

            skipWhiteSpace(byteBuffer);

            SkipResult skipResult = skipByte(byteBuffer, EQUALS_BYTE);
            if (skipResult == SkipResult.FOUND) {
                skipWhiteSpace(byteBuffer);
                value = readCookieValueRfc6265(byteBuffer);
                if (value == null) {
                    // Invalid cookie value. Skip to the next semi-colon
                    skipUntilSemiColon(byteBuffer);
                    continue;
                }
                skipWhiteSpace(byteBuffer);
            }

            skipResult = skipByte(byteBuffer, SEMICOLON_BYTE);
            if (skipResult == SkipResult.FOUND) {
                // NO-OP
            } else if (skipResult == SkipResult.NOT_FOUND) {
                // Invalid cookie. Ignore it and skip to the next semi-colon
                skipUntilSemiColon(byteBuffer);
                continue;
            } else {
                // SkipResult.EOF
                moreToProcess = false;
            }

            if (name.hasRemaining()) {
                LazyCookieState lazyCookie = serverCookies.getNextUnusedCookie().getLazyCookieState();
                lazyCookie.getName().setBytes(name.array(), name.position(), name.position() + name.remaining());
                if (value == null) {
                    lazyCookie.getValue().setBytes(EMPTY_BYTES, 0, EMPTY_BYTES.length);
                } else {
                    lazyCookie.getValue().setBytes(value.array(), value.position(), value.position() + value.remaining());
                }
            }
        }
    }


    private static void skipWhiteSpace(ByteBuffer byteBuffer) {
        while(byteBuffer.hasRemaining()) {
            byte b = byteBuffer.get();
            if (b != TAB_BYTE && b != SPACE_BYTE) {
                byteBuffer.rewind();
                break;
            }
        }
    }


    private static void skipUntilSemiColon(ByteBuffer byteBuffer) {
        while(byteBuffer.hasRemaining()) {
            if (byteBuffer.get() == SEMICOLON_BYTE) {
                break;
            }
        }
    }


    private static SkipResult skipByte(ByteBuffer byteBuffer, byte target) {
        if (!byteBuffer.hasRemaining()) {
            return SkipResult.EOF;
        }
        if (byteBuffer.get() == target) {
            return SkipResult.FOUND;
        }

        byteBuffer.rewind();
        return SkipResult.NOT_FOUND;
    }


    /**
     * Similar to readCookieValue() but treats a comma as part of an invalid
     * value.
     */
    private static ByteBuffer readCookieValueRfc6265(ByteBuffer byteBuffer) {
        boolean quoted = false;

        int cookieValueStart = byteBuffer.position();
        int cookieValueEnd = byteBuffer.limit();

        while (byteBuffer.hasRemaining()) {
            byte byteFromBuffer = byteBuffer.get();
            if (isCookieOctet[(byteFromBuffer & 0xFF)]) {
                // NO-OP
            } else if (byteFromBuffer == SEMICOLON_BYTE || byteFromBuffer == SPACE_BYTE || byteFromBuffer == TAB_BYTE) {
                cookieValueEnd = byteBuffer.position() - 1;
                byteBuffer.position(cookieValueEnd);
                break;
            } else if (byteFromBuffer == QUOTE_BYTE && cookieValueStart == byteBuffer.position() -1) {
                quoted = true;
            } else if (quoted && byteFromBuffer == QUOTE_BYTE) {
                cookieValueEnd = byteBuffer.position();
                break;
            } else {
                // Invalid cookie
                return null;
            }
        }

        return new ByteBuffer(byteBuffer.bytes, cookieValueStart, cookieValueEnd - cookieValueStart);
    }


    private static ByteBuffer readToken(ByteBuffer byteBuffer) {
        final int start = byteBuffer.position();
        int end = byteBuffer.limit();
        while (byteBuffer.hasRemaining()) {
            if (!isToken(byteBuffer.get())) {
                end = byteBuffer.position() - 1;
                byteBuffer.position(end);
                break;
            }
        }

        return new ByteBuffer(byteBuffer.bytes, start, end - start);
    }

    public static boolean isToken(int c) {
        if (c < 0 || c >= ARRAY_SIZE) {
            // out of bounds
            return false;
        }
        // Fast for correct values, slower for incorrect ones
        try {
            return IS_TOKEN[c];
        } catch (ArrayIndexOutOfBoundsException ex) {
            return false;
        }
    }

    public static boolean isText(int c) {
        if (c < 0 || c >= 256) {
            // out of bounds
            return false;
        }
        // Fast for correct values, slower for incorrect ones
        try {
            return isText[c];
        } catch (ArrayIndexOutOfBoundsException ex) {
            return false;
        }
    }


    /**
     * Custom implementation that skips many of the safety checks in
     * {@link java.nio.ByteBuffer}.
     */
    private static class ByteBuffer {

        private final byte[] bytes;
        private int limit;
        private int position = 0;

        public ByteBuffer(byte[] bytes, int offset, int len) {
            this.bytes = bytes;
            this.position = offset;
            this.limit = offset + len;
        }

        public int position() {
            return position;
        }

        public void position(int position) {
            this.position = position;
        }

        public int limit() {
            return limit;
        }

        public int remaining() {
            return limit - position;
        }

        public boolean hasRemaining() {
            return position < limit;
        }

        public byte get() {
            return bytes[position++];
        }

        public void rewind() {
            position--;
        }

        public byte[] array() {
            return bytes;
        }

        // For debug purposes
        @Override
        public String toString() {
            return "position [" + position + "], limit [" + limit + "]";
        }
    }

    private static enum SkipResult {
        FOUND,
        NOT_FOUND,
        EOF
    }
}
