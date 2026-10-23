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

package org.glassfish.grizzly;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/** A {@link Context} recycled on another thread goes back to the cache of the thread that created it. */
public class ContextReuseTest {

    @Test
    public void aContextRecycledElsewhereIsReusedByItsThread() throws Exception {
        final ExecutorService creator = Executors.newSingleThreadExecutor();
        final ExecutorService recycler = Executors.newSingleThreadExecutor();
        try {
            final Context context = creator.submit(() -> Context.create(null)).get(10, TimeUnit.SECONDS);
            recycler.submit(context::recycle).get(10, TimeUnit.SECONDS);
            assertNull("owner kept after recycle", cacheOwner(context));
            assertSame(context, creator.submit(() -> Context.create(null)).get(10, TimeUnit.SECONDS));
        } finally {
            creator.shutdownNow();
            recycler.shutdownNow();
        }
    }

    private static Object cacheOwner(final Context context) throws ReflectiveOperationException {
        final Field field = Context.class.getDeclaredField("cacheOwner");
        field.setAccessible(true);
        return field.get(context);
    }
}
