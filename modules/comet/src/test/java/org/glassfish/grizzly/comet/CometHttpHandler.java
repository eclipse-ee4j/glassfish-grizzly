/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2011, 2017 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.comet;

import java.io.IOException;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;

public class CometHttpHandler extends HttpHandler {
    final boolean resume;
    DefaultTestCometHandler cometHandler;
    final CometContext<String> cometContext;

    public CometHttpHandler(CometContext<String> cometContext, boolean resume) {
        this.cometContext = cometContext;
        this.resume = resume;
    }

    @Override
    public void service(Request request, Response response) throws IOException {
        cometHandler = createHandler(response);
        cometContext.addCometHandler(request, cometHandler);
    }

    public DefaultTestCometHandler createHandler(Response response) {
        return new DefaultTestCometHandler(cometContext, response, resume);
    }
}
