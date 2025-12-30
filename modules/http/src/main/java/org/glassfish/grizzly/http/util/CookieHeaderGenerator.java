/*
 * Copyright (c) 2022, 2025 Contributors to the Eclipse Foundation
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.BitSet;
import java.util.Locale;
import java.util.Map;

/**
 * <p>Cookie header generator based on RFC6265</p>
 *
 * @author The Tomcat team
 * @author Arjan Tijms
 *
 */
public class CookieHeaderGenerator {

    // -------------------------------------------------- Cookie attribute names
    public static final String COOKIE_COMMENT_ATTR = "Comment";
    public static final String COOKIE_DOMAIN_ATTR = "Domain";
    public static final String COOKIE_MAX_AGE_ATTR = "Max-Age";
    public static final String COOKIE_PATH_ATTR = "Path";
    public static final String COOKIE_SECURE_ATTR = "Secure";
    public static final String COOKIE_HTTP_ONLY_ATTR = "HttpOnly";

    private static final String COOKIE_DATE_PATTERN = "EEE, dd-MMM-yyyy HH:mm:ss z";

    protected static final DateTimeFormatter COOKIE_DATE_FORMAT =
            DateTimeFormatter.ofPattern(COOKIE_DATE_PATTERN, Locale.US).withZone(ZoneId.of("GMT"));

    protected static final String ANCIENT_DATE = COOKIE_DATE_FORMAT.format(Instant.ofEpochMilli(10000));

    private static final BitSet domainValid = new BitSet(128);

    static {
        for (char c = '0'; c <= '9'; c++) {
            domainValid.set(c);
        }
        for (char c = 'a'; c <= 'z'; c++) {
            domainValid.set(c);
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            domainValid.set(c);
        }
        domainValid.set('.');
        domainValid.set('-');
    }

    public static String generateHeader(String name, String value, int maxAge, String domain, String path, boolean isSecure, boolean isHttpOnly, Map<String, String> attributes) {

        // Can't use StringBuilder due to DateFormat
        StringBuffer header = new StringBuffer();

        // TODO: Name validation takes place in Cookie and cannot be configured
        //       per Context. Moving it to here would allow per Context config
        //       but delay validation until the header is generated. However,
        //       the spec requires an IllegalArgumentException on Cookie
        //       generation.
        header.append(name);
        header.append('=');
        if (value != null && value.length() > 0) {
            validateCookieValue(value);
            header.append(value);
        }

        // RFC 6265 prefers Max-Age to Expires but... (see below)
        if (maxAge > -1) {
            // Negative Max-Age is equivalent to no Max-Age

            // Max age 0 is omit Max-Age itself, but conditionally (see below)
            // add Expires
            if (maxAge > 0) {
                header.append("; Max-Age=");
                header.append(maxAge);
            }

            if (CookieUtils.ALWAYS_ADD_EXPIRES) {
                // Microsoft IE and Microsoft Edge don't understand Max-Age so send
                // expires as well. Without this, persistent cookies fail with those
                // browsers. See http://tomcat.markmail.org/thread/g6sipbofsjossacn

                // Wdy, DD-Mon-YY HH:MM:SS GMT ( Expires Netscape format )
                header.append ("; Expires=");

                // To expire immediately we need to set the time in past
                if (maxAge == 0) {
                    header.append(ANCIENT_DATE);
                } else {
                    header.append(COOKIE_DATE_FORMAT.format(Instant.now().plusSeconds(maxAge)));
                }
            }
        }

        if (domain != null && domain.length() > 0) {
            validateDomain(domain);
            header.append("; Domain=");
            header.append(domain);
        }

        if (path != null && path.length() > 0) {
            validatePath(path);
            header.append("; Path=");
            header.append(path);
        }

        if (isSecure) {
            header.append("; Secure");
        }

        if (isHttpOnly) {
            header.append("; HttpOnly");
        }

        // Add the remaining attributes
        for (Map.Entry<String,String> entry : attributes.entrySet()) {
            switch (entry.getKey()) {
                case COOKIE_COMMENT_ATTR:
                case COOKIE_DOMAIN_ATTR:
                case COOKIE_MAX_AGE_ATTR:
                case COOKIE_PATH_ATTR:
                case COOKIE_SECURE_ATTR:
                case COOKIE_HTTP_ONLY_ATTR:
                    // Handled above so NO-OP
                    break;
                default: {
                    validateAttribute(entry.getKey(), entry.getValue());
                    header.append("; ");
                    header.append(entry.getKey());
                    if (!"".equals(entry.getValue())) {
                        header.append('=');
                        header.append(entry.getValue());
                    }
                }
            }
        }

        return header.toString();
    }


