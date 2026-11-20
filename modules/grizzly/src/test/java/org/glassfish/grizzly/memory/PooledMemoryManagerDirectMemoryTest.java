/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
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

package org.glassfish.grizzly.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * Direct buffer pools must be sized from the max direct memory, not from the max heap (issue #2201).
 * <p>
 * The limits are JVM options, so the pool is created in a child JVM with a large heap and a small direct memory limit.
 * Sized from the heap, preallocating the pool exceeds the direct memory limit and fails with an OutOfMemoryError.
 */
public class PooledMemoryManagerDirectMemoryTest {

    private static final String MAX_DIRECT_MEMORY = "16m";

    @Test
    public void testDirectPoolIsSizedFromMaxDirectMemory() throws Exception {
        final String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        final Process process = new ProcessBuilder(java, "-Xmx2g", "-XX:MaxDirectMemorySize=" + MAX_DIRECT_MEMORY, "-cp",
                System.getProperty("java.class.path"), PooledMemoryManagerDirectMemoryTest.class.getName()).redirectErrorStream(true).start();

        final boolean exited = process.waitFor(1, TimeUnit.MINUTES);
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!exited) {
            process.destroyForcibly();
        }
        assertTrue("child JVM did not finish: " + output, exited);
        assertEquals("creating a direct PooledMemoryManager failed: " + output, 0, process.exitValue());
    }

    /**
     * Runs in the child JVM: create a direct pool with the default heap usage percentage and fully preallocated buffers.
     */
    public static void main(String[] args) {
        final PooledMemoryManager mm = new PooledMemoryManager(true);
        long pooledBytes = 0;
        for (PooledMemoryManager.Pool pool : mm.getPools()) {
            pooledBytes += pool.size();
        }
        System.out.println("pooled direct memory: " + pooledBytes + " bytes");
    }
}
