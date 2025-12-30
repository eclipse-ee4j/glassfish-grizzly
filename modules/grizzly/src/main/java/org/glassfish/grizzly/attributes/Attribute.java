/*
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

package org.glassfish.grizzly.attributes;

import java.util.function.Supplier;

/**
 * Class used to define dynamic typed attributes on {@link AttributeHolder} instances. Storing attribute values in
 * {@link AttributeHolder} has two advantage comparing to Map storage: 1) <tt>Attribute</tt> value is typed, and could
 * be checked at compile time. 2) Access to <tt>Attribute</tt> value, if used with {@link IndexedAttributeHolder}, could
 * be as fast as access to array.
 * 
 * @param <T>
 */
public final class Attribute<T> {
    /**
     * AttributeBuilder, which was used to create this attribute
     */
    private final AttributeBuilder builder;
    /**
     * Attribute name
     */
    private final String name;
    /**
     * Attribute initializer, which will be called, if attribute is not set.
     */
    private final Supplier<T> initializer;
    /**
     * Attribute index in AttributeBuilder
     */
    private final int attributeIndex;

    @Override
    public String toString() {
        return "Attribute[" + name + ':' + attributeIndex + ']';
    }

    protected Attribute(final AttributeBuilder builder, final String name, final int index, final T defaultValue) {
        this(builder, name, index, new Supplier<T>() {

            @Override
            public T get() {
                return defaultValue;
            }
        });
    }

    protected Attribute(final AttributeBuilder builder, final String name, final int index, final Supplier<T> initializer) {
        this.builder = builder;
        this.name = name;
        this.attributeIndex = index;
        this.initializer = initializer;
    }

    /**
     * Get attribute value, stored on the {@link AttributeHolder}, the difference from
     * {@link #get(org.glassfish.grizzly.attributes.AttributeHolder)} is that default value or {@link Supplier} won't
     * be invoked.
     *
     * @param attributeHolder {@link AttributeHolder}.
     * @return attribute value
     */
    public T peek(final AttributeHolder attributeHolder) {
        return get0(attributeHolder, null);
    }

    /**
     * Get attribute value, stored on the {@link AttributeStorage}, the difference from {@link #get(AttributeStorage)} is
     * that default value or {@link Supplier} won't be invoked.
     *
     * @param storage {@link AttributeStorage}.
     * @return attribute value
     */
    public T peek(final AttributeStorage storage) {
        final AttributeHolder holder = storage.getAttributes();
        if (holder != null) {
            return peek(holder);
        }

        return null;
    }

    /**
     * Get attribute value, stored on the {@link AttributeHolder}.
     *
     * @param attributeHolder {@link AttributeHolder}.
     * @return attribute value
     */
    public T get(final AttributeHolder attributeHolder) {
        return get0(attributeHolder, initializer);
    }

    /**
     * Get attribute value, stored on the {@link AttributeStorage}.
     *
     * @param storage {@link AttributeStorage}.
     * @return attribute value
     */
    public T get(final AttributeStorage storage) {
        return get(storage.getAttributes());
    }

    /**
     * Set attribute value, stored on the {@link AttributeHolder}.
     *
     * @param attributeHolder {@link AttributeHolder}.
     * @param value attribute value to set.
     */
    public void set(final AttributeHolder attributeHolder, final T value) {
        final IndexedAttributeAccessor indexedAccessor = attributeHolder.getIndexedAttributeAccessor();

        if (indexedAccessor != null) {
            indexedAccessor.setAttribute(attributeIndex, value);
        } else {
            attributeHolder.setAttribute(name, value);
        }
    }

    /**
     * Set attribute value, stored on the {@link AttributeStorage}.
     *
     * @param storage {@link AttributeStorage}.
     * @param value attribute value to set.
     */
    public void set(final AttributeStorage storage, final T value) {
        set(storage.getAttributes(), value);
    }

    /**
     * Remove attribute value, stored on the {@link AttributeHolder}.
     *
     * @param attributeHolder {@link AttributeHolder}.
     * @return the previous value associated with the attribute
     */
    @SuppressWarnings("unchecked")
    public T remove(final AttributeHolder attributeHolder) {
        final IndexedAttributeAccessor indexedAccessor = attributeHolder.getIndexedAttributeAccessor();

        return indexedAccessor != null ? (T) indexedAccessor.removeAttribute(attributeIndex) : (T) attributeHolder.removeAttribute(name);
    }

    /**
     * Remove attribute value, stored on the {@link AttributeStorage}.
     *
     * @param storage {@link AttributeStorage}.
     */
    public T remove(final AttributeStorage storage) {
        final AttributeHolder holder = storage.getAttributes();
        if (holder != null) {
            return remove(holder);
        }

        return null;
    }

    /**
     * Checks if this attribute is set on the {@link AttributeHolder}. Returns <tt>true</tt>, if attribute is set, of
     * <tt>false</tt> otherwise.
     *
     * @param attributeHolder {@link AttributeHolder}.
     *
     * @return <tt>true</tt>, if attribute is set, of <tt>false</tt> otherwise.
     */
    public boolean isSet(final AttributeHolder attributeHolder) {
        return get0(attributeHolder, null) != null;
    }

    /**
     * Checks if this attribute is set on the {@link AttributeStorage}. Returns <tt>true</tt>, if attribute is set, of
     * <tt>false</tt> otherwise.
     *
     * @param storage {@link AttributeStorage}.
     *
     * @return <tt>true</tt>, if attribute is set, of <tt>false</tt> otherwise.
     */
    public boolean isSet(final AttributeStorage storage) {
        final AttributeHolder holder = storage.getAttributes();
        return holder != null && isSet(holder);

    }

    /**
     * Return attribute name, which is used as attribute key on non-indexed {@link AttributeHolder}s.
     *
     * @return attribute name.
     */
    public String name() {
        return name;
    }

    /**
     * Return attribute name, which is used as attribute key on indexed {@link AttributeHolder}s.
     *
     * @return attribute indexed.
     */
    public int index() {
        return attributeIndex;
    }

    @SuppressWarnings("unchecked")
    private T get0(final AttributeHolder attributeHolder, final Supplier<T> initializer) {
        final IndexedAttributeAccessor indexedAccessor = attributeHolder.getIndexedAttributeAccessor();

        return indexedAccessor != null ? (T) indexedAccessor.getAttribute(attributeIndex, initializer) : (T) attributeHolder.getAttribute(name, initializer);
    }
}
