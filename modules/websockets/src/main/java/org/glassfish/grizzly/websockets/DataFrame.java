/*
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

package org.glassfish.grizzly.websockets;

import java.io.IOException;
import java.io.OutputStream;

import org.glassfish.grizzly.utils.Charsets;

/**
 * In memory representation of a websocket frame.
 *
 * @see <a href="http://tools.ietf.org/html/draft-ietf-hybi-thewebsocketprotocol-05#section-4.3">Frame Definition</a>
 */
public class DataFrame {

    public static boolean isDataFrame(final Object o) {
        return o instanceof DataFrame;
    }

    private String payload;
    private byte[] bytes;
    private final FrameType type;
    private boolean last = true;

    public DataFrame(FrameType type) {
        this.type = type;
    }

    public DataFrame(FrameType type, String data) {
        this(type, data, true);
    }

    public DataFrame(FrameType type, String data, boolean fin) {
        this.type = type;
        setPayload(data);
        last = fin;
    }

    public DataFrame(FrameType type, byte[] data) {
        this(type, data, true);
    }

    public DataFrame(FrameType type, byte[] data, boolean fin) {
        this.type = type;
        type.setPayload(this, data);
        last = fin;
    }

    public FrameType getType() {
        return type;
    }

    public String getTextPayload() {
        return payload;
    }

    public final void setPayload(String payload) {
        this.payload = payload;
    }

    public void setPayload(byte[] bytes) {
        this.bytes = bytes;
    }

    public byte[] getBytes() {
        if (payload != null) {
            bytes = Utf8Utils.encode(Charsets.UTF8_CHARSET, payload);
        }
        return bytes;
    }

    public void toStream(final OutputStream os) throws IOException {
        if (payload != null) {
            Utf8Utils.encode(Charsets.UTF8_CHARSET, payload, os);
        }
    }

    public void respond(WebSocket socket) {
        getType().respond(socket, this);
    }

    @Override
    public String toString() {
        return new StringBuilder("DataFrame").append("{").append("last=").append(last).append(", type=").append(type.getClass().getSimpleName())
                .append(", payload='").append(getTextPayload()).append('\'').append(", bytes=").append(Utils.toString(getBytes())).append('}').toString();
    }

    public boolean isLast() {
        return last;
    }

    public void setLast(boolean last) {
        this.last = last;
    }
}
