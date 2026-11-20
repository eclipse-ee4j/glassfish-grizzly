/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.  All rights reserved.
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

public class TokenBucketTest {

    private static final long START = 123_456_789L;

    @Test
    public void allowsBurstThenRefusesUntilRefilled() {
        final TokenBucket bucket = new TokenBucket(3, 2, START);
        assertTrue(bucket.tryAcquire(START));
        assertTrue(bucket.tryAcquire(START));
        assertTrue(bucket.tryAcquire(START));
        assertFalse(bucket.tryAcquire(START));

        // 2 tokens per second: one token after half a second
        final long halfSecond = START + TimeUnit.MILLISECONDS.toNanos(500);
        assertTrue(bucket.tryAcquire(halfSecond));
        assertFalse(bucket.tryAcquire(halfSecond));
    }

    @Test
    public void refillIsCappedAtBurst() {
        final TokenBucket bucket = new TokenBucket(2, 100, START);
        final long muchLater = START + TimeUnit.HOURS.toNanos(1);
        assertTrue(bucket.tryAcquire(muchLater));
        assertTrue(bucket.tryAcquire(muchLater));
        assertFalse(bucket.tryAcquire(muchLater));
    }

    @Test
    public void clockGoingBackDoesNotRefill() {
        final TokenBucket bucket = new TokenBucket(1, 1, START);
        assertTrue(bucket.tryAcquire(START));
        assertFalse(bucket.tryAcquire(START - TimeUnit.SECONDS.toNanos(10)));
        assertFalse(bucket.tryAcquire(START));
    }

    @Test
    public void nonPositiveBurstDisablesTheLimit() {
        for (final TokenBucket bucket : new TokenBucket[] { new TokenBucket(0, 10, START), new TokenBucket(-1, 10, START),
                new TokenBucket(0, 0, START), new TokenBucket(-1, -1, START) }) {
            for (int i = 0; i < 10_000; i++) {
                assertTrue(bucket.tryAcquire(START));
            }
        }
    }

    @Test
    public void nonPositiveRefillRateNeverRefills() {
        for (final TokenBucket bucket : new TokenBucket[] { new TokenBucket(2, 0, START), new TokenBucket(2, -1, START) }) {
            assertTrue(bucket.tryAcquire(START));
            assertTrue(bucket.tryAcquire(START));
            assertFalse(bucket.tryAcquire(START + TimeUnit.HOURS.toNanos(1)));
        }
    }
}
