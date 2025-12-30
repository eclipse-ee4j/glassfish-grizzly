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

package org.glassfish.grizzly.filterchain;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.Processor;
import org.glassfish.grizzly.ProcessorSelector;

/**
 * {@link ProcessorSelector} implementation, which delegates processing of {@link IOEvent} to the {@link FilterChain}.
 *
 * @see ProcessorSelector
 *
 * @author Alexey Stashok
 */
public class FilterChainProcessorSelector implements ProcessorSelector {

    /**
     * {@link FilterChainBuilder}, responsible for creating {@link FilterChain} instances
     */
    protected final FilterChainBuilder builder;

    public FilterChainProcessorSelector(FilterChainBuilder builder) {
        this.builder = builder;
    }

    /**
     * Returns {@link FilterChain} instance, if it's interested in processing passed {@link IOEvent}, or <tt>null</tt>
     * otherwise.
     *
     * @param ioEvent {@link IOEvent} to process.
     * @param connection {@link Connection}, where {@link IOEvent} occurred.
     *
     * @return {@link FilterChain} instance, if it's interested in processing passed {@link IOEvent}, or <tt>null</tt>
     * otherwise.
     */
    @Override
    public Processor select(IOEvent ioEvent, Connection connection) {

        FilterChain chain = builder.build();
        if (chain.isInterested(ioEvent)) {
            return chain;
        }

        return null;
    }
}