    private static void validateCookieValue(String value) {
        int start = 0;
        int end = value.length();

        if (end > 1 && value.charAt(0) == '"' && value.charAt(end - 1) == '"') {
            start = 1;
            end--;
        }

        char[] chars = value.toCharArray();
        for (int i = start; i < end; i++) {
            char c = chars[i];
            if (c < 0x21 || c == 0x22 || c == 0x2c || c == 0x3b || c == 0x5c || c == 0x7f) {
                throw new IllegalArgumentException(
                        "rfc6265CookieProcessor.invalidCharInValue " + Integer.toString(c));
            }
        }
    }


    private static void validateDomain(String domain) {
        int i = 0;
        int prev = -1;
        int cur = -1;
        char[] chars = domain.toCharArray();
        while (i < chars.length) {
            prev = cur;
            cur = chars[i];
            if (!domainValid.get(cur)) {
                throw new IllegalArgumentException(
                        "rfc6265CookieProcessor.invalidDomain " + domain);
            }
            // labels must start with a letter or number
            if ((prev == '.' || prev == -1) && (cur == '.' || cur == '-')) {
                throw new IllegalArgumentException(
                        "rfc6265CookieProcessor.invalidDomain " + domain);
            }
            // labels must end with a letter or number
            if (prev == '-' && cur == '.') {
                throw new IllegalArgumentException(
                        "rfc6265CookieProcessor.invalidDomain " + domain);
            }
            i++;
        }
        // domain must end with a label
        if (cur == '.' || cur == '-') {
            throw new IllegalArgumentException(
                    "rfc6265CookieProcessor.invalidDomain " + domain);
        }
    }


    private static void validatePath(String path) {
        char[] chars = path.toCharArray();

        for (char ch : chars) {
            if (ch < 0x20 || ch > 0x7E || ch == ';') {
                throw new IllegalArgumentException("rfc6265CookieProcessor.invalidPath " + path);
            }
        }
    }


    private static void validateAttribute(String name, String value) {
        if (!isToken(name)) {
            throw new IllegalArgumentException("rfc6265CookieProcessor.invalidAttributeName " + name);
        }

        char[] chars = value.toCharArray();
        for (char ch : chars) {
            if (ch < 0x20 || ch > 0x7E || ch == ';') {
                throw new IllegalArgumentException(
                        "rfc6265CookieProcessor.invalidAttributeValue " + name + " " + value);
            }
        }
    }

    /**
     * Is the provided String a token as per RFC 7230?
     * <br>
     * Note: token = 1 * tchar (RFC 7230)
     * <br>
     * Since a token requires at least 1 tchar, {@code null} and the empty
     * string ({@code ""}) are not considered to be valid tokens.
     *
     * @param string The string to test
     *
     * @return {@code true} if the string is a valid token, otherwise
     *         {@code false}
     */
    public static boolean isToken(String string) {
        if (string == null) {
            return false;
        }

        if (string.isEmpty()) {
            return false;
        }

        for (char c : string.toCharArray()) {
            if (!CookieHeaderParser.isToken(c)) {
                return false;
            }
        }

        return true;
    }

}
