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

package org.glassfish.grizzly.http.server.util;

import org.glassfish.grizzly.ThreadCache;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class DateTimeFormatters {
    private static final ThreadCache.CachedTypeIndex<DateTimeFormatters> CACHE_IDX = ThreadCache.obtainIndex(
            DateTimeFormatters.class, 1);

    public static DateTimeFormatters create() {
        final DateTimeFormatters formats = ThreadCache.takeFromCache(CACHE_IDX);
        if (formats != null) {
            return formats;
        }

        return new DateTimeFormatters();
    }

    private final DateTimeFormatter[] formatters;

    private DateTimeFormatters() {
        final ZoneId zoneId = ZoneId.of("GMT");
        formatters = new DateTimeFormatter[]{
                DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).withZone(zoneId),
                DateTimeFormatter.ofPattern("EEE, dd-MMM-yy HH:mm:ss zzz", Locale.US).withZone(zoneId),
                DateTimeFormatter.ofPattern("EEE MMMM d HH:mm:ss yyyy", Locale.US).withZone(zoneId)};
    }

    public DateTimeFormatter[] getFormats() {
        return formatters;
    }

    public void recycle() {
        ThreadCache.putToCache(CACHE_IDX, this);
    }
}
