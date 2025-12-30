/*
 * Copyright (c) 2010, 2025 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.websockets;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.http.util.MimeHeaders;

/**
 * @author Justin Lee
 */
public abstract class HandShake {
    private HttpRequestPacket.Builder builder;
    private boolean secure;
    private String origin;
    private String serverHostName;
    private int port = 80;
    private String resourcePath;
    private String location;
    // private final Map<String, String[]> queryParams = new TreeMap<String, String[]>();
    private List<String> subProtocol = new ArrayList<>();
    private List<Extension> extensions = new ArrayList<>(); // client extensions

    public HandShake(URI url) {
        builder = HttpRequestPacket.builder().protocol(Protocol.HTTP_1_1).method(Method.GET).header(Header.Connection, "Upgrade").upgrade("WebSocket");

        resourcePath = url.getPath();
        if ("".equals(resourcePath)) {
            resourcePath = "/";
        }
        if (url.getQuery() != null) {
            resourcePath += "?" + url.getQuery();
        }
        serverHostName = url.getHost();
        secure = "wss".equals(url.getScheme());
        port = url.getPort();

        final StringBuilder sb = new StringBuilder(32).append(getScheme()).append("://").append(url.getHost());
        origin = appendPort(sb).toString().toLowerCase(Locale.ENGLISH);

        buildLocation();
    }

    public HandShake(HttpRequestPacket request) {
        MimeHeaders mimeHeaders = request.getHeaders();
        checkForHeader(request, "Upgrade", "WebSocket");
        checkForHeader(request, "Connection", "Upgrade");
        origin = readHeader(mimeHeaders, Constants.SEC_WS_ORIGIN_HEADER);
        if (origin == null) {
            origin = readHeader(mimeHeaders, Constants.ORIGIN_HEADER);
        }
        determineHostAndPort(mimeHeaders);
        subProtocol = split(mimeHeaders.getHeader(Constants.SEC_WS_PROTOCOL_HEADER));
        if (serverHostName == null) {
            throw new HandshakeException("Missing required headers for WebSocket negotiation");
        }
        resourcePath = request.getRequestURI();
        final String queryString = request.getQueryString();
        if (queryString != null) {
            if (!queryString.isEmpty()) {
                resourcePath += "?" + queryString;
            }
//            Parameters queryParameters = new Parameters();
//            queryParameters.processParameters(queryString);
//            final Set<String> names = queryParameters.getParameterNames();
//            for (String name : names) {
//                queryParams.put(name, queryParameters.getParameterValues(name));
//            }
        }
        buildLocation();
    }

    protected abstract int getVersion();

    protected final void buildLocation() {
        final StringBuilder sb = new StringBuilder(getScheme() + "://" + serverHostName);
        appendPort(sb);
        if (resourcePath == null || !resourcePath.startsWith("/") && !"".equals(resourcePath)) {
            sb.append("/");
        }
        sb.append(resourcePath);
        location = sb.toString();
    }

    public String getLocation() {
        return location;
    }

    public String getOrigin() {
        return origin;
    }

    public int getPort() {
        return port;
    }

    public String getResourcePath() {
        return resourcePath;
    }

    public boolean isSecure() {
        return secure;
    }

    public String getServerHostName() {
        return serverHostName;
    }

    public List<String> getSubProtocol() {
        return subProtocol;
    }

    public void setSubProtocol(List<String> subProtocol) {
        this.subProtocol = subProtocol;
    }

    private void sanitize(List<String> strings) {
        if (strings != null) {
            for (int i = 0; i < strings.size(); i++) {
                strings.set(i, strings.get(i) == null ? null : strings.get(i).trim());
            }
        }
    }

    public List<Extension> getExtensions() {
        return extensions;
    }

    public void setExtensions(List<Extension> extensions) {
        this.extensions = extensions;
    }

    protected final String joinExtensions(List<Extension> extensions) {
        StringBuilder sb = new StringBuilder();
        for (Extension e : extensions) {
            if (sb.length() != 0) {
                sb.append(", ");
            }
            sb.append(e.toString());
        }
        return sb.toString();
    }

