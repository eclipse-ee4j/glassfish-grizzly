/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2010, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.http.util.CookieUtils;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.utils.Charsets;
import org.glassfish.grizzly.utils.Pair;

import junit.framework.TestCase;

/**
 * Cookie serialization/parsing test
 *
 * @author Alexey Stashok
 */
public class CookiesTest extends TestCase {

    private static Pair[] createClientTestCaseCookie() {
        return new Pair[] {
                new Pair<>("CUSTOMER=WILE_E_COYOTE",
                        new Checker[] { new Checker(0, "CUSTOMER", CheckValue.NAME), new Checker(0, "WILE_E_COYOTE", CheckValue.VALUE),
                                new Checker(0, 0, CheckValue.VERSION) }),
                new Pair<>("CUSTOMER=WILE_E_COYOTE; PART_NUMBER=ROCKET_LAUNCHER_0001",
                        new Checker[] { new Checker(0, "CUSTOMER", CheckValue.NAME), new Checker(0, "WILE_E_COYOTE", CheckValue.VALUE),
                                new Checker(0, 0, CheckValue.VERSION), new Checker(1, "PART_NUMBER", CheckValue.NAME),
                                new Checker(1, "ROCKET_LAUNCHER_0001", CheckValue.VALUE), new Checker(1, 0, CheckValue.VERSION) }),
                new Pair<>("$Version=\"1\"; Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"",
                        new Checker[] { new Checker(0, "Customer", CheckValue.NAME), new Checker(0, "WILE_E_COYOTE", CheckValue.VALUE),
                                new Checker(0, "/acme", CheckValue.PATH), new Checker(0, 1, CheckValue.VERSION) }),
                new Pair<>(
                        "$Version=\"1\"; Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"; $Domain=\"mydomain.com\"; Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"",
                        new Checker[] { new Checker(0, "Customer", CheckValue.NAME), new Checker(0, "WILE_E_COYOTE", CheckValue.VALUE),
                                new Checker(0, "/acme", CheckValue.PATH), new Checker(0, "mydomain.com", CheckValue.DOMAIN),
                                new Checker(0, 1, CheckValue.VERSION), new Checker(1, "Part_Number", CheckValue.NAME),
                                new Checker(1, "Rocket_Launcher_0001", CheckValue.VALUE), new Checker(1, "/acme", CheckValue.PATH),
                                new Checker(1, 1, CheckValue.VERSION) }),
                new Pair<>(
                        "$Version=\"1\"; Part_Number=\"Riding_Rocket_0023\"; $Path=\"/acme/ammo\"; Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"",
                        new Checker[] { new Checker(0, "Part_Number", CheckValue.NAME), new Checker(0, "Riding_Rocket_0023", CheckValue.VALUE),
                                new Checker(0, "/acme/ammo", CheckValue.PATH), new Checker(0, 1, CheckValue.VERSION),
                                new Checker(1, "Part_Number", CheckValue.NAME), new Checker(1, "Rocket_Launcher_0001", CheckValue.VALUE),
                                new Checker(1, "/acme", CheckValue.PATH), new Checker(1, 1, CheckValue.VERSION) }) };
    }

    // ex. Wednesday, 09-Nov-99 23:12:40 GMT
    private static final String expiresStr =
            CookieUtils.OLD_COOKIE_FORMAT.format(Instant.now().plusMillis(1000 * 60 * 60));

    private static Pair[] createServerTestCaseCookie() {
        return new Pair[] {
                new Pair<>("CUSTOMER=WILE_E_COYOTE; path=/; expires=" + expiresStr,
                        new Checker[] { new Checker(0, "CUSTOMER", CheckValue.NAME), new Checker(0, "WILE_E_COYOTE", CheckValue.VALUE),
                                new Checker(0, expire2MaxAge(expiresStr), CheckValue.MAX_AGE), new Checker(0, 0, CheckValue.VERSION) }),
                new Pair<>("Part_Number=\"Rocket_Launcher_0001\"; Version=\"1\"; Path=\"/acme\"",
                        new Checker[] { new Checker(0, "Part_Number", CheckValue.NAME), new Checker(0, "Rocket_Launcher_0001", CheckValue.VALUE),
                                new Checker(0, "/acme", CheckValue.PATH), new Checker(0, 1, CheckValue.VERSION) }),
                new Pair<>(
                        "Part_Number=\"Rocket_Launcher_0001\"; Version=\"1\"; Path=\"/acme\", Customer=\"WILE_E_COYOTE\"; Version=\"1\"; Path=\"/acme/path\"",
                        new Checker[] { new Checker(0, "Part_Number", CheckValue.NAME), new Checker(0, "Rocket_Launcher_0001", CheckValue.VALUE),
                                new Checker(0, "/acme", CheckValue.PATH), new Checker(0, 1, CheckValue.VERSION), new Checker(1, "Customer", CheckValue.NAME),
                                new Checker(1, "WILE_E_COYOTE", CheckValue.VALUE), new Checker(1, "/acme/path", CheckValue.PATH),
                                new Checker(1, 1, CheckValue.VERSION) }), };
    }

