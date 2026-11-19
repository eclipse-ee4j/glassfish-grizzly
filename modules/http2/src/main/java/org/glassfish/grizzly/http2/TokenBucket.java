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

/**
 * A token bucket used to bound how often a peer may trigger a given kind of work on one {@link Http2Session}. The bucket
 * starts full with {@code burst} tokens and is refilled at {@code refillPerSecond} tokens per second, up to
 * {@code burst}. Each event takes one token; an event that finds the bucket empty exceeds the budget.
 * <p>
 * A bucket with a non-positive burst is unlimited: {@link #tryAcquire()} always succeeds. A bucket with a non-positive
 * refill rate is never refilled: it allows {@code burst} events in total.
 */
final class TokenBucket {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final boolean unlimited;
    private final double burst;
    private final double refillPerNano;

    // guarded by this
    private double tokens;
    private long lastRefillNanos;

    TokenBucket(final int burst, final int refillPerSecond) {
        this(burst, refillPerSecond, System.nanoTime());
    }

    TokenBucket(final int burst, final int refillPerSecond, final long nowNanos) {
        this.unlimited = burst <= 0;
        this.burst = burst;
        this.refillPerNano = refillPerSecond > 0 ? (double) refillPerSecond / NANOS_PER_SECOND : 0;
        this.tokens = burst;
        this.lastRefillNanos = nowNanos;
    }

    /**
     * Takes one token.
     *
     * @return {@code false} if the bucket was empty, i.e. the event exceeds the budget.
     */
    boolean tryAcquire() {
        return unlimited || tryAcquire(System.nanoTime());
    }

    synchronized boolean tryAcquire(final long nowNanos) {
        if (unlimited) {
            return true;
        }

        final long elapsed = nowNanos - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(burst, tokens + elapsed * refillPerNano);
            lastRefillNanos = nowNanos;
        }

        if (tokens < 1.0) {
            return false;
        }

        tokens -= 1.0;
        return true;
    }
}