    protected String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String s : values) {
            if (builder.length() != 0) {
                builder.append(", ");
            }
            builder.append(s);
        }
        return builder.toString();
    }

    private void checkForHeader(HttpHeader headers, String header, String validValue) {
        validate(header, validValue, headers.getHeader(header));
    }

    private void checkForSubProtocol(final HttpResponsePacket headers) {
        if (!getSubProtocol().isEmpty()) {
            final String value = headers.getHeader(Constants.SEC_WS_PROTOCOL_HEADER);
            boolean found = false;
            if (value != null) {
                final Set<String> validValues = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                final List<String> acceptedSubProtocol = new ArrayList<>(validValues.size());

                validValues.addAll(getSubProtocol());

                if (value.contains(",")) {
                    for (String part : value.split(",")) {
                        final String protocol = part.trim();
                        if (validValues.contains(protocol)) {
                            acceptedSubProtocol.add(protocol);
                        }
                    }
                } else if (validValues.contains(value)) {
                    acceptedSubProtocol.add(value);
                }

                if (!acceptedSubProtocol.isEmpty()) {
                    found = true;
                    setSubProtocol(acceptedSubProtocol);
                }
            }
            if (!found) {
                throw new HandshakeException(String.format("Invalid Sec-WebSocket-Protocol header returned: '%s'", value));
            }
        }
    }

    private void validate(final String header, final String validValue, final String value) {
        boolean found = false;

        if (value != null) {
            if (value.contains(",")) {
                for (String part : value.split(",")) {
                    if (part.trim().equalsIgnoreCase(validValue)) {
                        found = true;
                        break;
                    }
                }
            } else {
                found = value.equalsIgnoreCase(validValue);
            }
        }

        if (!found) {
            throw new HandshakeException(String.format("Invalid %s header returned: '%s'", header, value));
        }
    }

    /**
     * Reads the header value using UTF-8 encoding
     */
    private String readHeader(MimeHeaders headers, final String name) {
        final DataChunk value = headers.getValue(name);
        return value == null || value.isNull() ? null : value.toString();
    }

    private void determineHostAndPort(MimeHeaders headers) {
        String header;
        header = readHeader(headers, "host");
        final int i = header == null ? -1 : header.indexOf(":");
        if (i == -1) {
            serverHostName = header;
            port = 80;
        } else {
            assert header != null;

            serverHostName = header.substring(0, i);
            port = Integer.parseInt(header.substring(i + 1));
        }
    }

    public HttpContent composeHeaders() {
        String host = getServerHostName();
        if (port != -1 && port != 80 && port != 443) {
            host += ":" + getPort();
        }
        builder.uri(getResourcePath()).header(Header.Host, host);
        if (!getSubProtocol().isEmpty()) {
            builder.header(Constants.SEC_WS_PROTOCOL_HEADER, join(getSubProtocol()));
        } else {
            builder.removeHeader(Constants.SEC_WS_EXTENSIONS_HEADER);
        }

        if (!getSubProtocol().isEmpty()) {
            builder.header(Constants.SEC_WS_PROTOCOL_HEADER, join(getSubProtocol()));
        }
        return HttpContent.builder(builder.build()).build();
    }

    public void validateServerResponse(HttpResponsePacket headers) {
        if (Constants.RESPONSE_CODE_VALUE != headers.getStatus()) {
            throw new HandshakeException(
                    String.format("Response code was not %s: %s %s", Constants.RESPONSE_CODE_VALUE, headers.getStatus(), headers.getReasonPhrase()));
        }
        checkForHeader(headers, Constants.UPGRADE, Constants.WEBSOCKET);
        checkForHeader(headers, Constants.CONNECTION, Constants.UPGRADE);
        checkForSubProtocol(headers);
    }

    public void respond(final FilterChainContext ctx, final WebSocketApplication application, final HttpResponsePacket response) {

        response.setProtocol(Protocol.HTTP_1_1);
        response.setStatus(HttpStatus.SWITCHING_PROTOCOLS_101);
        response.setHeader("Upgrade", "websocket");
        response.setHeader("Connection", "Upgrade");
        setHeaders(response);
        if (!getSubProtocol().isEmpty()) {
            response.setHeader(Constants.SEC_WS_PROTOCOL_HEADER, join(application.getSupportedProtocols(getSubProtocol())));
        }
        if (!application.getSupportedExtensions().isEmpty() && !getExtensions().isEmpty()) {
            List<Extension> intersection = intersection(getExtensions(), application.getSupportedExtensions());
            if (!intersection.isEmpty()) {
                application.onExtensionNegotiation(intersection);
                response.setHeader(Constants.SEC_WS_EXTENSIONS_HEADER, joinExtensions(intersection));
            }
        }

        ctx.write(HttpContent.builder(response).build());
    }

    protected abstract void setHeaders(HttpResponsePacket response);

    protected final List<String> split(final String header) {
        if (header == null) {
            return Collections.emptyList();
        } else {
            final List<String> list = Arrays.asList(header.split(","));
            sanitize(list);
            return list;
        }
    }

    protected List<Extension> intersection(List<Extension> requested, List<Extension> supported) {
        List<Extension> intersection = new ArrayList<>(supported.size());
        for (Extension e : requested) {
            for (Extension s : supported) {
                if (e.getName().equals(s.getName())) {
                    intersection.add(e);
                    break;
                }
            }
        }
        return intersection;
    }

    protected final List<Extension> parseExtensionsHeader(final String headerValue) {
        List<Extension> resolved = new ArrayList<>();
        String[] parts = headerValue.split(",");
        for (String part : parts) {
            int idx = part.indexOf(';');
            if (idx < 0) {
                resolved.add(new Extension(part.trim()));
            } else {
                String name = part.substring(0, idx);
                Extension e = new Extension(name.trim());
                resolved.add(e);
                parseParameters(part.substring(idx + 1).trim(), e.getParameters());
            }
        }
        return resolved;
    }

    protected final void parseParameters(String parameterString, List<Extension.Parameter> parameters) {
        String[] parts = parameterString.split(";");
        for (String part : parts) {
            int idx = part.indexOf('=');
            if (idx < 0) {
                parameters.add(new Extension.Parameter(part.trim(), null));
            } else {
                parameters.add(new Extension.Parameter(part.substring(0, idx).trim(), part.substring(idx + 1).trim()));
            }
        }
    }

    public void initiate(FilterChainContext ctx) throws IOException {
        ctx.write(composeHeaders());
    }

    private StringBuilder appendPort(StringBuilder builder) {
        if (isSecure()) {
            if (port != 443 && port != -1) {
                builder.append(':').append(port);
            }
        } else {
            if (port != 80 && port != -1) {
                builder.append(':').append(port);
            }
        }
        return builder;
    }

    private String getScheme() {
        return isSecure() ? "wss" : "ws";
    }
}
