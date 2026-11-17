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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class Http2ResetBudgetTest {

    private static final long MILLI = TimeUnit.MILLISECONDS.toNanos(1);

    @Test
    public void testBurstUpToTheLimitIsAllowed() {
        final Http2ResetBudget budget = new Http2ResetBudget(10, 1000);
        for (int i = 0; i < 10; i++) {
            assertTrue("reset " + (i + 1) + " is within the budget", budget.tryConsume(0));
        }
        assertFalse("the 11th reset in the same instant is over the budget", budget.tryConsume(0));
    }

    @Test
    public void testBudgetIsRestoredOverTheWindow() {
        final Http2ResetBudget budget = new Http2ResetBudget(10, 1000);
        for (int i = 0; i < 10; i++) {
            budget.tryConsume(0);
        }

        // 10 resets per 1000 ms, that is one per 100 ms
        assertFalse(budget.tryConsume(50 * MILLI));
        assertTrue(budget.tryConsume(150 * MILLI));
        assertFalse(budget.tryConsume(160 * MILLI));
    }

    @Test
    public void testIdleConnectionDoesNotSaveUpMoreThanTheLimit() {
        final Http2ResetBudget budget = new Http2ResetBudget(10, 1000);
        final long afterLongPause = 3_600_000 * MILLI;
        for (int i = 0; i < 10; i++) {
            assertTrue(budget.tryConsume(afterLongPause));
        }
        assertFalse(budget.tryConsume(afterLongPause));
    }

    @Test
    public void testSlowSteadyRateIsNeverLimited() {
        final Http2ResetBudget budget = new Http2ResetBudget(10, 1000);
        // one reset per 100 ms is exactly the allowed rate, for as long as it takes
        for (int i = 0; i < 1000; i++) {
            assertTrue("reset " + i, budget.tryConsume(i * 100 * MILLI));
        }
    }

    @Test
    public void testNegativeLimitSwitchesTheBudgetOff() {
        final Http2ResetBudget budget = new Http2ResetBudget(-1, 1000);
        assertFalse(budget.isEnabled());
        for (int i = 0; i < 10_000; i++) {
            assertTrue(budget.tryConsume(0));
        }
    }

    @Test
    public void testNonPositiveWindowSwitchesTheBudgetOff() {
        final Http2ResetBudget budget = new Http2ResetBudget(10, 0);
        assertFalse(budget.isEnabled());
        for (int i = 0; i < 100; i++) {
            assertTrue(budget.tryConsume(0));
        }
    }
}
