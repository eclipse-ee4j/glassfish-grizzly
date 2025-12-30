/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2011, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http.server;

import java.io.IOException;
import java.nio.CharBuffer;

import org.glassfish.grizzly.Cacheable;
import org.glassfish.grizzly.ReadHandler;
import org.glassfish.grizzly.http.io.InputBuffer;
import org.glassfish.grizzly.http.io.NIOReader;

/**
 * {@link NIOReader} implementation based on {@link InputBuffer}.
 *
 * @author Ryan Lubke
 * @author Alexey Stashok
 */
final class NIOReaderImpl extends NIOReader implements Cacheable {

    private volatile InputBuffer inputBuffer;

    // ----------------------------------------------------- Methods from Reader

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(final CharBuffer target) throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        return inputBuffer.read(target);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read() throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        return inputBuffer.readChar();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(final char[] cbuf) throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        return inputBuffer.read(cbuf, 0, cbuf.length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long skip(final long n) throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        return inputBuffer.skip(n);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean ready() throws IOException {
        return isReady();
    }

    /**
     * This {@link java.io.Reader} implementation supports marking.
     *
     * @return <code>true</code>
     */
    @Override
    public boolean markSupported() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void mark(int readAheadLimit) throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        inputBuffer.mark(readAheadLimit);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        inputBuffer.reset();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(final char[] cbuf, final int off, final int len) throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        return inputBuffer.read(cbuf, off, len);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        if (!initialized()) {
            return;
        }
        inputBuffer.close();
    }

    // --------------------------------------------- Methods from InputSource

    /**
     * {@inheritDoc}
     */
    @Override
    public void notifyAvailable(final ReadHandler handler) {
        if (!initialized()) {
            throw new IllegalStateException("Not initialized");
        }
        inputBuffer.notifyAvailable(handler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void notifyAvailable(final ReadHandler handler, final int size) {
        if (!initialized()) {
            throw new IllegalStateException("Not initialized");
        }
        inputBuffer.notifyAvailable(handler, size);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isFinished() {
        if (!initialized()) {
            throw new IllegalStateException("Not initialized");
        }
        return inputBuffer.isFinished();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readyData() {
        if (!initialized()) {
            throw new IllegalStateException("Not initialized");
        }
        return inputBuffer.availableChar();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReady() {
        return readyData() > 0;
    }

    // -------------------------------------------------- Methods from Cacheable

    /**
     * {@inheritDoc}
     */
    @Override
    public void recycle() {

        inputBuffer = null;

    }

    // ---------------------------------------------------------- Public Methods

    public void setInputBuffer(final InputBuffer inputBuffer) {

        this.inputBuffer = inputBuffer;

    }

    private boolean initialized() {
        return inputBuffer != null;
    }
}
