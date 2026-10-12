/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly.http.ajp;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.memory.ByteBufferWrapper;
import org.junit.After;
import org.junit.Before;

public class AjpTestBase {
    int port;
    static final String LISTENER_NAME = "ajp";
    HttpServer httpServer;

    private Socket socket;

    AjpAddOn ajpAddon;

    @Before
    public void before() throws Exception {
        port = Utils.findAvailableTcpPort();
        ByteBufferWrapper.DEBUG_MODE = true;
        configureHttpServer(port);
    }

    @After
    public void after() throws IOException {
        if (socket != null) {
            socket.close();
        }
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }

    protected byte[] readFile(String name) throws IOException {
        ByteArrayOutputStream out;
        final FileInputStream stream = new FileInputStream(name);
        try {
            out = new ByteArrayOutputStream();
            byte[] read = new byte[4096];
            int count;
            while ((count = stream.read(read)) != -1) {
                out.write(read, 0, count);
            }
        } finally {
            stream.close();
        }
        return out.toByteArray();
    }

    protected ByteBuffer read(String file) throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream(file)));
        try {
            while (reader.ready()) {
                String[] line = reader.readLine().split(" ");
                int index = 0;
                while (index < 19 && index < line.length) {
                    if (!"".equals(line[index])) {
                        stream.write(Integer.parseInt(line[index], 16));
                    }
                    index++;
                }
            }
        } finally {
            reader.close();
        }

        return ByteBuffer.wrap(stream.toByteArray());
    }

    @SuppressWarnings({ "unchecked" })
    protected void send(byte[] request) throws IOException {
        if (socket == null || socket.isClosed()) {
            socket = new Socket("localhost", port);
            socket.setSoTimeout(5000);
        }
        final OutputStream os = socket.getOutputStream();
        os.write(request);
        os.flush();
    }

    protected void closeClient() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
            }

            socket = null;
        }
    }

    @SuppressWarnings({ "unchecked" })
    protected byte[] readAjpMessage() throws IOException {
        final byte[] tmpHeaderBuffer = new byte[4];

        final InputStream stream = socket.getInputStream();
        Utils.readFully(stream, tmpHeaderBuffer, 0, 4);

        if (tmpHeaderBuffer[0] != 'A' || tmpHeaderBuffer[1] != 'B') {
            throw new IllegalStateException("Incorrect protocol magic");
        }

        final int length = Utils.getShort(tmpHeaderBuffer, 2);

        final byte[] ajpMessage = new byte[4 + length];
        System.arraycopy(tmpHeaderBuffer, 0, ajpMessage, 0, 4);

        Utils.readFully(stream, ajpMessage, 4, length);

        return ajpMessage;
    }

    private void configureHttpServer(final int port) throws Exception {
        httpServer = new HttpServer();
        final NetworkListener listener = new NetworkListener(LISTENER_NAME, NetworkListener.DEFAULT_NETWORK_HOST, port);

        ajpAddon = new AjpAddOn();
        listener.registerAddOn(ajpAddon);

        httpServer.addListener(listener);
    }

    void startHttpServer(HttpHandler httpHandler, String... mappings) throws Exception {
        httpServer.getServerConfiguration().addHttpHandler(httpHandler, mappings);
        httpServer.start();
    }
}
