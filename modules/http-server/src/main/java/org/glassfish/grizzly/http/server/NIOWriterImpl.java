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

import org.glassfish.grizzly.Cacheable;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.http.io.NIOWriter;
import org.glassfish.grizzly.http.io.OutputBuffer;

/**
 * {@link NIOWriter} implementation.
 *
 * @author Ryan Lubke
 * @author Alexey Stashok
 */
final class NIOWriterImpl extends NIOWriter implements Cacheable {

    private volatile OutputBuffer outputBuffer;

    // ----------------------------------------------------- Methods from Writer

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(int c) throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        outputBuffer.writeChar(c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(char[] cbuf) throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        outputBuffer.write(cbuf);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        outputBuffer.write(cbuf, off, len);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(String str) throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        outputBuffer.write(str);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(String str, int off, int len) throws IOException {
        if (!initialized()) {
            throw new IOException("Not initialized");
        }
        outputBuffer.write(str, off, len);
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
     * @param length specifies the number of characters that require writing
     *
     * @return <code>true</code> if a write to this <code>OutputSink</code> will succeed, otherwise returns
     * <code>false</code>.
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
     * @return <code>true</code> if a write to this <code>OutputSink</code> will succeed, otherwise returns
     * <code>false</code>.
     *
     * @since 2.3
     */
    @Override
    public boolean canWrite() {
        if (!initialized()) {
            throw new IllegalStateException("Not initialized");
        }
        return outputBuffer.canWrite();
    }

    /**
     * Instructs the <code>OutputSink</code> to invoke the provided {@link WriteHandler} when it is possible to write
     * <code>length</code> characters.
     *
     * @param handler the {@link WriteHandler} that should be notified when it's possible to write <code>length</code>
     * characters.
     * @param length the number of characters that require writing.
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
     * Instructs the <code>OutputSink</code> to invoke the provided {@link WriteHandler} when it is possible to write more
     * characters.
     *
     * @param handler the {@link WriteHandler} that should be notified when it's possible to write more characters.
     *
     * @since 2.3
     */
    @Override
    public void notifyCanWrite(final WriteHandler handler) {
        if (!initialized()) {
            throw new IllegalStateException("Not initialized");
        }
        outputBuffer.notifyCanWrite(handler);
    }

    // -------------------------------------------------- Methods from Cacheable

    /**
     * {@inheritDoc}
     */
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
