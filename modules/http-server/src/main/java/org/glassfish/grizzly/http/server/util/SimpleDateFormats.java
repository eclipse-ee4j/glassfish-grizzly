/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2012, 2020 Oracle and/or its affiliates. All rights reserved.
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

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import org.glassfish.grizzly.ThreadCache;

@Deprecated
public final class SimpleDateFormats {
    private static final ThreadCache.CachedTypeIndex<SimpleDateFormats> CACHE_IDX = ThreadCache.obtainIndex(SimpleDateFormats.class, 1);

    public static SimpleDateFormats create() {
        final SimpleDateFormats formats = ThreadCache.takeFromCache(CACHE_IDX);
        if (formats != null) {
            return formats;
        }

        return new SimpleDateFormats();
    }

    private final SimpleDateFormat[] f;

    public SimpleDateFormats() {
        f = new SimpleDateFormat[3];
        f[0] = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        f[1] = new SimpleDateFormat("EEEEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US);
        f[2] = new SimpleDateFormat("EEE MMMM d HH:mm:ss yyyy", Locale.US);

        f[0].setTimeZone(TimeZone.getTimeZone("GMT"));

        f[1].setTimeZone(TimeZone.getTimeZone("GMT"));

        f[2].setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    public SimpleDateFormat[] getFormats() {
        return f;
    }

    public void recycle() {
        ThreadCache.putToCache(CACHE_IDX, this);
    }
}
