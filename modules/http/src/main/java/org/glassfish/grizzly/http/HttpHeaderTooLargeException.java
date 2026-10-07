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

package org.glassfish.grizzly.http;

/**
 * Thrown while parsing an HTTP message whose header fields exceed the configured maximum header size. The server
 * answers it with <code>431 Request Header Fields Too Large</code> (RFC 6585, section 5).
 * <p>
 * Extends {@link IllegalStateException}, which the codec threw for this case before, so existing handlers keep working.
 */
public class HttpHeaderTooLargeException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public HttpHeaderTooLargeException(final String message) {
        super(message);
    }
}
