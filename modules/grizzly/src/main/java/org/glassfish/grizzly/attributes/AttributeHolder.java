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

import java.util.Set;

import java.util.function.Supplier;

/**
 * Interface declares common functionality for objects, which have associated {@link Attribute}s.
 *
 * @see IndexedAttributeHolder
 * @see NamedAttributeHolder
 *
 * @author Alexey Stashok
 */
public interface AttributeHolder {
    /**
     * Remove a name/value object.
     * 
     * @param name - name of an attribute
     * @return attribute which has been removed
     */
    Object removeAttribute(String name);

    /**
     * Set a name/value object.
     * 
     * @param name - name of an attribute
     * @param value - value of named attribute
     */
    void setAttribute(String name, Object value);

    /**
     * Return an object based on a name.
     * 
     * @param name - name of an attribute
     * @return - attribute value for the <tt>name</tt>, null if <tt>name</tt> does not exist in <tt>attributes</tt>
     */
    Object getAttribute(String name);

    /**
     * Return an object based on a name.
     * 
     * @param name - name of an attribute
     * @param initializer the initializer to be used to assign a default attribute value, in case it hasn't been assigned
     * @return - attribute value for the <tt>name</tt>, null if <tt>name</tt> does not exist in <tt>attributes</tt>
     *
     * @since 2.3.18
     */
    Object getAttribute(String name, Supplier initializer);

    /**
     * Return a {@link Set} of attribute names.
     *
     * @return - {@link Set} of attribute names
     */
    Set<String> getAttributeNames();

    /**
     * Clear all the attributes.
     */
    void clear();

    /**
     * Recycle <tt>AttributeHolder</tt>
     */
    void recycle();

    /**
     * Get AttributeBuilder, associated with this holder
     * 
     * @return AttributeBuilder
     */
    AttributeBuilder getAttributeBuilder();

    /**
     * If AttributeHolder supports attribute access by index - it will return an {@link IndexedAttributeAccessor}, which
     * will make {@link Attribute} access as fast as access to array element.
     *
     * @return {@link IndexedAttributeAccessor}.
     */
    IndexedAttributeAccessor getIndexedAttributeAccessor();

    /**
     * Copies attributes from this <tt>AttributeHolder</tt> to the dstAttributes.
     *
     * @param dstAttributes
     */
    void copyTo(AttributeHolder dstAttributes);

    /**
     * Copies attributes from the srcAttributes to this <tt>AttributeHolder</tt>
     *
     * @param srcAttributes
     */
    void copyFrom(AttributeHolder srcAttributes);
}
