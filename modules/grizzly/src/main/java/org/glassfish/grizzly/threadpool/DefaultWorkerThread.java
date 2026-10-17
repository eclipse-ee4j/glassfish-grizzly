/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright 2021 Contributors to the Eclipse Foundation.
 * Copyright (c) 2008, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.threadpool;

import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.ThreadCache.ObjectCache;
import org.glassfish.grizzly.attributes.AttributeBuilder;
import org.glassfish.grizzly.attributes.AttributeHolder;
import org.glassfish.grizzly.memory.ThreadLocalPool;

/**
 * Default Grizzly worker thread implementation
 *
 * @author Alexey Stashok
 */
public class DefaultWorkerThread extends Thread implements WorkerThread {

    private final AttributeHolder attributes;

    private final ThreadLocalPool memoryPool;

    private final ObjectCache objectCache = new ObjectCache();

    private long transactionTimeoutMillis = WorkerThread.UNLIMITED_TRANSACTION_TIMEOUT;

    public DefaultWorkerThread(AttributeBuilder attrBuilder, String name, ThreadLocalPool pool, Runnable runTask) {
        super(runTask, name);
        attributes = attrBuilder.createUnsafeAttributeHolder();
        memoryPool = pool;

    }

    @Override
    public Thread getThread() {
        return this;
    }

    @Override
    public AttributeHolder getAttributes() {
        return attributes;
    }

    public ThreadLocalPool getMemoryPool() {
        return memoryPool;
    }

    /**
     * Get the cached object with the given type index from cache. Unlike
     * {@link #takeFromCache(org.glassfish.grizzly.ThreadCache.CachedTypeIndex)}, the object won't be removed from cache.
     *
     * @param <E>
     * @param index the cached object type index.
     * @return cached object.
     */
    public final <E> E getFromCache(final ThreadCache.CachedTypeIndex<E> index) {
        return objectCache.get(index);
    }

    /**
     * Take the cached object with the given type index from cache. Unlike
     * {@link #getFromCache(org.glassfish.grizzly.ThreadCache.CachedTypeIndex)}, the object will be removed from cache.
     *
     * @param <E>
     * @param index the cached object type index.
     * @return cached object.
     */
    public final <E> E takeFromCache(final ThreadCache.CachedTypeIndex<E> index) {
        return objectCache.take(index);
    }

    public final <E> boolean putToCache(final ThreadCache.CachedTypeIndex<E> index, final E o) {
        return objectCache.put(index, o);
    }

    /**
     * @return this thread's object cache, see {@link ThreadCache}
     * @since 5.0.4
     */
    public final ObjectCache getObjectCache() {
        return objectCache;
    }

    @Override
    public long getTransactionTimeout(TimeUnit timeunit) {
        return timeunit.convert(transactionTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void setTransactionTimeout(long timeout, TimeUnit timeunit) {
        this.transactionTimeoutMillis = TimeUnit.MILLISECONDS.convert(timeout, timeunit);
    }

    public void destroy() {
    }
}
