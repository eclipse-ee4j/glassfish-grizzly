/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
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

import java.io.UnsupportedEncodingException;

import org.glassfish.grizzly.http.util.DataChunk;

/**
 * Predefined HTTP methods
 *
 * @author Alexey Stashok
 */
public final class Method {
    public enum PayloadExpectation {
        ALLOWED, NOT_ALLOWED, UNDEFINED
    }

    public static final Method OPTIONS = new Method("OPTIONS", PayloadExpectation.ALLOWED);
    public static final Method GET = new Method("GET", PayloadExpectation.UNDEFINED);
    public static final Method HEAD = new Method("HEAD", PayloadExpectation.UNDEFINED);
    public static final Method POST = new Method("POST", PayloadExpectation.ALLOWED);
    public static final Method PUT = new Method("PUT", PayloadExpectation.ALLOWED);
    public static final Method DELETE = new Method("DELETE", PayloadExpectation.UNDEFINED);
    public static final Method TRACE = new Method("TRACE", PayloadExpectation.NOT_ALLOWED);
    public static final Method CONNECT = new Method("CONNECT", PayloadExpectation.NOT_ALLOWED);
    public static final Method PATCH = new Method("PATCH", PayloadExpectation.ALLOWED);
    public static final Method PRI = new Method("PRI", PayloadExpectation.NOT_ALLOWED);

    public static Method CUSTOM(final String methodName) {
        return CUSTOM(methodName, PayloadExpectation.ALLOWED);
    }

    public static Method CUSTOM(final String methodName, final PayloadExpectation payloadExpectation) {
        return new Method(methodName, payloadExpectation);
    }

    public static Method valueOf(final DataChunk methodC) {
        if (methodC.equals(Method.GET.getMethodString())) {
            return Method.GET;
        } else if (methodC.equals(Method.POST.getMethodBytes())) {
            return Method.POST;
        } else if (methodC.equals(Method.HEAD.getMethodBytes())) {
            return Method.HEAD;
        } else if (methodC.equals(Method.PUT.getMethodBytes())) {
            return Method.PUT;
        } else if (methodC.equals(Method.DELETE.getMethodBytes())) {
            return Method.DELETE;
        } else if (methodC.equals(Method.TRACE.getMethodBytes())) {
            return Method.TRACE;
        } else if (methodC.equals(Method.CONNECT.getMethodBytes())) {
            return Method.CONNECT;
        } else if (methodC.equals(Method.OPTIONS.getMethodBytes())) {
            return Method.OPTIONS;
        } else if (methodC.equals(Method.PATCH.getMethodBytes())) {
            return Method.PATCH;
        } else if (methodC.equals(Method.PRI.getMethodBytes())) {
            return Method.PRI;
        } else {
            return CUSTOM(methodC.toString());
        }
    }

    public static Method valueOf(final String method) {
        if (method.equals(Method.GET.getMethodString())) {
            return Method.GET;
        } else if (method.equals(Method.POST.getMethodString())) {
            return Method.POST;
        } else if (method.equals(Method.HEAD.getMethodString())) {
            return Method.HEAD;
        } else if (method.equals(Method.PUT.getMethodString())) {
            return Method.PUT;
        } else if (method.equals(Method.DELETE.getMethodString())) {
            return Method.DELETE;
        } else if (method.equals(Method.TRACE.getMethodString())) {
            return Method.TRACE;
        } else if (method.equals(Method.CONNECT.getMethodString())) {
            return Method.CONNECT;
        } else if (method.equals(Method.OPTIONS.getMethodString())) {
            return Method.OPTIONS;
        } else if (method.equals(Method.PATCH.getMethodString())) {
            return Method.PATCH;
        } else if (method.equals(Method.PRI.getMethodString())) {
            return Method.PRI;
        } else {
            return CUSTOM(method);
        }
    }

    private final String methodString;
    private final byte[] methodBytes;

    private final PayloadExpectation payloadExpectation;

    private Method(final String methodString, final PayloadExpectation payloadExpectation) {
        this.methodString = methodString;
        try {
            this.methodBytes = methodString != null ? methodString.getBytes("US-ASCII") : null;
        } catch (UnsupportedEncodingException e) {
            // Should never get here
            throw new IllegalStateException(e);
        }

        this.payloadExpectation = payloadExpectation;
    }

    public String getMethodString() {
        return methodString;
    }

    public byte[] getMethodBytes() {
        return methodBytes;
    }

    public PayloadExpectation getPayloadExpectation() {
        return payloadExpectation;
    }

    @Override
    public String toString() {
        return methodString;
    }

    public boolean matchesMethod(final String method) {
        return this.methodString.equals(method);
    }
}
