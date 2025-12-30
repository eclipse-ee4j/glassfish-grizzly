/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.function.Supplier;

/**
 * Default {@link AttributeBuilder} implementation.
 *
 * @see AttributeBuilder
 *
 * @author Alexey Stashok
 */
public class DefaultAttributeBuilder implements AttributeBuilder {
    protected final List<Attribute> attributes = new ArrayList<>();
    protected final Map<String, Attribute> name2Attribute = new HashMap<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized <T> Attribute<T> createAttribute(final String name) {
        return createAttribute(name, (T) null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> Attribute<T> createAttribute(final String name, final T defaultValue) {
        Attribute<T> attribute = name2Attribute.get(name);
        if (attribute == null) {
            attribute = new Attribute<>(this, name, attributes.size(), defaultValue);
            attributes.add(attribute);
            name2Attribute.put(name, attribute);
        }

        return attribute;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> Attribute<T> createAttribute(final String name, final Supplier<T> initializer) {
        Attribute<T> attribute = name2Attribute.get(name);
        if (attribute == null) {
            attribute = new Attribute<>(this, name, attributes.size(), initializer);
            attributes.add(attribute);
            name2Attribute.put(name, attribute);
        }

        return attribute;
    }

    @Override
    public AttributeHolder createSafeAttributeHolder() {
        return new IndexedMapAttributeHolder(this);
    }

    @Override
    public AttributeHolder createUnsafeAttributeHolder() {
        return new UnsafeAttributeHolder(this);
    }

    protected Attribute getAttributeByName(final String name) {
        return name2Attribute.get(name);
    }

    protected Attribute getAttributeByIndex(final int index) {
        return attributes.get(index);
    }
}
