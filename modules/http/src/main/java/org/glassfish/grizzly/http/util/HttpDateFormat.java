/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2025 Oracle and/or its affiliates. All rights reserved.
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

import org.glassfish.grizzly.utils.Charsets;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.glassfish.grizzly.http.util.HttpCodecUtils.toCheckedByteArray;

/**
 * Utility class to generate HTTP dates.
 * This class is based on {@link FastHttpDateFormat},
 * but uses java.time API instead of the old java.util.Date/Calendar and java.text.DateFormat/SimpleDateFormat API.
 *
 * @author Bongjae Chang
 */
public final class HttpDateFormat {

    private static final String ASCII_CHARSET_NAME = Charsets.ASCII_CHARSET.name();

    private static final int CACHE_SIZE = 1000;

    private static final ZoneId GMT_ZONE_ID = ZoneId.of("GMT");

    // Fri, 03 Oct 2025 04:47:00 GMT
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).withZone(GMT_ZONE_ID);

    private static final DateTimeFormatter[] FORMATTERS = new DateTimeFormatter[]{
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).withZone(GMT_ZONE_ID),
            DateTimeFormatter.ofPattern("EEE, dd-MMM-yy HH:mm:ss zzz", Locale.US).withZone(GMT_ZONE_ID),
            DateTimeFormatter.ofPattern("EEE MMMM d HH:mm:ss yyyy", Locale.US).withZone(GMT_ZONE_ID)};

    /**
     * Instant on which the currentDate object was generated.
     */
    private static volatile long nextGeneration;

    private static final AtomicBoolean isGeneratingNow = new AtomicBoolean();

    /**
     * Current formatted date as byte[].
     */
    private static byte[] currentDateBytes;

    /**
     * Current formatted date.
     */
    private static String cachedStringDate;
    private static volatile byte[] dateBytesForCachedStringDate;

    /**
     * Formatter cache.
     */
    private static final ConcurrentMap<Long, String> formatCache = new ConcurrentHashMap<>(CACHE_SIZE, 0.75f, 64);

    /**
     * Parser cache.
     */
    private static final ConcurrentMap<String, Long> parseCache = new ConcurrentHashMap<>(CACHE_SIZE, 0.75f, 64);

    // --------------------------------------------------------- Public Methods

    /**
     * Get the current date in HTTP format.
     */
    public static String getCurrentDate() {
        final byte[] currentDateBytesNow = getCurrentDateBytes();
        if (currentDateBytesNow != dateBytesForCachedStringDate) {
            try {
                cachedStringDate = new String(currentDateBytesNow, ASCII_CHARSET_NAME);
                dateBytesForCachedStringDate = currentDateBytesNow;
            } catch (UnsupportedEncodingException ignored) {
                // should never reach this line
            }
        }
        return cachedStringDate;
    }

    /**
     * Get the current date in HTTP format.
     */
    public static byte[] getCurrentDateBytes() {
        final long now = System.currentTimeMillis();
        final long diff = now - nextGeneration;

        // if the next generation time is not reached, or it is reached within 5 seconds and another thread is
        // generating the current date, return the cached value.
        if (diff < 0 || (diff < 5000 && isGeneratingNow.get())) {
            return currentDateBytes;
        }

        // otherwise, try to generate the current date.
        while (!isGeneratingNow.compareAndSet(false, true)) {
            Thread.onSpinWait();
        }
        if (now > nextGeneration) {
            currentDateBytes = toCheckedByteArray(new StringBuilder(FORMATTER.format(Instant.ofEpochMilli(now))));
            nextGeneration = now + 1000;
        }
        isGeneratingNow.set(false);
        return currentDateBytes;
    }

    /**
     * Get the HTTP format of the specified date.<br>
     * http spec only require second precision http://tools.ietf.org/html/rfc2616#page-20 <br>
     * therefore we don't use the millisecond precision , but second . truncation is done in the same way for second
     * precision in SimpleDateFormat:<br>
     * (999 millisec. = 0 sec.)
     *
     * @param value in milli-seconds
     * @param formatter the {@link DateTimeFormatter} used if cache value was not found
     */
    public static String formatDate(long value, DateTimeFormatter formatter) {
        // truncating to second precision
        // this way we optimally use the cache to only store needed http values
        value = value / 1000 * 1000;
        final Long longValue = value;
        String cachedDate = formatCache.get(longValue);
        if (cachedDate != null) {
            return cachedDate;
        }
        String newDate = Objects.requireNonNullElse(formatter, FORMATTER).format(Instant.ofEpochMilli(value));
        updateFormatCache(longValue, newDate);
        return newDate;
    }

    /**
     * Try to parse the given date as a HTTP date.
     */
    public static long parseDate(final String value, final DateTimeFormatter[] formatters) {
        Long cachedDate = parseCache.get(value);
        if (cachedDate != null) {
            return cachedDate;
        }
        long date;
        date = internalParseDate(value, Objects.requireNonNullElse(formatters, FORMATTERS));
        if (date != -1) {
            updateParseCache(value, date);
        }
        return date;
    }

    /**
     * Parse date with given formatters.
     */
    private static long internalParseDate(final String value, final DateTimeFormatter[] formatters) {
        for (final DateTimeFormatter formatter : formatters) {
            try {
                return ZonedDateTime.parse(value, formatter).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignore) {
            }
        }
        return -1;
    }

    /**
     * Update cache.
     */
    private static void updateFormatCache(Long key, String value) {
        if (value == null) {
            return;
        }
        if (formatCache.size() > CACHE_SIZE) {
            formatCache.clear();
        }
        formatCache.put(key, value);
    }

    /**
     * Update cache.
     */
    private static void updateParseCache(String key, Long value) {
        if (parseCache.size() > CACHE_SIZE) {
            parseCache.clear();
        }
        parseCache.put(key, value);
    }
}
