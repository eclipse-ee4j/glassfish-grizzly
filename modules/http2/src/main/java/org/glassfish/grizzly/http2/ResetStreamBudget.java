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

package org.glassfish.grizzly.http2;

import java.util.concurrent.TimeUnit;

final class ResetStreamBudget {
    // Track fractional allowances with integer arithmetic. At one reset per second,
    // each elapsed nanosecond restores one unit.
    private static final long UNITS_PER_RESET = TimeUnit.SECONDS.toNanos(1);

    private final long capacity;
    private final int refillPerSecond;
    private long remaining;
    private long lastRefillNanos;
    private volatile boolean exceeded;

    ResetStreamBudget(final int burstSize, final int refillPerSecond, final long nowNanos) {
        capacity = burstSize * UNITS_PER_RESET;
        remaining = capacity;
        this.refillPerSecond = refillPerSecond;
        lastRefillNanos = nowNanos;
    }

    synchronized boolean tryConsume(final long nowNanos) {
        if (exceeded) {
            return false;
        }

        final long elapsed = nowNanos - lastRefillNanos;
        if (elapsed > 0 && refillPerSecond > 0) {
            final long missing = capacity - remaining;
            // Cap the refill before multiplying, even if the connection has been idle for a long time.
            remaining += elapsed > missing / refillPerSecond ? missing : elapsed * refillPerSecond;
            lastRefillNanos = nowNanos;
        }

        if (remaining < UNITS_PER_RESET) {
            exceeded = true;
            return false;
        }

        remaining -= UNITS_PER_RESET;
        return true;
    }

    boolean isExceeded() {
        return exceeded;
    }
}
