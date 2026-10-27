/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly.http.server.util;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.localization.LogMessages;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.ssl.SSLBaseFilter.CertificateEvent;
import org.glassfish.grizzly.ssl.SSLSupport;
import org.glassfish.grizzly.ssl.SSLSupportImpl;

public class RequestUtils {

    private static final Logger LOGGER = Grizzly.logger(RequestUtils.class);

    public static Object populateCertificateAttribute(final Request request) {
        Object certificates = null;

        if (request.getRequest().isSecure()) {
            if (!request.getRequest().isUpgrade()) {
                // It's normal HTTP request, not upgraded one
                try {
                    request.getInputBuffer().fillFully(request.getHttpFilter().getConfiguration().getMaxBufferedPostSize());
                } catch (IOException e) {
                    throw new IllegalStateException("Can't complete SSL re-negotation", e);
                }
            }

            // the renegotiation must not wait for writes this thread holds back
            if (request.getContext().getConnection() instanceof NIOConnection) {
                ((NIOConnection) request.getContext().getConnection()).flushCorkedWrites();
            }
            GrizzlyFuture<Object[]> certFuture = new CertificateEvent(true).trigger(request.getContext());
            try {
                // TODO: make the timeout configurable
                certificates = certFuture.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "Unable to obtain certificates from peer.", e);
                }
            }
            request.setAttribute(SSLSupport.CERTIFICATE_KEY, certificates);
        }

        return certificates;
    }

    public static void populateSSLAttributes(final Request request) {
        if (request.isSecure()) {
            try {
                SSLSupport sslSupport = new SSLSupportImpl(request.getContext().getConnection());
                Object sslO = sslSupport.getCipherSuite();
                if (sslO != null) {
                    request.setAttribute(SSLSupport.CIPHER_SUITE_KEY, sslO);
                }
                sslO = sslSupport.getPeerCertificateChain(false);
                if (sslO != null) {
                    request.setAttribute(SSLSupport.CERTIFICATE_KEY, sslO);
                }
                sslO = sslSupport.getKeySize();
                if (sslO != null) {
                    request.setAttribute(SSLSupport.KEY_SIZE_KEY, sslO);
                }
                sslO = sslSupport.getSessionId();
                if (sslO != null) {
                    request.setAttribute(SSLSupport.SESSION_ID_KEY, sslO);
                }
            } catch (Exception ioe) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "Unable to populate SSL attributes", ioe);
                }
            }
        }
    }

    public static void handleSendFile(final Request request) {
        final Object f = request.getAttribute(Request.SEND_FILE_ATTR);
        if (f != null) {
            final Response response = request.getResponse();
            if (response.isCommitted()) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING, LogMessages.WARNING_GRIZZLY_HTTP_SERVER_REQUESTUTILS_SENDFILE_FAILED());
                }

                return;
            }

            final File file = (File) f;
            Long offset = (Long) request.getAttribute(Request.SEND_FILE_START_OFFSET_ATTR);
            Long len = (Long) request.getAttribute(Request.SEND_FILE_WRITE_LEN_ATTR);
            if (offset == null) {
                offset = 0L;
            }
            if (len == null) {
                len = file.length();
            }
            // let the sendfile() method suspend/resume the response.
            response.getOutputBuffer().sendfile(file, offset, len, null);
        }
    }
}
