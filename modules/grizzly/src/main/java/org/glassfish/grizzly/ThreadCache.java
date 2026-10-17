/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2010, 2020 Oracle and/or its affiliates. All rights reserved.
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.glassfish.grizzly.threadpool.DefaultWorkerThread;

/**
 *
 * @author oleksiys
 */
public final class ThreadCache {
    private static final ObjectCacheElement[] INITIAL_OBJECT_ARRAY = new ObjectCacheElement[16];

    private static final Map<String, CachedTypeIndex> typeIndexMap = new HashMap<>();

    private static int indexCounter;

    private static final ThreadLocal<ObjectCache> genericCacheAttr = new ThreadLocal<>();

    public static synchronized <E> CachedTypeIndex<E> obtainIndex(Class<E> clazz, int size) {
        return obtainIndex(clazz.getName(), clazz, size);

    }

    @SuppressWarnings("unchecked")
    public static synchronized <E> CachedTypeIndex<E> obtainIndex(String name, Class<E> clazz, int size) {

        CachedTypeIndex<E> typeIndex = typeIndexMap.get(name);
        if (typeIndex == null) {
            typeIndex = new CachedTypeIndex<>(indexCounter++, name, clazz, size);
            typeIndexMap.put(name, typeIndex);
        }

        return typeIndex;
    }

    public static <E> boolean putToCache(final CachedTypeIndex<E> index, final E o) {
        return putToCache(Thread.currentThread(), index, o);
    }

    /**
     * The {@link Owner} of the current thread's cache: an object taken from the cache (or created) on this thread can
     * remember it, so that {@link #putToOwnerCache(Owner, CachedTypeIndex, Object)} gives it back to this thread when another
     * thread recycles it.
     *
     * @return the current thread's {@link Owner}
     * @since 5.0.4
     */
    public static Owner currentOwner() {
        return currentObjectCache().owner();
    }

    /**
     * Puts the object back into the cache of the thread that {@code owner} belongs to. On that thread this is
     * {@link #putToCache(CachedTypeIndex, Object)}; from any other thread the object goes to a bounded queue of the owner,
     * which the owner's {@link #takeFromCache(CachedTypeIndex)} drains when its own cache is empty. This keeps objects
     * reusable when they are created on one thread (for example a selector thread) and recycled on another (a worker).
     * The queue is bounded, see the give-back factor property {@code org.glassfish.grizzly.ThreadCache.give-back-factor}.
     *
     * @param owner the {@link Owner} of the thread the object was taken or created on, or {@code null} for the current
     * thread
     * @param index the cached object type index
     * @param o the object
     * @return {@code true} if the object was cached
     * @since 5.0.4
     */
    public static <E> boolean putToOwnerCache(final Owner owner, final CachedTypeIndex<E> index, final E o) {
        if (owner == null) {
            return putToCache(index, o);
        }
        final Thread currentThread = Thread.currentThread();
        final ObjectCache currentCache = currentThread instanceof DefaultWorkerThread ? ((DefaultWorkerThread) currentThread).getObjectCache()
                : genericCacheAttr.get();
        if (currentCache != null && owner == currentCache.owner) {
            return currentCache.put(index, o);
        }
        // another thread's object: no cache is created on this thread for it
        return owner.giveBack(index, o);
    }

    private static ObjectCache currentObjectCache() {
        final Thread currentThread = Thread.currentThread();
        if (currentThread instanceof DefaultWorkerThread) {
            return ((DefaultWorkerThread) currentThread).getObjectCache();
        }
        ObjectCache genericCache = genericCacheAttr.get();
        if (genericCache == null) {
            genericCache = new ObjectCache();
            genericCacheAttr.set(genericCache);
        }
        return genericCache;
    }

    public static <E> boolean putToCache(final Thread currentThread, final CachedTypeIndex<E> index, final E o) {
        if (currentThread instanceof DefaultWorkerThread) {
            return ((DefaultWorkerThread) currentThread).putToCache(index, o);
        } else {
            ObjectCache genericCache = genericCacheAttr.get();
            if (genericCache == null) {
                genericCache = new ObjectCache();
                genericCacheAttr.set(genericCache);
            }

            return genericCache.put(index, o);
        }
    }

    /**
     * Get the cached object with the given type index from cache. Unlike
     * {@link #takeFromCache(org.glassfish.grizzly.ThreadCache.CachedTypeIndex)}, the object won't be removed from cache.
     *
     * @param <E> cached object type
     * @param index the cached object type index.
     * @return cached object.
     */
    public static <E> E getFromCache(final CachedTypeIndex<E> index) {
        return getFromCache(Thread.currentThread(), index);
    }

