/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly.http.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

/**
 * Checks the SWAR helpers lane by lane against the scalar definitions they replace.
 */
public class SwarTest {

    @Test
    public void toLowerCaseMatchesScalarForEveryByteInEveryLane() {
        for (int lane = 0; lane < 8; lane++) {
            for (int b = 0; b < 256; b++) {
                final byte[] bytes = "xY0-Z@[`".getBytes(StandardCharsets.ISO_8859_1);
                bytes[lane] = (byte) b;
                final long lowered = Swar.toLowerCase(Swar.getLong(bytes, 0));
                for (int i = 0; i < 8; i++) {
                    assertEquals("lane " + lane + " byte " + b, (byte) Ascii.toLower(bytes[i]), (byte) (lowered >>> (i * 8)));
                }
            }
        }
    }

    @Test
    public void indexOfNonVisibleMatchesScalarForEveryByteInEveryLane() {
        for (int len = 0; len <= 19; len++) {
            for (int pos = 0; pos < len; pos++) {
                for (int b = 0; b < 256; b++) {
                    final byte[] bytes = new byte[len + 3];
                    Arrays.fill(bytes, (byte) 'a');
                    bytes[1 + pos] = (byte) b;
                    final boolean visible = b >= 0x20 && b < 0x7F;
                    assertEquals("len " + len + " pos " + pos + " byte " + b, visible ? 1 + len : 1 + pos,
                            Swar.indexOfNonVisible(bytes, 1, 1 + len));
                }
            }
        }
    }

    @Test
    public void indexOfUriDelimiterMatchesScalarForEveryByteInEveryLane() {
        for (int len = 0; len <= 19; len++) {
            for (int pos = 0; pos < len; pos++) {
                for (int b = 0; b < 256; b++) {
                    final byte[] bytes = new byte[len + 3];
                    Arrays.fill(bytes, (byte) '/');
                    bytes[1 + pos] = (byte) b;
                    final boolean delimiter = b <= 0x20 || b == '?';
                    assertEquals("len " + len + " pos " + pos + " byte " + b, delimiter ? 1 + pos : 1 + len,
                            Swar.indexOfUriDelimiter(bytes, 1, 1 + len));
                }
            }
        }
    }

    @Test
    public void skipAlphaNumDashMatchesScalarForEveryByteInEveryLane() {
        for (final boolean lower : new boolean[] { true, false }) {
            for (int len = 0; len <= 19; len++) {
                for (int pos = 0; pos < len; pos++) {
                    for (int b = 0; b < 256; b++) {
                        final byte[] bytes = new byte[len + 3];
                        for (int i = 0; i < bytes.length; i++) {
                            bytes[i] = (byte) "Ab-9zZ0x".charAt(i % 8);
                        }
                        bytes[1 + pos] = (byte) b;
                        final byte[] expected = bytes.clone();
                        int stop = 1;
                        while (stop < 1 + len && (Ascii.isAlpha(expected[stop]) || Ascii.isDigit(expected[stop]) || expected[stop] == '-')) {
                            stop++;
                        }

                        final int actual = Swar.skipAlphaNumDash(bytes, 1, 1 + len, lower);
                        final String msg = "lower " + lower + " len " + len + " pos " + pos + " byte " + b;
                        // whole words only: stops at the first non-matching byte or before a partial tail word
                        assertEquals(msg, Math.min(stop, 1 + (len / 8) * 8), actual);
                        for (int i = 0; i < bytes.length; i++) {
                            final byte want = lower && i >= 1 && i < actual ? (byte) Ascii.toLower(expected[i]) : expected[i];
                            assertEquals(msg + " at " + i, want, bytes[i]);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void indexOfNonVisibleReturnsFirstOfSeveral() {
        final byte[] bytes = "abcdefgh\r\n\tij\u007fkl".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(8, Swar.indexOfNonVisible(bytes, 0, bytes.length));
        assertEquals(10, Swar.indexOfNonVisible(bytes, 10, bytes.length));
        assertEquals(13, Swar.indexOfNonVisible(bytes, 11, bytes.length));
        assertEquals(5, Swar.indexOfNonVisible(bytes, 5, 5));
    }

    @Test
    public void equalsIgnoreCaseMatchesScalar() {
        final Random rnd = new Random(42);
        final byte[] alphabet = "aAbBzZ09-_:@[`{ \tÀà".getBytes(StandardCharsets.ISO_8859_1);
        for (int iter = 0; iter < 20000; iter++) {
            final int len = rnd.nextInt(24);
            final byte[] a = new byte[len + 2];
            final byte[] b = new byte[len + 5];
            for (int i = 0; i < len; i++) {
                a[1 + i] = alphabet[rnd.nextInt(alphabet.length)];
                // mostly case variants of a, sometimes a different byte
                b[4 + i] = rnd.nextInt(8) == 0 ? alphabet[rnd.nextInt(alphabet.length)]
                        : (byte) (rnd.nextBoolean() ? Ascii.toUpper(a[1 + i]) : Ascii.toLower(a[1 + i]));
            }
            boolean expected = true;
            boolean expectedLower = true;
            for (int i = 0; i < len; i++) {
                expected &= Ascii.toLower(a[1 + i]) == Ascii.toLower(b[4 + i]);
            }
            assertEquals(expected, Swar.equalsIgnoreCase(a, 1, b, 4, len));

            final byte[] lower = new byte[len];
            for (int i = 0; i < len; i++) {
                lower[i] = (byte) Ascii.toLower(b[4 + i]);
                expectedLower &= Ascii.toLower(a[1 + i]) == lower[i];
            }
            assertEquals(expectedLower, Swar.equalsIgnoreCaseLowerCase(a, 1, lower, len));
        }
    }

    @Test
    public void equalsIgnoreCaseLowerCaseNeverMatchesNonAscii() {
        final byte[] value = "content-éncoding".getBytes(StandardCharsets.ISO_8859_1);
        assertFalse(Swar.equalsIgnoreCaseLowerCase(value, 0, value.clone(), value.length));
        assertFalse(ByteChunk.equalsIgnoreCaseLowerCase(value, 0, value.length, value.clone()));
    }
}
