/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */

package org.glassfish.grizzly.http.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * SIMD-within-a-register helpers for scanning ASCII protocol bytes eight at a time.
 * <p>
 * Words are read little-endian through a byte-array view {@link VarHandle} (unaligned access is allowed for plain
 * get), so the lowest-index byte is the least significant lane and the first matching byte is found with
 * {@link Long#numberOfTrailingZeros(long)}. Every lane computation keeps the lane below 0x100, so no carry or
 * borrow crosses lanes and all masks are exact per byte.
 */
public final class Swar {

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private static final long HI = 0x8080808080808080L;
    private static final long LO7 = 0x7F7F7F7F7F7F7F7FL;

    private Swar() {
    }

    /**
     * Reads eight bytes starting at {@code index} as a little-endian long.
     */
    public static long getLong(final byte[] array, final int index) {
        return (long) LONGS.get(array, index);
    }

    /**
     * Sets the 0x80 bit of each lane holding an ASCII upper-case letter ('A'..'Z').
     */
    static long upperCaseMask(final long word) {
        final long t = word & LO7;
        // t + 0x25 overflows into bit 7 iff t >= 'Z' + 1; after dropping that bit, adding 0x1A reaches bit 7 iff
        // t >= 'A' and t <= 'Z'. Lanes with the top bit set in the input are excluded.
        return ((((t + 0x2525252525252525L) & LO7) + 0x1A1A1A1A1A1A1A1AL) & ~word) & HI;
    }

    /**
     * ASCII lower-casing of eight bytes at once (bytes outside 'A'..'Z' are untouched).
     */
    public static long toLowerCase(final long word) {
        return word | (upperCaseMask(word) >>> 2);
    }

    /**
     * Sets the 0x80 bit of each lane that is not a "visible" header-value byte for strict RFC 9110 parsing: a control
     * (&lt; 0x20, which includes HT, CR and LF), DEL (0x7F) or a byte with the top bit set.
     */
    static long nonVisibleMask(final long word) {
        final long t = word & LO7;
        final long lt20 = ~(t + 0x6060606060606060L) & HI; // t + 0x60 reaches bit 7 iff t >= 0x20
        final long del = (t + 0x0101010101010101L) & HI;   // t + 1 reaches bit 7 iff t == 0x7F
        return word & HI | lt20 | del;
    }

    /**
     * Skips the leading run of bytes in [{@code from}, {@code to}) that are ASCII letters, digits or {@code '-'} (the
     * token characters header names are made of), lower-casing the letters in place when {@code toLowerCase} is set.
     * Only whole words are processed; the caller handles the returned position and the remaining tail byte by byte.
     *
     * @return the index of the first byte not processed
     */
    public static int skipAlphaNumDash(final byte[] array, int from, final int to, final boolean toLowerCase) {
        while (to - from >= Long.BYTES) {
            final long word = getLong(array, from);
            final long lower = toLowerCase(word);
            final long t = lower & LO7;
            final long letter = (t + 0x1F1F1F1F1F1F1F1FL) & ~(t + 0x0505050505050505L);  // 'a' <= t <= 'z'
            final long digit = (t + 0x5050505050505050L) & ~(t + 0x4646464646464646L);   // '0' <= t <= '9'
            final long dash = ~((t ^ 0x2D2D2D2D2D2D2D2DL) + LO7);                        // t == '-'
            final long other = ~((letter | digit | dash) & ~word) & HI;
            final int n = Long.numberOfTrailingZeros(other) >>> 3; // 8 if every lane matched
            if (toLowerCase && n != 0 && lower != word) {
                final long keep = n == Long.BYTES ? -1L : (1L << (n << 3)) - 1;
                LONGS.set(array, from, lower & keep | word & ~keep);
            }
            from += n;
            if (n != Long.BYTES) {
                break;
            }
        }
        return from;
    }

    /**
     * Returns the index of the first byte in [{@code from}, {@code to}) that is &lt;= 0x20 (SP, HT, CR, LF and the
     * other controls) or {@code '?'}, or {@code to} if there is none: the bytes that end the request-URI or its path.
     */
    public static int indexOfUriDelimiter(final byte[] array, int from, final int to) {
        while (to - from >= Long.BYTES) {
            final long word = getLong(array, from);
            final long t = word & LO7;
            final long le20 = ~(t + 0x5F5F5F5F5F5F5F5FL) & ~word & HI;            // t + 0x5F reaches bit 7 iff t > 0x20
            final long q = t ^ 0x3F3F3F3F3F3F3F3FL;                               // zero lane iff t == '?'
            final long question = ~(q + LO7) & ~word & HI;                        // q + 0x7F reaches bit 7 iff q != 0
            final long mask = le20 | question;
            if (mask != 0) {
                return from + (Long.numberOfTrailingZeros(mask) >>> 3);
            }
            from += Long.BYTES;
        }
        for (; from < to; from++) {
            final int b = array[from];
            if (b >= 0 && b <= 0x20 || b == '?') {
                return from;
            }
        }
        return to;
    }

    /**
     * Returns the index of the first byte in [{@code from}, {@code to}) that is a control, DEL or non-ASCII byte, or
     * {@code to} if all bytes are visible ASCII or space.
     */
    public static int indexOfNonVisible(final byte[] array, int from, final int to) {
        while (to - from >= Long.BYTES) {
            final long mask = nonVisibleMask(getLong(array, from));
            if (mask != 0) {
                return from + (Long.numberOfTrailingZeros(mask) >>> 3);
            }
            from += Long.BYTES;
        }
        for (; from < to; from++) {
            final int b = array[from];
            if (b < 0x20 || b == 0x7F) { // negative (non-ASCII) bytes are < 0x20 here as well
                return from;
            }
        }
        return to;
    }

    /**
     * ASCII case-insensitive comparison of two byte ranges of the same length.
     */
    public static boolean equalsIgnoreCase(final byte[] a, int aOffs, final byte[] b, int bOffs, final int len) {
        final int aEnd = aOffs + len;
        while (aEnd - aOffs >= Long.BYTES) {
            final long wa = getLong(a, aOffs);
            final long wb = getLong(b, bOffs);
            if (wa != wb && toLowerCase(wa) != toLowerCase(wb)) {
                return false;
            }
            aOffs += Long.BYTES;
            bOffs += Long.BYTES;
        }
        while (aOffs < aEnd) {
            if (Ascii.toLower(a[aOffs++]) != Ascii.toLower(b[bOffs++])) {
                return false;
            }
        }
        return true;
    }

    /**
     * ASCII case-insensitive comparison of a byte range with an array that is already lower-case. As with
     * {@code Ascii.toLower(a[i]) == lowerCase[i]}, a non-ASCII byte in {@code a} never matches.
     */
    public static boolean equalsIgnoreCaseLowerCase(final byte[] a, int aOffs, final byte[] lowerCase, final int len) {
        int i = 0;
        for (; len - i >= Long.BYTES; i += Long.BYTES) {
            final long wa = getLong(a, aOffs + i);
            if ((wa & HI) != 0 || toLowerCase(wa) != getLong(lowerCase, i)) {
                return false;
            }
        }
        for (; i < len; i++) {
            if (Ascii.toLower(a[aOffs + i]) != lowerCase[i]) {
                return false;
            }
        }
        return true;
    }
}
