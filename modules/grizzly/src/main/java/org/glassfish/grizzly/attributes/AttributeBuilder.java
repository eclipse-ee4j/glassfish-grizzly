/*
 * Copyright (c) 2008, 2023 Oracle and/or its affiliates. All rights reserved.
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
 * <tt>AttributeBuilder</tt> is responsible for creating and indexing {@link Attribute}s. For faster access to
 * {@link Attribute} value, each {@link Attribute} has assigned index. <tt>AttributeBuilder</tt> is responsible to
 * assign each {@link Attribute} unique index.
 *
 * @see DefaultAttributeBuilder
 *
 * @author Alexey Stashok
 */
public interface AttributeBuilder {

    /**
     * <p>
     * The default {@link AttributeBuilder} implementation used by all created builder instances.
     * </p>
     *
     * <p>
     * The default may be changed by setting the system property
     * <code>org.glassfish.grizzly.DEFAULT_ATTRIBUTE_BUILDER</code> with the fully qualified name of the class that
     * implements the AttributeBuilder interface. Note that this class must be public and have a public no-arg constructor.
     * </p>
     */
    AttributeBuilder DEFAULT_ATTRIBUTE_BUILDER = AttributeBuilderInitializer.initBuilder();

    /**
     * Create Attribute with name
     *
     * @param <T> Type of attribute value
     * @param name attribute name
     * 
     * @return Attribute<T>
     */
    <T> Attribute<T> createAttribute(String name);

    /**
     * Create Attribute with name and default value
     *
     * @param <T> Type of attribute value
     * @param name attribute name
     * @param defaultValue attribute's default value
     *
     * @return Attribute<T>
     */
    <T> Attribute<T> createAttribute(String name, T defaultValue);


    /**
     * Create Attribute with name and initializer, which will be called, if Attribute's value is null on a AttributedObject
     *
     * @param <T> Type of attribute value
     * @param name attribute name
     * @param initializer {@link Supplier}, which will be called, if Attribute's value is null on a AttributedObject
     *
     * @return Attribute<T>
     */
    <T> Attribute<T> createAttribute(String name, Supplier<T> initializer);

    /**
     * Creates and returns new thread-safe {@link AttributeHolder}
     *
     * @return thread-safe {@link AttributeHolder}
     */
    AttributeHolder createSafeAttributeHolder();

    /**
     * Creates and returns new non thread-safe {@link AttributeHolder}
     *
     * @return non thread-safe {@link AttributeHolder}
     */
    AttributeHolder createUnsafeAttributeHolder();
}
