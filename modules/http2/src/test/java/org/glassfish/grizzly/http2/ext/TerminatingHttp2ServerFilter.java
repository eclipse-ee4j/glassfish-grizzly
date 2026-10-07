/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly.http2.ext;

import java.util.List;

import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http2.Http2Configuration;
import org.glassfish.grizzly.http2.Http2ServerFilter;
import org.glassfish.grizzly.http2.Http2Session;
import org.glassfish.grizzly.http2.frames.ErrorCode;
import org.glassfish.grizzly.http2.frames.HeadersFrame;
import org.glassfish.grizzly.http2.frames.Http2Frame;

/**
 * Server filter that ends the session as soon as the peer sends a HEADERS frame, the way a flood mitigation would.
 * <p>
 * Deliberately lives outside {@code org.glassfish.grizzly.http2}, so it compiles only against public and protected API.
 */
public class TerminatingHttp2ServerFilter extends Http2ServerFilter {

    public static final String DETAIL = "Stream creation rate exceeded";

    private final ErrorCode errorCode;

    public TerminatingHttp2ServerFilter(final Http2Configuration configuration, final ErrorCode errorCode) {
        super(configuration);
        this.errorCode = errorCode;
    }

    @Override
    protected boolean processFrames(final FilterChainContext ctx, final Http2Session http2Session, final List<Http2Frame> framesList) {
        if (framesList != null && framesList.stream().anyMatch(frame -> frame.getType() == HeadersFrame.TYPE)) {
            http2Session.terminate(errorCode, DETAIL);
            framesList.clear();
            return false;
        }
        return super.processFrames(ctx, http2Session, framesList);
    }
}
