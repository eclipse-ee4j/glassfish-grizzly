/*
 * Copyright (c) 2011, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.glassfish.grizzly.attributes.AttributeBuilder;
import org.glassfish.grizzly.threadpool.DefaultWorkerThread;
import org.junit.Test;

/**
 * Test thread-local cache.
 *
 * @author Alexey Stashok
 */
public class ThreadCacheTest {
    @Test
    public void testGet() {
        final ThreadCache.CachedTypeIndex<Object> CACHE_IDX = ThreadCache.obtainIndex("testGet", Object.class, 1);

        assertNull(ThreadCache.getFromCache(CACHE_IDX));

        final Object obj = new Object();
        ThreadCache.putToCache(CACHE_IDX, obj);

        for (int i = 0; i < 10; i++) {
            assertEquals(obj, ThreadCache.getFromCache(CACHE_IDX));
        }
    }

    @Test
    public void testTake() {
        final int size = 5;

        final ThreadCache.CachedTypeIndex<Object> CACHE_IDX = ThreadCache.obtainIndex("testTake", Object.class, size);

        assertNull(ThreadCache.getFromCache(CACHE_IDX));

        for (int i = 0; i < size * 2; i++) {
            ThreadCache.putToCache(CACHE_IDX, new Object());
        }

        for (int i = 0; i < size; i++) {
            assertNotNull(ThreadCache.takeFromCache(CACHE_IDX));
        }

        assertNull(ThreadCache.takeFromCache(CACHE_IDX));

    }


    /** An object recycled on another thread goes back to the cache of the thread it belongs to. */
    @Test
    public void anObjectRecycledElsewhereIsTakenBackByItsOwner() throws Exception {
        final ThreadCache.CachedTypeIndex<Object> idx = ThreadCache.obtainIndex("giveBack", Object.class, 4);
        for (final boolean workerThread : new boolean[] { false, true }) {
            final ExecutorService ownerThread = Executors.newSingleThreadExecutor(
                    r -> workerThread ? new DefaultWorkerThread(AttributeBuilder.DEFAULT_ATTRIBUTE_BUILDER, "cache-owner", null, r) : new Thread(r));
            final ExecutorService otherThread = Executors.newSingleThreadExecutor();
            try {
                final Object o = new Object();
                final ThreadCache.Owner owner = ownerThread.submit(ThreadCache::currentOwner).get(10, TimeUnit.SECONDS);
                assertTrue(otherThread.submit(() -> ThreadCache.putToCache(owner, idx, o)).get(10, TimeUnit.SECONDS));
                assertNull("not in the other thread's cache", otherThread.submit(() -> ThreadCache.takeFromCache(idx)).get(10, TimeUnit.SECONDS));
                assertSame("worker thread: " + workerThread, o, ownerThread.submit(() -> ThreadCache.takeFromCache(idx)).get(10, TimeUnit.SECONDS));
                assertNull(ownerThread.submit(() -> ThreadCache.takeFromCache(idx)).get(10, TimeUnit.SECONDS));
            } finally {
                ownerThread.shutdownNow();
                otherThread.shutdownNow();
            }
        }
    }

    /** On the owner thread itself the object goes to the thread's cache as with putToCache(index, o). */
    @Test
    public void theOwnerThreadPutsIntoItsOwnCache() {
        final ThreadCache.CachedTypeIndex<Object> idx = ThreadCache.obtainIndex("ownerLocal", Object.class, 2);
        final Object o = new Object();
        assertTrue(ThreadCache.putToCache(ThreadCache.currentOwner(), idx, o));
        assertSame(o, ThreadCache.getFromCache(idx));
        assertSame(o, ThreadCache.takeFromCache(idx));
        assertTrue(ThreadCache.putToCache((ThreadCache.Owner) null, idx, o));
        assertSame(o, ThreadCache.takeFromCache(idx));
    }

    /** No more objects than the type's cache size wait for their owner. */
    @Test
    public void givenBackObjectsAreBounded() throws Exception {
        final ThreadCache.CachedTypeIndex<Object> idx = ThreadCache.obtainIndex("giveBackBound", Object.class, 3);
        final ExecutorService ownerThread = Executors.newSingleThreadExecutor();
        final ThreadCache.Owner owner;
        try {
            owner = ownerThread.submit(ThreadCache::currentOwner).get(10, TimeUnit.SECONDS);
        } finally {
            ownerThread.shutdownNow();
        }
        for (int i = 0; i < 3; i++) {
            assertTrue(ThreadCache.putToCache(owner, idx, new Object()));
        }
        assertFalse(ThreadCache.putToCache(owner, idx, new Object()));
    }

    /** Many threads give back while the owner takes: every accepted object is taken exactly once. */
    @Test
    public void concurrentGiveBacks() throws Exception {
        final int size = 64;
        final int producers = 4;
        final int perProducer = 20_000;
        final ThreadCache.CachedTypeIndex<Object> idx = ThreadCache.obtainIndex("giveBackConcurrent", Object.class, size);
        final CompletableFuture<ThreadCache.Owner> ownerFuture = new CompletableFuture<>();
        final AtomicInteger accepted = new AtomicInteger();
        final CountDownLatch producersDone = new CountDownLatch(producers);
        final Set<Object> taken = Collections.newSetFromMap(new IdentityHashMap<>());
        final AtomicInteger duplicates = new AtomicInteger();
        final Thread consumer = new Thread(() -> {
            ownerFuture.complete(ThreadCache.currentOwner());
            while (true) {
                final Object o = ThreadCache.takeFromCache(idx);
                if (o != null) {
                    if (!taken.add(o)) {
                        duplicates.incrementAndGet();
                    }
                } else if (producersDone.getCount() == 0) {
                    // a last pass after all producers ended
                    Object rest;
                    while ((rest = ThreadCache.takeFromCache(idx)) != null) {
                        if (!taken.add(rest)) {
                            duplicates.incrementAndGet();
                        }
                    }
                    return;
                }
            }
        });
        consumer.start();
        final ThreadCache.Owner owner = ownerFuture.get(10, TimeUnit.SECONDS);
        final List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            final Thread t = new Thread(() -> {
                for (int i = 0; i < perProducer; i++) {
                    if (ThreadCache.putToCache(owner, idx, new Object())) {
                        accepted.incrementAndGet();
                    }
                }
                producersDone.countDown();
            });
            threads.add(t);
            t.start();
        }
        for (final Thread t : threads) {
            t.join(30_000);
        }
        consumer.join(30_000);
        assertFalse(consumer.isAlive());
        assertTrue("nothing accepted", accepted.get() > 0);
        assertEquals("taken twice", 0, duplicates.get());
        assertEquals("accepted and taken", accepted.get(), taken.size());
    }
}