    /**
     * Get the cached object with the given type index from cache. Unlike
     * {@link #takeFromCache(org.glassfish.grizzly.ThreadCache.CachedTypeIndex)}, the object won't be removed from cache.
     *
     * @param <E> cached object type
     * @param currentThread current {@link Thread}
     * @param index the cached object type index.
     * @return cached object.
     */
    public static <E> E getFromCache(final Thread currentThread, final CachedTypeIndex<E> index) {
        assert currentThread == Thread.currentThread();

        if (currentThread instanceof DefaultWorkerThread) {
            return ((DefaultWorkerThread) currentThread).getFromCache(index);
        } else {
            final ObjectCache genericCache = genericCacheAttr.get();
            if (genericCache != null) {
                return genericCache.get(index);
            }

            return null;
        }
    }

    /**
     * Take the cached object with the given type index from cache. Unlike
     * {@link #getFromCache(org.glassfish.grizzly.ThreadCache.CachedTypeIndex)}, the object will be removed from cache.
     *
     * @param <E> cached object type
     * @param index the cached object type index
     * @return cached object
     */
    public static <E> E takeFromCache(final CachedTypeIndex<E> index) {
        return takeFromCache(Thread.currentThread(), index);
    }

    /**
     * Take the cached object with the given type index from cache. Unlike
     * {@link #getFromCache(org.glassfish.grizzly.ThreadCache.CachedTypeIndex)}, the object will be removed from cache.
     *
     * @param <E> cached object type
     * @param currentThread current {@link Thread}
     * @param index the cached object type index
     * @return cached object
     */
    public static <E> E takeFromCache(final Thread currentThread, final CachedTypeIndex<E> index) {
        if (currentThread instanceof DefaultWorkerThread) {
            return ((DefaultWorkerThread) currentThread).takeFromCache(index);
        } else {
            final ObjectCache genericCache = genericCacheAttr.get();
            if (genericCache != null) {
                return genericCache.take(index);
            }

            return null;
        }
    }

    public static final class ObjectCache {
        private ObjectCacheElement[] objectCacheElements;
        // created when an object of this thread may be recycled elsewhere, see ThreadCache#currentOwner()
        private Owner owner;

        Owner owner() {
            if (owner == null) {
                owner = new Owner();
            }
            return owner;
        }

        public boolean put(final CachedTypeIndex index, final Object o) {
            if (objectCacheElements != null && index.getIndex() < objectCacheElements.length) {
                ObjectCacheElement objectCache = objectCacheElements[index.getIndex()];
                if (objectCache == null) {
                    objectCache = new ObjectCacheElement(index.size);
                    objectCacheElements[index.getIndex()] = objectCache;
                }

                return objectCache.put(o);
            }

            final ObjectCacheElement[] arrayToGrow = objectCacheElements != null ? objectCacheElements : INITIAL_OBJECT_ARRAY;
            final int newSize = Math.max(index.getIndex() + 1, arrayToGrow.length * 3 / 2 + 1);

            objectCacheElements = Arrays.copyOf(arrayToGrow, newSize);

            final ObjectCacheElement objectCache = new ObjectCacheElement(index.getSize());
            objectCacheElements[index.getIndex()] = objectCache;
            return objectCache.put(o);
        }

        /**
         * Get the cached object with the given type index from cache. Unlike
         * {@link #take(org.glassfish.grizzly.ThreadCache.CachedTypeIndex)}, the object won't be removed from cache.
         *
         * @param <E> cached object type
         * @param index the cached object type index.
         * @return cached object.
         */
        @SuppressWarnings("unchecked")
        public <E> E get(final CachedTypeIndex<E> index) {
            final int idx;
            if (objectCacheElements != null && (idx = index.getIndex()) < objectCacheElements.length) {

                final ObjectCacheElement objectCache = objectCacheElements[idx];
                if (objectCache == null) {
                    return null;
                }

                return (E) objectCache.get();
            }

            return null;
        }

        /**
         * Take the cached object with the given type index from cache. Unlike
         * {@link #get(org.glassfish.grizzly.ThreadCache.CachedTypeIndex)}, the object will be removed from cache.
         *
         * @param <E> cached object type
         * @param index the cached object type index.
         * @return cached object.
         */
        @SuppressWarnings("unchecked")
        public <E> E take(final CachedTypeIndex<E> index) {
            final int idx;
            if (objectCacheElements != null && (idx = index.getIndex()) < objectCacheElements.length) {

                final ObjectCacheElement objectCache = objectCacheElements[idx];
                if (objectCache != null) {
                    final Object o = objectCache.take();
                    if (o != null) {
                        return (E) o;
                    }
                }
            }

            // objects of this thread that other threads recycled
            return owner != null ? (E) owner.takeBack(index) : null;
        }
    }

    public static final class ObjectCacheElement {
        private final int size;
        private final Object[] cache;
        private int index;

