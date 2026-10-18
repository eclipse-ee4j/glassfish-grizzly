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

package org.glassfish.grizzly.http.server.io;

import static org.junit.Assert.assertFalse;

import java.lang.reflect.Field;

import org.junit.Test;

/** A recycled ServerInputBuffer forgets that its last request read asynchronously, so that the next one may be given back. */
public class ServerInputBufferRecycleTest {

    @Test
    public void recycleClearsTheAsyncReadFlag() throws Exception {
        final ServerInputBuffer buffer = new ServerInputBuffer();
        final Field flag = ServerInputBuffer.class.getDeclaredField("asyncReadStarted");
        flag.setAccessible(true);
        flag.setBoolean(buffer, true);
        // what initialize() would have set
        final Field content = org.glassfish.grizzly.http.io.InputBuffer.class.getDeclaredField("inputContentBuffer");
        content.setAccessible(true);
        content.set(buffer, org.glassfish.grizzly.memory.Buffers.EMPTY_BUFFER);
        buffer.recycle();
        assertFalse(buffer.isAsyncReadStarted());
    }
}
