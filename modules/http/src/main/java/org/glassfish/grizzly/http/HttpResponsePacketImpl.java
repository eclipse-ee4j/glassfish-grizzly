/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly.http;

import org.glassfish.grizzly.ThreadCache;

/**
 *
 * @author oleksiys
 */
class HttpResponsePacketImpl extends HttpResponsePacket {

    private static final ThreadCache.CachedTypeIndex<HttpResponsePacketImpl> CACHE_IDX = ThreadCache.obtainIndex(HttpResponsePacketImpl.class, 16);

    public static HttpResponsePacketImpl create() {
        final HttpResponsePacketImpl httpResponseImpl = ThreadCache.takeFromCache(CACHE_IDX);
        if (httpResponseImpl != null) {
            return httpResponseImpl;
        }

        return new HttpResponsePacketImpl() {
            @Override
            public void recycle() {
                super.recycle();
                ThreadCache.putToCache(CACHE_IDX, this);
            }
        };
    }

    protected HttpResponsePacketImpl() {
    }

    @Override
    public ProcessingState getProcessingState() {
        return getRequest().getProcessingState();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void reset() {
//        headerParsingState.recycle();
//        contentParsingState.recycle();
//        isHeaderParsed = false;
        super.reset();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void recycle() {
        final HttpRequestPacket localRequest = getRequest();
        if (localRequest != null && localRequest.isExpectContent()) {
            return;
        }
        reset();
    }
}