        public ObjectCacheElement(int size) {
            this.size = size;
            cache = new Object[size];
        }

        public boolean put(Object o) {
            if (index < size) {
                cache[index++] = o;
                return true;
            }

            return false;
        }

        /**
         * Get (peek) the object from cache. Unlike {@link #take()} the object will not be removed from cache.
         *
         * @return object from cache.
         */
        public Object get() {
            if (index > 0) {
                final Object o = cache[index - 1];
                return o;
            }

            return null;
        }

        /**
         * Take (poll) the object from cache. Unlike {@link #get()} the object will be removed from cache.
         *
         * @return object from cache.
         */
        public Object take() {
            if (index > 0) {
                index--;

                final Object o = cache[index];
                cache[index] = null;
                return o;
            }

            return null;
        }
    }

    /**
     * The objects that other threads gave back to a thread's cache, see {@link ThreadCache#currentOwner()}. Any thread may
     * give an object back; the owner thread takes them when its own cache is empty.
     *
     * @since 5.0.4
     */
    public static final class Owner {
        private volatile ReturnQueue[] queues = new ReturnQueue[0];

        Owner() {
        }

        boolean giveBack(final CachedTypeIndex<?> index, final Object o) {
            return queue(index.getIndex()).offer(o, giveBackBound(index));
        }

        Object takeBack(final CachedTypeIndex<?> index) {
            final ReturnQueue[] q = queues;
            final int idx = index.getIndex();
            return idx < q.length && q[idx] != null ? q[idx].poll() : null;
        }

        private ReturnQueue queue(final int idx) {
            ReturnQueue[] q = queues;
            if (idx < q.length && q[idx] != null) {
                return q[idx];
            }
            synchronized (this) {
                q = queues;
                if (idx >= q.length) {
                    q = Arrays.copyOf(q, Math.max(idx + 1, q.length * 3 / 2 + 1));
                }
                if (q[idx] == null) {
                    q[idx] = new ReturnQueue();
                }
                queues = q;
                return q[idx];
            }
        }
    }

    /**
     * Property for the give-back factor, see {@link #giveBackBound(CachedTypeIndex)}: 4 by default, at least 1.
     */
    static final String GIVE_BACK_FACTOR_PROPERTY = ThreadCache.class.getName() + ".give-back-factor";
    private static final int GIVE_BACK_FACTOR = Math.max(1, Integer.getInteger(GIVE_BACK_FACTOR_PROPERTY, 4));

    /**
     * How many objects of a type may wait for their owner, on top of the owner's own cache: the type's cache size times
     * the give-back factor, because one owner may get back the objects of many recycling threads at once. The objects
     * kept per owner thread and type are therefore bounded by the cache size times (1 + factor); they don't grow with the
     * number of connections. An owner thread that stays alive but no longer takes keeps them until it ends.
     */
    static int giveBackBound(final CachedTypeIndex<?> index) {
        return (int) Math.min(Integer.MAX_VALUE, (long) index.getSize() * GIVE_BACK_FACTOR);
    }

    /**
     * A bounded lock-free stack. Every push allocates a new node and writes its link before the publishing
     * compare-and-set, and a node is never pushed twice, so popping with a compare-and-set has no ABA problem, even if
     * several threads pop. The node count never exceeds the bound (the size is incremented and checked before the push,
     * and decremented after the pop); only the counter can briefly overshoot, which rejects an offer, never retains more.
     */
    private static final class ReturnQueue {
        private final AtomicReference<Node> head = new AtomicReference<>();
        private final AtomicInteger size = new AtomicInteger();

        boolean offer(final Object o, final int max) {
            if (size.incrementAndGet() > max) {
                size.decrementAndGet();
                return false;
            }
            final Node node = new Node(o);
            Node h;
            do {
                h = head.get();
                node.next = h;
            } while (!head.compareAndSet(h, node));
            return true;
        }

        Object poll() {
            Node h;
            do {
                h = head.get();
                if (h == null) {
                    return null;
                }
            } while (!head.compareAndSet(h, h.next));
            size.decrementAndGet();
            return h.item;
        }

        private static final class Node {
            final Object item;
            Node next;

            Node(final Object item) {
                this.item = item;
            }
        }
    }

    public static final class CachedTypeIndex<E> {
        private final int index;
        private final Class clazz;
        private final int size;
        private final String name;

        public CachedTypeIndex(final int index, final String name, final Class<E> clazz, final int size) {
            this.index = index;
            this.name = name;
            this.clazz = clazz;
            this.size = size;
        }

        public int getIndex() {
            return index;
        }

        public String getName() {
            return name;
        }

        public Class getClazz() {
            return clazz;
        }

        public int getSize() {
            return size;
        }
    }
}
