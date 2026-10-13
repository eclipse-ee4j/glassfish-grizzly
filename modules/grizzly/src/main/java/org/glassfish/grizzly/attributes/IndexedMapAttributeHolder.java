/*
 * Copyright (c) 2025, 2026 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly.attributes;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * An {@link AttributeHolder} implementation, which stores {@link Attribute} values in an array indexed by
 * {@link Attribute#index()}.
 * <p>
 * This implementation is thread-safe. Reads are a single acquiring array load and writes a CAS on the slot, so the
 * per-request attribute traffic of a connection (touched by selector, worker and timer threads) neither allocates nor
 * walks a hash table. The array grows like a {@code ConcurrentHashMap} table resizes: under a lock, every slot of the
 * old array is atomically swapped for a {@code MOVED} marker while its value is copied, so a writer racing with the
 * resize fails its CAS and retries on the new array instead of losing its update. Initializers run under a lock, at
 * most once per absent value.
 *
 * @author Bongjae Chang
 */
@SuppressWarnings("rawtypes")
public class IndexedMapAttributeHolder implements AttributeHolder {

    private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Object[].class);

    /** Placed in every slot of an array that has been replaced by a larger one. */
    private static final Object MOVED = new Object();

    private static final Object[] EMPTY = {};

    private static final int MIN_CAPACITY = 16;

    private volatile Object[] values = EMPTY;

    private final Object lock = new Object();

    private final IndexedAttributeAccessor indexedAttributeAccessor = new IndexedAttributeAccessorImpl();

    private final DefaultAttributeBuilder attributeBuilder;

    IndexedMapAttributeHolder(final AttributeBuilder attributeBuilder) {
        this.attributeBuilder = (DefaultAttributeBuilder) attributeBuilder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getAttribute(final String name) {
        return getAttribute(name, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getAttribute(final String name, final Supplier initializer) {
        final Attribute attribute = attributeBuilder.getAttributeByName(name);
        if (attribute != null) {
            return indexedAttributeAccessor.getAttribute(attribute.index(), initializer);
        }
        return initializer != null ? initializer.get() : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAttribute(final String name, final Object value) {
        Attribute attribute = attributeBuilder.getAttributeByName(name);
        if (attribute == null) {
            attribute = attributeBuilder.createAttribute(name);
        }
        indexedAttributeAccessor.setAttribute(attribute.index(), value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object removeAttribute(final String name) {
        final Attribute attribute = attributeBuilder.getAttributeByName(name);
        if (attribute != null) {
            return indexedAttributeAccessor.removeAttribute(attribute.index());
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<String> getAttributeNames() {
        final Set<String> names = new HashSet<>();
        final int length = values.length;
        for (int i = 0; i < length; i++) {
            if (get(i) != null) {
                names.add(attributeBuilder.getAttributeByIndex(i).name());
            }
        }
        return names;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void copyFrom(final AttributeHolder srcAttributes) {
        if (srcAttributes == null) {
            return;
        }
        clear();
        if (srcAttributes instanceof IndexedMapAttributeHolder imah) {
            imah.copyValuesTo(this);
        } else {
            srcAttributes.getAttributeNames().forEach(name -> setAttribute(name, srcAttributes.getAttribute(name)));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void copyTo(final AttributeHolder dstAttributes) {
        if (dstAttributes == null) {
            return;
        }
        if (dstAttributes instanceof IndexedMapAttributeHolder imah) {
            imah.clear();
            copyValuesTo(imah);
        } else {
            dstAttributes.clear();
            final int length = values.length;
            for (int i = 0; i < length; i++) {
                final Object value = get(i);
                if (value != null) {
                    dstAttributes.setAttribute(attributeBuilder.getAttributeByIndex(i).name(), value);
                }
            }
        }
    }

    private void copyValuesTo(final IndexedMapAttributeHolder dst) {
        final int length = values.length;
        for (int i = 0; i < length; i++) {
            final Object value = get(i);
            if (value != null) {
                dst.getAndSet(i, value);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void recycle() {
        clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        final int length = values.length;
        for (int i = 0; i < length; i++) {
            getAndSet(i, null);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AttributeBuilder getAttributeBuilder() {
        return attributeBuilder;
    }

    /**
     * Returns {@link IndexedAttributeAccessor} for accessing {@link Attribute}s by index.
     *
     * @return {@link IndexedAttributeAccessor} for accessing {@link Attribute}s by index.
     */
    @Override
    public IndexedAttributeAccessor getIndexedAttributeAccessor() {
        return indexedAttributeAccessor;
    }

    // ------------------------------------------------------------------ array

    private Object get(final int index) {
        Object[] array = values;
        while (index < array.length) {
            final Object value = SLOT.getAcquire(array, index);
            if (value != MOVED) {
                return value;
            }
            array = awaitResized(array);
        }
        return null;
    }

    /**
     * Atomically replaces the value at {@code index} ({@code null} removes it) and returns the previous one.
     */
    private Object getAndSet(final int index, final Object value) {
        Object[] array = values;
        while (true) {
            if (index >= array.length) {
                if (value == null) {
                    return null;
                }
                array = resize(array, index);
                continue;
            }
            final Object current = SLOT.getAcquire(array, index);
            if (current == MOVED) {
                array = awaitResized(array);
            } else if (SLOT.compareAndSet(array, index, current, value)) {
                return current;
            }
        }
    }

    /**
     * Stores {@code value} at {@code index} unless a value is already there; returns the value in place afterwards.
     */
    private Object putIfAbsent(final int index, final Object value) {
        Object[] array = values;
        while (true) {
            if (index >= array.length) {
                array = resize(array, index);
                continue;
            }
            final Object current = SLOT.getAcquire(array, index);
            if (current == MOVED) {
                array = awaitResized(array);
            } else if (current != null) {
                return current;
            } else if (SLOT.compareAndSet(array, index, null, value)) {
                return value;
            }
        }
    }

    private Object[] resize(final Object[] array, final int index) {
        synchronized (lock) {
            final Object[] current = values;
            if (current != array) {
                return current; // already resized by another thread
            }
            final Object[] resized = new Object[Math.max(index + 1, Math.max(MIN_CAPACITY, array.length * 2))];
            for (int i = 0; i < array.length; i++) {
                // freeze the slot: CASes on the old array fail from now on and retry on the new one
                resized[i] = SLOT.getAndSet(array, i, MOVED);
            }
            values = resized;
            return resized;
        }
    }

    private Object[] awaitResized(final Object[] array) {
        Object[] current;
        while ((current = values) == array) {
            // the resizing thread has frozen this array and is about to publish the new one
            Thread.onSpinWait();
        }
        return current;
    }

    /**
     * {@link IndexedAttributeAccessor} implementation.
     */
    private final class IndexedAttributeAccessorImpl implements IndexedAttributeAccessor {
        /**
         * {@inheritDoc}
         */
        @Override
        public Object getAttribute(final int index) {
            return get(index);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object getAttribute(final int index, final Supplier initializer) {
            final Object value = get(index);
            if (value != null || initializer == null) {
                return value;
            }
            synchronized (lock) {
                // run the initializer at most once per absent value
                final Object existing = get(index);
                if (existing != null) {
                    return existing;
                }
                final Object initialized = initializer.get();
                return initialized == null ? null : putIfAbsent(index, initialized);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void setAttribute(final int index, final Object value) {
            getAndSet(index, value);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object removeAttribute(final int index) {
            return getAndSet(index, null);
        }
    }
}
