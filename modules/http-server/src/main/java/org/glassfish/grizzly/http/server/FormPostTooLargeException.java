/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
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

/**
 * Thrown when a form POST body exceeds the configured maximum form post size while the request parameters are being
 * parsed.
 * <p>
 * It extends {@link IllegalStateException} so that existing callers catching the latter keep working, while callers
 * that want to distinguish this condition (for example to answer with a <code>413 Payload Too Large</code> status
 * instead of a generic <code>500</code>) can catch it explicitly.
 */
public class FormPostTooLargeException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public FormPostTooLargeException(final String message) {
        super(message);
    }
}
