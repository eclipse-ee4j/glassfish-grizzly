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

package org.glassfish.grizzly.attributes;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * An {@link AttributeHolder} implementation, which stores {@link Attribute} values in a {@link ConcurrentMap},
 * accessing them by {@link Attribute#index()}.
 * <p>
 * This implementation is thread-safe.
 *
 * @author Bongjae Chang
 */
@SuppressWarnings("rawtypes")
public class IndexedMapAttributeHolder implements AttributeHolder {

    private final ConcurrentMap<Integer, Object> valueMap = new ConcurrentHashMap<>();
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
        return valueMap.keySet().stream().map(index -> attributeBuilder.getAttributeByIndex(index).name())
                       .collect(Collectors.toSet());
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
            valueMap.putAll(imah.valueMap);
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
            imah.valueMap.clear();
            imah.valueMap.putAll(valueMap);
        } else {
            dstAttributes.clear();
            valueMap.forEach(
                    (index, value) -> dstAttributes.setAttribute(attributeBuilder.getAttributeByIndex(index).name(),
                                                                 value));
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
        valueMap.clear();
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

    /**
     * {@link IndexedAttributeAccessor} implementation.
     */
    private final class IndexedAttributeAccessorImpl implements IndexedAttributeAccessor {
        /**
         * {@inheritDoc}
         */
        @Override
        public Object getAttribute(final int index) {
            return getAttribute(index, null);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object getAttribute(final int index, final Supplier initializer) {
            return valueMap.computeIfAbsent(index, k -> initializer != null ? initializer.get() : null);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void setAttribute(final int index, final Object value) {
            if (value != null) {
                valueMap.put(index, value);
            } else {
                valueMap.remove(index);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object removeAttribute(final int index) {
            return valueMap.remove(index);
        }
    }
}
