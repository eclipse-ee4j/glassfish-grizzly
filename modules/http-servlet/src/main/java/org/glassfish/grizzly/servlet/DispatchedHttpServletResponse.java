/*
 * Copyright (c) 2022, 2026 Contributors to the Eclipse Foundation
 * Copyright (c) 2008, 2020 Oracle and/or its affiliates. All rights reserved.
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.glassfish.grizzly.servlet;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Wrapper around a <code>jakarta.servlet.http.HttpServletResponse</code> that transforms an application response object
 * (which might be the original one passed to a servlet.
 *
 * @author Bongjae Chang
 */
public class DispatchedHttpServletResponse extends HttpServletResponseWrapper {

    /**
     * Is this wrapped response the subject of an <code>include()</code> call?
     */
    private boolean included = false;

    public DispatchedHttpServletResponse(HttpServletResponse response, boolean included) {
        super(response);
        this.included = included;
        setResponse(response);
    }

    /**
     * Set the response that we are wrapping.
     *
     * @param response The new wrapped response
     */
    private void setResponse(HttpServletResponse response) {
        super.setResponse(response);
    }

    @Override
    public void setContentLength(int len) {
        if (included) {
            return;
        }
        super.setContentLength(len);
    }

    @Override
    public void setContentType(String type) {
        if (included) {
            return;
        }
        super.setContentType(type);
    }

    @Override
    public void setBufferSize(int size) {
        if (included) {
            return;
        }
        super.setBufferSize(size);
    }

    @Override
    public void reset() {
        if (included) {
            return;
        }
        super.reset();
    }

    @Override
    public void setLocale(Locale loc) {
        if (included) {
            return;
        }
        super.setLocale(loc);
    }

    @Override
    public void addCookie(Cookie cookie) {
        if (included) {
            return;
        }
        super.addCookie(cookie);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        if (included) {
            return;
        }
        super.sendError(sc, msg);
    }

    @Override
    public void sendError(int sc) throws IOException {
        if (included) {
            return;
        }
        super.sendError(sc);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        if (included) {
            return;
        }
        super.sendRedirect(location);
    }

    @Override
    public void sendRedirect(String location, int sc) throws IOException {
        if (included) {
            return;
        }
        super.sendRedirect(location, sc);
    }

    @Override
    public void sendRedirect(String location, boolean clearBuffer) throws IOException {
        if (included) {
            return;
        }
        super.sendRedirect(location, clearBuffer);
    }

    @Override
    public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException {
        if (included) {
            return;
        }
        super.sendRedirect(location, sc, clearBuffer);
    }

    @Override
    public void setDateHeader(String name, long date) {
        if (included) {
            return;
        }
        super.setDateHeader(name, date);
    }

    @Override
    public void addDateHeader(String name, long date) {
        if (included) {
            return;
        }
        super.addDateHeader(name, date);
    }

    @Override
    public void setHeader(String name, String value) {
        if (included) {
            return;
        }
        super.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        if (included) {
            return;
        }
        super.addHeader(name, value);
    }

    @Override
    public void setIntHeader(String name, int value) {
        if (included) {
            return;
        }
        super.setIntHeader(name, value);
    }

    @Override
    public void addIntHeader(String name, int value) {
        if (included) {
            return;
        }
        super.addIntHeader(name, value);
    }

    @Override
    public void setStatus(int sc) {
        if (included) {
            return;
        }
        super.setStatus(sc);
    }

    @Override
    public void setCharacterEncoding(String charEnc) {
        if (included) {
            return;
        }
        super.setCharacterEncoding(charEnc);
    }
}
