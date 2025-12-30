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
 * The interface declares, that {@link AttributeHolder} supports indexed {@link Attribute} access.
 *
 * @see AttributeHolder
 */
public interface IndexedAttributeAccessor {

    /**
     * Internal method for dynamic attribute support. Return the value of the attribute by index.
     * 
     * @param index the attribute index
     * @return the value of the attribute by index
     */
    Object getAttribute(int index);

    /**
     * Internal method for dynamic attribute support. Return the value of the attribute by index. If the attribute with such
     * index is not set, set it to the default value, using the <tt>initializer</tt>, and return the default.
     * 
     * @param index the attribute index
     * @param initializer the default value {@link Supplier}
     * @return the value of the attribute by index
     * @since 2.3.18
     */
    Object getAttribute(int index, Supplier initializer);

    /**
     * Internal method for dynamic attribute support. Set the attribute with the index to value.
     * 
     * @param index the attribute index
     * @param value the value
     */
    void setAttribute(int index, Object value);

    /**
     * Internal method for dynamic attribute support. Removes the attribute with the index and returns its previous value.
     *
     * @param index the attribute index
     * @return the previous value associated with the attribute
     * @since 2.3.18
     */
    Object removeAttribute(int index);

}
