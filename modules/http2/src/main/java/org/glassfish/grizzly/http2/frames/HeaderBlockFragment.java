/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2014, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http2.frames;

import java.util.HashMap;
import java.util.Map;

import org.glassfish.grizzly.Buffer;

public abstract class HeaderBlockFragment extends Http2Frame {

    public static final byte END_HEADERS = 0x4;

    static final Map<Integer, String> FLAG_NAMES_MAP = new HashMap<>(2);

    static {
        FLAG_NAMES_MAP.put((int) END_HEADERS, "END_HEADERS");
    }

    protected Buffer compressedHeaders;
    protected boolean truncated;

    // ---------------------------------------------------------- Public Methods

    public Buffer getCompressedHeaders() {
        return compressedHeaders;
    }

    public boolean isEndHeaders() {
        return isFlagSet(END_HEADERS);
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated() {
        truncated = true;
    }

    public Buffer takePayload() {
        final Buffer payload = compressedHeaders;
        compressedHeaders = null;

        return payload;
    }

    @Override
    protected Map<Integer, String> getFlagNamesMap() {
        return FLAG_NAMES_MAP;
    }

    // -------------------------------------------------- Methods from Cacheable

    @Override
    public void recycle() {
        if (DONT_RECYCLE) {
            return;
        }

        truncated = false;
        compressedHeaders = null;
        super.recycle();
    }

    // ---------------------------------------------------------- Nested Classes

    public static abstract class HeaderBlockFragmentBuilder<T extends HeaderBlockFragmentBuilder> extends Http2FrameBuilder<T> {

        protected Buffer compressedHeaders;

        // -------------------------------------------------------- Constructors

        protected HeaderBlockFragmentBuilder() {
        }

        // ------------------------------------------------------ Public Methods

        public T endHeaders(boolean endHeaders) {
            if (endHeaders) {
                setFlag(END_HEADERS);
            }
            return getThis();
        }

        /**
         * Sets compressed headers buffer.
         *
         * @param compressedHeaders {@link Buffer} containing compressed headers
         * @see CompressedHeadersBuilder
         */
        @SuppressWarnings("unchecked")
        public T compressedHeaders(final Buffer compressedHeaders) {
            this.compressedHeaders = compressedHeaders;

            return getThis();
        }

    } // END SynStreamFrameBuilder

}
