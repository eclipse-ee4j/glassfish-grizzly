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

import java.util.concurrent.TimeUnit;

/**
 * The number of stream resets a connection may cause, as a token bucket: the connection may cause
 * {@code maxResets} of them at once, and after that gets {@code maxResets} per window more, continuously. See
 * {@link Http2Configuration#setMaxResetsPerWindow(int)}.
 * <p>
 * It is meant to be used from the connection's read thread. The methods are synchronized nevertheless, which costs next
 * to nothing when there is no contention, so that an occasional call from another thread can't corrupt the state.
 */
final class Http2ResetBudget {

    private final boolean enabled;
    private final double capacity;
    private final double tokensPerNano;

    private double tokens;
    private long lastRefillNanos;
    private boolean started;

    /**
     * @param maxResets the number of resets allowed per window, a negative value disables the budget
     * @param windowMillis the length of the window, a value which is not positive disables the budget
     */
    Http2ResetBudget(final int maxResets, final long windowMillis) {
        enabled = maxResets >= 0 && windowMillis > 0;
        capacity = Math.max(maxResets, 0);
        tokensPerNano = enabled ? capacity / TimeUnit.MILLISECONDS.toNanos(windowMillis) : 0;
        tokens = capacity;
    }

    boolean isEnabled() {
        return enabled;
    }

    /**
     * Records a reset.
     *
     * @param nowNanos the current {@link System#nanoTime()}
     * @return {@code false} if the connection has exceeded its budget with this reset, {@code true} otherwise
     */
    synchronized boolean tryConsume(final long nowNanos) {
        if (!enabled) {
            return true;
        }

        if (!started) {
            started = true;
        } else if (nowNanos > lastRefillNanos) {
            tokens = Math.min(capacity, tokens + (nowNanos - lastRefillNanos) * tokensPerNano);
        }
        lastRefillNanos = nowNanos;

        if (tokens < 1) {
            return false;
        }
        tokens--;
        return true;
    }
}
