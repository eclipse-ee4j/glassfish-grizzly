/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly.http.util;

import org.junit.Ignore;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpDateFormatTest {

    @Test
    @SuppressWarnings("deprecation")
    public void testHttpDateFormat() {
        final long now = Instant.now().toEpochMilli();
        assertEquals(FastHttpDateFormat.formatDate(now, null), HttpDateFormat.formatDate(now, null));

        final String sampleDate = "Fri, 03 Oct 2025 04:47:00 GMT";
        assertEquals(FastHttpDateFormat.parseDate(sampleDate, null), HttpDateFormat.parseDate(sampleDate, null));

        // check initial current date
        boolean equalResult = false;
        for (int i = 0; i < 2; i++) {
            final Object[] initDates = performSimultaneously(
                    new Supplier[]{FastHttpDateFormat::getCurrentDateBytes, HttpDateFormat::getCurrentDateBytes});
            assertEquals(2, initDates.length);
            equalResult = Arrays.equals((byte[]) initDates[0], (byte[]) initDates[1]);
            if (equalResult) {
                break;
            }
            // wait for 1 seconds to try again
            try {
                Thread.sleep(1001L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        assertTrue(equalResult);
        final String initFastDate = FastHttpDateFormat.getCurrentDate();
        final String initHttpDate = HttpDateFormat.getCurrentDate();
        assertEquals(initFastDate, initHttpDate);

        // wait for 1 seconds(next generation) to see the difference in current date
        try {
            Thread.sleep(1001L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // check re-generated current date
        equalResult = false;
        for (int i = 0; i < 2; i++) {
            final Object[] initDates = performSimultaneously(
                    new Supplier[]{FastHttpDateFormat::getCurrentDateBytes, HttpDateFormat::getCurrentDateBytes});
            assertEquals(2, initDates.length);
            equalResult = Arrays.equals((byte[]) initDates[0], (byte[]) initDates[1]);
            if (equalResult) {
                break;
            }
            // wait for 1 seconds to try again
            try {
                Thread.sleep(1001L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        assertTrue(equalResult);
        final String currentFastDate = FastHttpDateFormat.getCurrentDate();
        final String currentHttpDate = HttpDateFormat.getCurrentDate();
        assertEquals(currentFastDate, currentHttpDate);

        assertNotEquals(initHttpDate, currentHttpDate);
        assertTrue(HttpDateFormat.parseDate(initHttpDate, null) < HttpDateFormat.parseDate(currentHttpDate, null));
    }

    @Test
    @Ignore("Performance test")
    @SuppressWarnings("deprecation")
    public void testHttpDateFormatForPerformance() {
        final int repeatCount = 2;
        int numberOfThreads = 512;
        int numberOfTryCountPerThread = 10000;
        for (int i = 0; i < repeatCount; i++) {
            System.out.println("Performance test iteration #" + (i + 1));
            long timeElapsed = getMeasureTimeInMillis(numberOfThreads, numberOfTryCountPerThread,
                                                      j -> FastHttpDateFormat.getCurrentDateBytes());
            System.out.println("Time elapsed for FastHttpDateFormat with " + numberOfThreads + " threads and " +
                               numberOfTryCountPerThread + " tries per thread: " + timeElapsed + " ms");
            timeElapsed = getMeasureTimeInMillis(numberOfThreads, numberOfTryCountPerThread,
                                                 j -> HttpDateFormat.getCurrentDateBytes());
            System.out.println("Time elapsed for HttpDateFormat with " + numberOfThreads + " threads and " +
                               numberOfTryCountPerThread + " tries per thread: " + timeElapsed + " ms");
        }

        // with some sleep time in each loop
        final long sleepTimeInMillis = 50L;
        numberOfThreads = 192;
        numberOfTryCountPerThread = 15;
        for (int i = 0; i < repeatCount; i++) {
            System.out.println(
                    "Performance test with " + sleepTimeInMillis + " ms sleep in each loop, iteration #" + (i + 1));
            long timeElapsed = getMeasureTimeInMillis(numberOfThreads, numberOfTryCountPerThread, j -> {
                FastHttpDateFormat.getCurrentDateBytes();
                try {
                    Thread.sleep(sleepTimeInMillis);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            System.out.println("Time elapsed for FastHttpDateFormat with " + numberOfThreads + " threads and " +
                               numberOfTryCountPerThread + " tries per thread: " + timeElapsed + " ms");
            timeElapsed = getMeasureTimeInMillis(numberOfThreads, numberOfTryCountPerThread, j -> {
                HttpDateFormat.getCurrentDateBytes();
                try {
                    Thread.sleep(sleepTimeInMillis);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            System.out.println("Time elapsed for HttpDateFormat with " + numberOfThreads + " threads and " +
                               numberOfTryCountPerThread + " tries per thread: " + timeElapsed + " ms");
        }
    }

    private static Object[] performSimultaneously(final Supplier[] actions) {
        if (actions == null || actions.length == 0) {
            return new Object[0];
        }
        final CountDownLatch latch = new CountDownLatch(actions.length);
        return Stream.of(actions).parallel().map(action -> {
            latch.countDown();
            try {
                assertTrue(latch.await(3000L, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return action.get();
        }).toList().toArray(new Object[0]);
    }

    private static long getMeasureTimeInMillis(final int numberOfThreads, final int numberOfTryCountPerThread,
                                               final IntConsumer action) {
        final Instant start = Instant.now();
        // Each thread tries to set/get/remove each attribute multiple times.
        final CompletableFuture[] futures = IntStream.range(0, numberOfThreads).mapToObj(
                                                             i -> CompletableFuture.runAsync(() -> IntStream.range(0, numberOfTryCountPerThread).forEach(action)))
                                                     .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(futures).orTimeout(60, TimeUnit.SECONDS).join();
        } catch (Exception e) {
            fail(e.getMessage());
        }
        final Instant finish = Instant.now();
        return Duration.between(start, finish).toMillis();
    }
}
