/*
 * Copyright (c) 2013, 2020 Oracle and/or its affiliates. All rights reserved.
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
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

public final class Utils {

    static final ServletInputStream NULL_SERVLET_INPUT_STREAM = new ServletInputStream() {

        @Override
        public int read() throws IOException {
            return -1;
        }

        @Override
        public boolean isFinished() {
            return true;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(final ReadListener readListener) {
            try {
                readListener.onAllDataRead();
            } catch (IOException e) {
                readListener.onError(e);
            }
        }
    };

    static final Reader NULL_READER = new Reader() {

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            return -1;
        }

        @Override
        public void close() throws IOException {
        }

    };

    static final ServletOutputStream NULL_SERVLET_OUTPUT_STREAM = new ServletOutputStream() {
        private IOException ioe1;
        private IOException ioe2;

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(final WriteListener writeListener) {
            // we don't care if ioe1 will be initialized several times because of thread racing
            if (ioe1 == null) {
                ioe1 = new IOException("Can't write to a websocket using ServletOutputStream");
            }

            writeListener.onError(ioe1);
        }

        @Override
        public void write(final int b) throws IOException {
            // we don't care if ioe2 will be initialized several times because of thread racing
            if (ioe2 == null) {
                ioe2 = new IOException("Can't write to a websocket using ServletOutputStream");
            }

            throw ioe2;
        }
    };

    static final Writer NULL_WRITER = new Writer() {
        private IOException ioe;

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            // we don't care if ioe will be initialized several times because of thread racing
            if (ioe == null) {
                ioe = new IOException("Can't write to a websocket using ServletWriter");
            }

            throw ioe;
        }

        @Override
        public void flush() throws IOException {
        }

        @Override
        public void close() throws IOException {
        }

    };

    public static byte[] toArray(long length) {
        long value = length;
        byte[] b = new byte[8];
        for (int i = 7; i >= 0 && value > 0; i--) {
            b[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return b;
    }

    public static long toLong(byte[] bytes, int start, int end) {
        long value = 0;

        if (bytes == null) {
            return value;
        }

        for (int i = start; i < end; i++) {
            value <<= 8;
            value ^= (long) bytes[i] & 0xFF;
        }
        return value;
    }

    public static List<String> toString(byte[] bytes) {
        if (bytes == null) {
            return new ArrayList<>();
        }

        return toString(bytes, 0, bytes.length);
    }

    public static List<String> toString(byte[] bytes, int start, int end) {
        List<String> list = new ArrayList<>();

        if (bytes == null) {
            return list;
        }

        for (int i = start; i < end; i++) {
            list.add(Integer.toHexString(bytes[i] & 0xFF).toUpperCase(Locale.US));
        }
        return list;
    }
}
