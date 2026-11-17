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

package org.glassfish.grizzly.http2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Http2ConfigurationTest {

    @Test
    public void testRapidResetProtectionIsOnByDefault() {
        final Http2Configuration configuration = Http2Configuration.builder().build();

        assertEquals(200, configuration.getMaxResetsPerWindow());
        assertEquals(30000, configuration.getResetWindowMillis());
        assertTrue(configuration.isHoldSlotUntilProcessingComplete());
    }

    @Test
    public void testRapidResetProtectionIsConfigurable() {
        final Http2Configuration configuration = Http2Configuration.builder().maxResetsPerWindow(7).resetWindowMillis(1234)
                .holdSlotUntilProcessingComplete(false).build();

        assertEquals(7, configuration.getMaxResetsPerWindow());
        assertEquals(1234, configuration.getResetWindowMillis());
        assertFalse(configuration.isHoldSlotUntilProcessingComplete());

        configuration.setMaxResetsPerWindow(-1);
        configuration.setResetWindowMillis(5);
        configuration.setHoldSlotUntilProcessingComplete(true);

        assertEquals(-1, configuration.getMaxResetsPerWindow());
        assertEquals(5, configuration.getResetWindowMillis());
        assertTrue(configuration.isHoldSlotUntilProcessingComplete());
    }
}
