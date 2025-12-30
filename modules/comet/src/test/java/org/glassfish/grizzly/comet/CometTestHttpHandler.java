/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2009, 2020 Oracle and/or its affiliates. All rights reserved.
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

import org.glassfish.grizzly.comet.concurrent.DefaultConcurrentCometHandler;
import org.glassfish.grizzly.http.io.NIOOutputStream;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;

/**
 * @author Gustav Trede
 */
public class CometTestHttpHandler extends HttpHandler {
    private final boolean useConcurrentCometHandler;
    static CometContext<Byte> cometContext;
    static volatile boolean useStreaming;

    public CometTestHttpHandler(String name, boolean useHandler, int idleTimeout) {
        cometContext = CometEngine.getEngine().register(name);
        cometContext.setExpirationDelay(idleTimeout);
        useConcurrentCometHandler = useHandler;
        // this.eventsperconnect = eventsperconnect;
    }

    @Override
    public void service(Request req, Response res) {
        cometContext.addCometHandler(req, useConcurrentCometHandler ? new MyConcurrentCometHandler(cometContext, res) : new CometRequestHandler());
    }

    private void doEvent(CometEvent event, CometHandler handler) throws IOException {
        if (event.getType() == CometEvent.Type.NOTIFY) {
            final NIOOutputStream outputStream = handler.getResponse().getNIOOutputStream();
            outputStream.write((Byte) event.attachment());
            outputStream.flush();
            if (!useStreaming) {
                cometContext.resumeCometHandler(handler);
            }
        }
    }

    private class MyConcurrentCometHandler extends DefaultConcurrentCometHandler<Byte> {

        private MyConcurrentCometHandler(CometContext<Byte> context, Response response) {
            super(context, response);
        }

        @Override
        public void onEvent(CometEvent event) throws IOException {
            doEvent(event, this);
        }

        @Override
        public void onInitialize(CometEvent arg0) throws IOException {
        }

        @Override
        public void onInterrupt(CometEvent event) throws IOException {
            // new Exception().printStackTrace();
            super.onInterrupt(event);
        }

        @Override
        public void onTerminate(CometEvent event) throws IOException {
            // new Exception().printStackTrace();
            super.onTerminate(event);
        }
    }

    private class CometRequestHandler implements CometHandler<Byte> {
        private Byte attachment;
        private CometContext<Byte> context;
        private Response response;

        @Override
        public void onEvent(CometEvent event) throws IOException {
            doEvent(event, this);
        }

        public void attach(Byte attachment) {
            this.attachment = attachment;
        }

        @Override
        public void onInitialize(CometEvent event) throws IOException {
        }

        @Override
        public void onInterrupt(CometEvent event) throws IOException {
            doClose();
        }

        @Override
        public void onTerminate(CometEvent event) throws IOException {
            doClose();
        }

        private void doClose() throws IOException {
            getResponse().finish();
        }

        @Override
        public CometContext<Byte> getCometContext() {
            return context;
        }

        @Override
        public void setCometContext(final CometContext<Byte> context) {
            this.context = context;
        }

        @Override
        public void setResponse(final Response response) {
            this.response = response;
        }

        @Override
        public Response getResponse() {
            return response;
        }
    }
}
