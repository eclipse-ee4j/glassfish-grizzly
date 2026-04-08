/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2012, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.utils;

import java.util.function.Supplier;

/**
 * The object holder, which might be used for lazy object initialization.
 *
 * @author Alexey Stashok
 */
public abstract class Holder<E> {

    public static <T> Holder<T> staticHolder(final T value) {
        return new Holder<>() {

            @Override
            public T get() {
                return value;
            }
        };
    }

    public static <T> LazyHolder<T> lazyHolder(final Supplier<T> factory) {
        return new LazyHolder<>() {

            @Override
            protected T evaluate() {
                return factory.get();
            }
        };
    }

    public abstract E get();

    @Override
    public String toString() {
        final E obj = get();
        return obj != null ? "{" + obj + "}" : "{}";
    }

    public static abstract class LazyHolder<E> extends Holder<E> {
        private volatile boolean isSet;
        private E value;

        @Override
        public final E get() {
            if (isSet) {
                return value;
            }

            synchronized (this) {
                if (!isSet) {
                    value = evaluate();
                    isSet = true;
                }
            }

            return value;
        }

        protected abstract E evaluate();
    }
}
