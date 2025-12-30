/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly.http.server.accesslog;

import java.text.SimpleDateFormat;

/**
 * Simple utility class to keep pre-configured {@link SimpleDateFormat}s around on a per-{@link Thread} basis. The
 * {@link SimpleDateFormat#clone() clone()} method will be used to generate new instances.
 *
 * @author <a href="mailto:pier@usrz.com">Pier Fumagalli</a>
 * @author <a href="http://www.usrz.com/">USRZ.com</a>
 */
@Deprecated
class SimpleDateFormatThreadLocal extends ThreadLocal<SimpleDateFormat> {

    private final SimpleDateFormat format;

    SimpleDateFormatThreadLocal(String format) {
        this.format = new SimpleDateFormat(format);
    }

    @Override
    protected SimpleDateFormat initialValue() {
        return (SimpleDateFormat) format.clone();
    }

}