    @SuppressWarnings({ "unchecked" })
    public void testClientCookie() {
        for (Pair<String, Checker[]> testCase : createClientTestCaseCookie()) {
            final String cookieString = testCase.getFirst();
            final Checker[] checkers = testCase.getSecond();

            Cookie[] cookies = CookiesBuilder.client().parse(cookieString).build().get();
            validateClientCookies(testCase, cookies, checkers);

            ByteBuffer b = ByteBuffer.allocateDirect(cookieString.length());
            b.put(cookieString.getBytes(Charsets.ASCII_CHARSET));
            b.flip();
            Buffer nonArrayBackedBuffer = Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, b);
            cookies = CookiesBuilder.client().parse(nonArrayBackedBuffer).build().get();
            validateClientCookies(testCase, cookies, checkers);

            Buffer byteBasedBuffer = Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, cookieString.getBytes(Charsets.ASCII_CHARSET));
            cookies = CookiesBuilder.client().parse(byteBasedBuffer).build().get();
            validateClientCookies(testCase, cookies, checkers);
        }
    }

    @SuppressWarnings({ "unchecked" })
    public void testServerCookie() {
        for (Pair<String, Checker[]> testCase : createServerTestCaseCookie()) {
            final String cookieString = testCase.getFirst();
            final Checker[] checkers = testCase.getSecond();

            Cookie[] cookies = CookiesBuilder.server().parse(cookieString).build().get();
            validateServerCookies(testCase, cookies, checkers);

            ByteBuffer b = ByteBuffer.allocateDirect(cookieString.length());
            b.put(cookieString.getBytes(Charsets.ASCII_CHARSET));
            b.flip();
            Buffer nonArrayBackedBuffer = Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, b);
            cookies = CookiesBuilder.server().parse(nonArrayBackedBuffer).build().get();
            validateServerCookies(testCase, cookies, checkers);

            Buffer byteBasedBuffer = Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, cookieString.getBytes(Charsets.ASCII_CHARSET));
            cookies = CookiesBuilder.server().parse(byteBasedBuffer).build().get();
            validateServerCookies(testCase, cookies, checkers);
        }
    }

    private void validateServerCookies(Pair<String, Checker[]> testCase, Cookie[] cookies, Checker[] checkers) {
        for (Checker checker : checkers) {
            final Cookie cookie = cookies[checker.getCookieIdx()];
            assertTrue("Mismatch. Checker=" + checker.getCheckValue() + " expected=" + checker.getExpected() + " value=" + checker.getCheckValue().get(cookie),
                    checker.check(cookie));
        }

        for (Cookie cookie : cookies) {
            final String serializedString = cookie.asServerCookieString();
            Cookie[] parsedCookies = CookiesBuilder.server().parse(serializedString).build().get();
            assertEquals(testCase.toString(), 1, parsedCookies.length);

            Cookie parsedCookie = parsedCookies[0];

            assertTrue(equalsCookies(cookie, parsedCookie));

            Buffer serializedBuffer = cookie.asServerCookieBuffer();
            parsedCookies = CookiesBuilder.server().parse(serializedBuffer).build().get();
            assertEquals(testCase.toString(), 1, parsedCookies.length);

            parsedCookie = parsedCookies[0];

            assertTrue(testCase.toString(), equalsCookies(cookie, parsedCookie));
        }
    }

    private void validateClientCookies(Pair<String, Checker[]> testCase, Cookie[] cookies, Checker[] checkers) {
        for (Checker checker : checkers) {
            final Cookie cookie = cookies[checker.getCookieIdx()];
            assertTrue("Mismatch. Checker=" + checker.getCheckValue() + " expected=" + checker.getExpected() + " value=" + checker.getCheckValue().get(cookie),
                    checker.check(cookie));
        }

        for (Cookie cookie : cookies) {
            final String serializedString = cookie.asClientCookieString();
            Cookie[] parsedCookies = CookiesBuilder.client().parse(serializedString).build().get();
            assertEquals(testCase.toString(), 1, parsedCookies.length);

            Cookie parsedCookie = parsedCookies[0];

            assertTrue(testCase.toString(), equalsCookies(cookie, parsedCookie));

            Buffer serializedBuffer = cookie.asClientCookieBuffer();
            parsedCookies = CookiesBuilder.client().parse(serializedBuffer).build().get();
            assertEquals(1, parsedCookies.length);

            parsedCookie = parsedCookies[0];

            assertTrue(testCase.toString(), equalsCookies(cookie, parsedCookie));
        }
    }

    private boolean equalsCookies(Cookie expected, Cookie got) {
        return equalsObjects("Name", expected.getName(), got.getName()) || equalsObjects("Value", expected.getValue(), got.getValue())
                || equalsObjects("Comment", expected.getComment(), got.getComment()) || equalsObjects("Domain", expected.getDomain(), got.getDomain())
                || equalsObjects("Max-Age", expected.getMaxAge(), got.getMaxAge()) || equalsObjects("Path", expected.getPath(), got.getPath())
                || equalsObjects("Version", expected.getVersion(), got.getVersion()) || equalsObjects("HttpOnly", expected.isHttpOnly(), got.isHttpOnly())
                || equalsObjects("Secure", expected.isSecure(), got.isSecure());
    }

    private boolean equalsObjects(String cmpValue, Object o1, Object o2) {
        final boolean result = o1 == null && o2 == null || o1 != null && o1.equals(o2) || o2 != null && o2.equals(o1);
        if (!result) {
            fail("Mismatch property=" + cmpValue + " expected=" + o1 + " got=" + o2);
        }

        return true;
    }

    public static class Checker {
        private final int cookieIdx;
        private final Object expected;
        private final CheckValue checkValue;

        public Checker(int cookieIdx, Object expected, CheckValue checkValue) {
            this.cookieIdx = cookieIdx;
            this.expected = expected;
            this.checkValue = checkValue;
        }

        public int getCookieIdx() {
            return cookieIdx;
        }

        public Object getExpected() {
            return expected;
        }

        public CheckValue getCheckValue() {
            return checkValue;
        }

        public boolean check(Cookie cookie) {
            return checkValue.check(expected, cookie);
        }
    }

    public enum CheckValue {
        NAME() {
            @Override
            public Object get(Cookie cookie) {
                return cookie.getName();
            }
        },
        VALUE() {
            @Override
            public Object get(Cookie cookie) {
                return cookie.getValue();
            }
        },
        PATH() {
            @Override
            public Object get(Cookie cookie) {
                return cookie.getPath();
            }
        },
        DOMAIN() {
            @Override
            public Object get(Cookie cookie) {
                return cookie.getDomain();
            }
        },
        COMMENT() {
            @Override
            public Object get(Cookie cookie) {
                return cookie.getComment();
            }
        },
        VERSION() {
            @Override
            public Object get(Cookie cookie) {
                return cookie.getVersion();
            }
        },
        HTTP_ONLY() {
            @Override
            public Object get(Cookie cookie) {
                return cookie.isHttpOnly();
            }
        },
        SECURE() {
            @Override
            public Object get(Cookie cookie) {
                return cookie.isSecure();
            }
        },
        MAX_AGE() {
            @Override
            public Object get(Cookie cookie) {
                return cookie.getMaxAge();
            }

            @Override
            public boolean check(Object pattern, Cookie cookie) {
                // In the tests we allow max-age to have 15sec precision.
                return Math.abs((Integer) pattern - cookie.getMaxAge()) < 15;
            }
        };

        public abstract Object get(Cookie cookie);

        public boolean check(Object pattern, Cookie cookie) {
            return pattern.equals(get(cookie));
        }
    }

    private static int expire2MaxAge(String expire) {
        try {
            return (int) Duration.between(Instant.now(),
                                          ZonedDateTime.parse(expire, CookieUtils.OLD_COOKIE_FORMAT).toInstant())
                                 .toSeconds();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Illegal expire value: " + expire);
        }
    }
}
