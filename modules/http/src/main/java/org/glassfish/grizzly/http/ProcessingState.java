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

package org.glassfish.grizzly.http;

/**
 * Maintains semantic state necessary to proper HTTP processing.
 */
public final class ProcessingState {

    /**
     * <p>
     * This flag controls the semantics of the Connection response header.
     * </p>
     */
    boolean keepAlive = false;

    /**
     * <p>
     * Indicates if an error occurred during request/response processing.
     * </p>
     */
    boolean error;

    /**
     * <p>
     * References {@link HttpContext} associated with the processing.
     * </p>
     */
    HttpContext httpContext;

    /**
     * <p>
     * Indicates that nobody is waiting for the result of the processing any more, e.g. the HTTP/2 stream the request was
     * received on has been reset. Set from the thread which found it out, and read by the thread which is about to
     * process the request.
     * </p>
     */
    private volatile boolean cancelled;

    /**
     * <p>
     * This flag indicates whether error occurred during the HTTP processing.
     * </p>
     *
     * @return <tt>true</tt>, if error occurred during the HTTP processing, or <tt>false</tt> otherwise.
     */
    public boolean isError() {
        return error;
    }

    /**
     * <p>
     * This flag indicates whether error occurred during the HTTP processing.
     * </p>
     *
     * @param error <tt>true</tt>, if error occurred during the HTTP processing, or <tt>false</tt> otherwise.
     */
    public void setError(boolean error) {
        this.error = error;
    }

    /**
     * <p>
     * Method returns <tt>true</tt> only if the connection is in keep-alive mode and there was no error occurred during the
     * packet processing.
     * </p>
     *
     * @return <tt>true</tt> only if the connection is in keep-alive mode and there was no error occurred during the packet
     * processing.
     */
    public boolean isStayAlive() {
        return keepAlive && !error;
    }

    /**
     * <p>
     * This flag controls the connection keep-alive feature.
     * </p>
     *
     * @return <tt>true</tt> if connection may work in keep-alive mode or <tt>false</tt> otherwise.
     */
    public boolean isKeepAlive() {
        return keepAlive;
    }

    /**
     * <p>
     * This flag controls the connection keep-alive feature.
     * </p>
     *
     * @param keepAlive <tt>true</tt> if connection may work in keep-alive mode or <tt>false</tt> otherwise.
     */
    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    /**
     * <p>
     * Returns {@link HttpContext} associated with the processing.
     * </p>
     *
     * @return {@link HttpContext} associated with the processing.
     */
    public HttpContext getHttpContext() {
        return httpContext;
    }

    /**
     * <p>
     * Sets the {@link HttpContext} associated with the processing.
     * </p>
     *
     * @param httpContext {@link HttpContext}.
     */
    public void setHttpContext(final HttpContext httpContext) {
        this.httpContext = httpContext;
    }

    /**
     * <p>
     * This flag indicates whether the processing has been cancelled, i.e. whoever sent the request is no longer interested
     * in the response (the HTTP/2 stream has been reset, or the connection closed). Request processing, which hasn't
     * started yet, may be skipped then.
     * </p>
     *
     * @return <tt>true</tt>, if the processing has been cancelled, or <tt>false</tt> otherwise.
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * <p>
     * Marks the processing as cancelled, see {@link #isCancelled()}.
     * </p>
     *
     * @param cancelled <tt>true</tt>, if the processing has been cancelled, or <tt>false</tt> otherwise.
     */
    public void setCancelled(final boolean cancelled) {
        this.cancelled = cancelled;
    }

    /**
     * <p>
     * Resets values to their initial states.
     * </p>
     */
    public void recycle() {
        keepAlive = false;
        error = false;
        cancelled = false;
        httpContext = null;
    }

}
