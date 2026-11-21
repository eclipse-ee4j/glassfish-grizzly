/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.grizzly.http2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ResetStreamBudgetTest {

    @Test
    public void testRefillKeepsFractionalAllowancesAndExhaustionIsSticky() {
        final ResetStreamBudget budget = new ResetStreamBudget(2, 3, 0L);

        assertTrue(budget.tryConsume(0L));
        assertTrue(budget.tryConsume(200_000_000L));
        assertTrue(budget.tryConsume(400_000_000L));
        assertFalse(budget.isExceeded());
        assertFalse(budget.tryConsume(666_666_666L));
        assertTrue(budget.isExceeded());
        assertFalse(budget.tryConsume(2_000_000_000L));
    }

    @Test
    public void testRefillCapsAtBurstWithoutOverflow() {
        final ResetStreamBudget budget = new ResetStreamBudget(2, Integer.MAX_VALUE, 0L);
        assertTrue(budget.tryConsume(0L));

        assertTrue(budget.tryConsume(Long.MAX_VALUE));
        assertTrue(budget.tryConsume(Long.MAX_VALUE));
        assertFalse(budget.tryConsume(Long.MAX_VALUE));
    }

    @Test
    public void testZeroRefillRateNeverRestoresAllowances() {
        final ResetStreamBudget budget = new ResetStreamBudget(1, 0, 0L);
        assertTrue(budget.tryConsume(0L));
        assertFalse(budget.tryConsume(Long.MAX_VALUE));
    }

    @Test
    public void testConfigurationValidatesResetStreamRateLimit() {
        assertThrows(IllegalArgumentException.class, () -> Http2Configuration.builder().resetStreamRateLimit(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> Http2Configuration.builder().resetStreamRateLimit(1, -1));

        final Http2Configuration configuration = Http2Configuration.builder().resetStreamRateLimit(0, 5).build();
        assertEquals(0, configuration.getResetStreamBurst());
        assertEquals(5, configuration.getResetStreamsPerSecond());
    }
}
