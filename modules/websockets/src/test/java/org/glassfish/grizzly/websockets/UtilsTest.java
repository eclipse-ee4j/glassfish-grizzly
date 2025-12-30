/*
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

package org.glassfish.grizzly.websockets;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class UtilsTest {

    @Test
    public void testToLong() {
        // Basic case
        {
            byte[] bytes = new byte[]{1, 2, 3};
            long value = Utils.toLong(bytes, 0, bytes.length);
            assertEquals(66051L, value);
        }

        // Null case
        {
            long value = Utils.toLong(null, 0, 1);
            assertEquals(0L, value);
        }
    }

    @Test
    public void testToString() {
        // Basic case
        {
            byte[] bytes = new byte[]{1, 2};
            List<String> list = Utils.toString(bytes);
            assertEquals(2, list.size());
            assertEquals("1", list.get(0));
            assertEquals("2", list.get(1));
        }

        // Null case
        {
            List<String> list = Utils.toString(null);
            assertEquals(0, list.size());
        }
    }

    @Test
    public void testToStringCustom() {
        // Basic case
        {
            byte[] bytes = new byte[]{1, 2};
            List<String> list = Utils.toString(bytes, 0, bytes.length);
            assertEquals(2, list.size());
            assertEquals("1", list.get(0));
            assertEquals("2", list.get(1));
        }

        // Null case
        {
            List<String> list = Utils.toString(null, 0, 1);
            assertEquals(0, list.size());
        }
    }
}