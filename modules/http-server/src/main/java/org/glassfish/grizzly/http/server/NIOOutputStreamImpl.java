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

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Cacheable;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.http.io.NIOOutputStream;
import org.glassfish.grizzly.http.io.OutputBuffer;

/**
 * {@link NIOOutputStream} implementation.
 *
 * @author Ryan Lubke
 * @author Alexey Stashok
 */
class NIOOutputStreamImpl extends NIOOutputStream implements Cacheable {

    private volatile OutputBuffer outputBuffer;

    // ----------------------------------------------- Methods from OutputStream

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final int b) throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        outputBuffer.writeByte(b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final byte[] b) throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        outputBuffer.write(b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final byte[] b, final int off, final int len) throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        outputBuffer.write(b, off, len);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        outputBuffer.flush();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        if (!initialized()) {
            return;
        }
        outputBuffer.close();
    }

    // ---------------------------------------------- Methods from OutputSink

    /**
     * {@inheritDoc}
     *
     * @deprecated the <code>length</code> parameter will be ignored. Pls use {@link #canWrite()}.
     */
    @Deprecated
    @Override
    public boolean canWrite(final int length) {
        if (!initialized()) {
            throw new IllegalStateException("Not initialized");
        }
        return outputBuffer.canWrite();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canWrite() {
        if (!initialized()) {
            throw new IllegalStateException("Not initialized");
        }
        return outputBuffer.canWrite();
    }

    /**
     * {@inheritDoc}
     * 
     * @deprecated the <code>length</code> parameter will be ignored. Pls. use
     * {@link #notifyCanWrite(org.glassfish.grizzly.WriteHandler)}.
     */
    @Deprecated
    @Override
    public void notifyCanWrite(final WriteHandler handler, final int length) {
        if (!initialized()) {
            throw new IllegalStateException("Not initialized");
        }
        outputBuffer.notifyCanWrite(handler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void notifyCanWrite(final WriteHandler handler) {
        if (!initialized()) {
            throw new IllegalStateException("Not initialized");
        }
        outputBuffer.notifyCanWrite(handler);
    }

    // ---------------------------------------- Methods from BinaryNIOOutputSink

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final Buffer buffer) throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        outputBuffer.writeBuffer(buffer);
    }

    // -------------------------------------------------- Methods from Cacheable

    @Override
    public void recycle() {

        outputBuffer = null;

    }

    // ---------------------------------------------------------- Public Methods

    public void setOutputBuffer(final OutputBuffer outputBuffer) {

        this.outputBuffer = outputBuffer;

    }

    private boolean initialized() {
        return outputBuffer != null;
    }
}
