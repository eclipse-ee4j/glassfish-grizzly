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

public abstract class HeaderBlockHead extends HeaderBlockFragment {

    public static final byte PADDED = 0x8;

    static final Map<Integer, String> FLAG_NAMES_MAP = new HashMap<>(2);

    static {
        FLAG_NAMES_MAP.putAll(HeaderBlockFragment.FLAG_NAMES_MAP);
        FLAG_NAMES_MAP.put((int) PADDED, "PADDED");
    }

    protected int padLength;

    // ---------------------------------------------------------- Public Methods

    public int getPadLength() {
        return padLength;
    }

    public boolean isPadded() {
        return isFlagSet(PADDED);
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

        padLength = 0;
        super.recycle();
    }

    // ---------------------------------------------------------- Nested Classes

    public static abstract class HeaderBlockHeadBuilder<T extends HeaderBlockHeadBuilder> extends HeaderBlockFragmentBuilder<T> {

        protected int padLength;

        // -------------------------------------------------------- Constructors

        protected HeaderBlockHeadBuilder() {
        }

        // ------------------------------------------------------ Public Methods

        public T padded(boolean isPadded) {
            if (isPadded) {
                setFlag(PushPromiseFrame.PADDED);
            }
            return getThis();
        }

        public T padLength(int padLength) {
            this.padLength = padLength;
            return getThis();
        }

    } // END SynStreamFrameBuilder

}
