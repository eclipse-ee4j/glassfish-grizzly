/*
 * Copyright (c) 2022, 2026 Contributors to Eclipse Foundation. All rights reserved.
 * Copyright (c) 2010, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Cacheable;
import org.glassfish.grizzly.http.util.CookieSerializerUtils;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.utils.Charsets;

/**
 *
 * Creates a cookie, a small amount of information sent by a servlet to a Web browser, saved by the browser, and later
 * sent back to the server. A cookie's value can uniquely identify a client, so cookies are commonly used for session
 * management.
 *
 * <p>
 * A cookie has a name, a single value, and optional attributes such as a comment, path and domain qualifiers, a maximum
 * age, and a version number. Some Web browsers have bugs in how they handle the optional attributes, so use them
 * sparingly to improve the interoperability of your servlets.
 *
 * The browser is expected to support 20 cookies for each Web server, 300 cookies total, and may limit cookie size to 4
 * KB each.
 *
 * <p>
 * Cookies affect the caching of the Web pages that use them. HTTP 1.0 does not cache pages that use cookies created
 * with this class. This class does not support the cache control defined with HTTP 1.1.
 *
 * <p>
 * This class supports both the Version 0 (by Netscape) and Version 1 (by RFC 2109) cookie specifications. By default,
 * cookies are created using Version 0 to ensure the best interoperability.
 *
 *
 * @author Various
 * @version $Version$
 *
 */
public class Cookie implements Cloneable, Cacheable {

    public static final int UNSET = Integer.MIN_VALUE;

    private static final String SAME_SITE_ATTR = "SameSite";

    /**
     * Attributes with a dedicated property, which {@link #setAttribute(String, String)} does not accept.
     */
    private static final Set<String> RESERVED_ATTRIBUTES = Collections.unmodifiableSet(newCaseInsensitiveSet("Comment", "Domain", "Max-Age",
            "Expires", "Path", "Secure", "HttpOnly", "Version"));

    //
    // The value of the cookie itself.
    //

    protected String name; // NAME= ... "$Name" style is reserved
    protected String value; // value of NAME

    //
    // Attributes encoded in the header's cookie fields.
    //

    protected String comment; // ;Comment=VALUE ... describes cookie's use
    // ;Discard ... implied by maxAge < 0
    protected String domain; // ;Domain=VALUE ... domain that sees cookie
    protected int maxAge = -1; // ;Max-Age=VALUE ... cookies auto-expire
    protected String path; // ;Path=VALUE ... URLs that see the cookie
    protected boolean secure; // ;Secure ... e.g. use SSL
    protected int version = UNSET; // ;Version=1 ... means RFC 2109++ style

    protected boolean isHttpOnly; // Is HTTP only feature, which is not part of the spec
    protected Map<String, String> attributes; // ;SameSite=VALUE, ;Partitioned ... further attributes, case-insensitive
    protected LazyCookieState lazyCookieState;
    protected boolean usingLazyCookieState;

    protected Cookie() {
    }

    /**
     * Constructs a cookie with a specified name and value.
     *
     * <p>
     * The name must conform to RFC 2109. That means it can contain only ASCII alphanumeric characters and cannot contain
     * commas, semicolons, or white space or begin with a $ character.
     *
     * <p>
     * The value can be anything the server chooses to send. Its value is probably of interest only to the server.
     *
     * <p>
     * By default, cookies are created according to the Netscape cookie specification. The version can be changed with the
     * <code>setVersion</code> method.
     *
     *
     * @param name a <code>String</code> specifying the name of the cookie
     *
     * @param value a <code>String</code> specifying the value of the cookie
     *
     * @throws IllegalArgumentException if the cookie name contains illegal characters (for example, a comma, space, or
     * semicolon) or it is one of the tokens reserved for use by the cookie protocol
     * @see #setValue
     * @see #setVersion
     *
     */
    public Cookie(final String name, final String value) {
        this.name = name;
        this.value = value;
    }

    /**
     *
     * Specifies a comment that describes a cookie's purpose. The comment is useful if the browser presents the cookie to
     * the user. Comments are not supported by Netscape Version 0 cookies.
     *
     * @param purpose a <code>String</code> specifying the comment to display to the user
     *
     * @see #getComment
     *
     */

    public void setComment(String purpose) {
        comment = purpose;
    }

    /**
     * Returns the comment describing the purpose of this cookie, or <code>null</code> if the cookie has no comment.
     *
     * @return a <code>String</code> containing the comment, or <code>null</code> if none
     *
     * @see #setComment
     *
     */

    public String getComment() {
        if (comment == null && usingLazyCookieState) {
            final String commentStr = lazyCookieState.getComment().toString(Charsets.ASCII_CHARSET);
            comment = version == 1 ? unescape(commentStr) : null;
        }
        return comment;
    }

    /**
     *
     * Specifies the domain within which this cookie should be presented.
     *
     * <p>
     * The form of the domain name is specified by RFC 2109. A domain name begins with a dot (<code>.foo.com</code>) and
     * means that the cookie is visible to servers in a specified Domain Name System (DNS) zone (for example,
     * <code>www.foo.com</code>, but not <code>a.b.foo.com</code>). By default, cookies are only returned to the server that
     * sent them.
     *
     *
     * @param pattern a <code>String</code> containing the domain name within which this cookie is visible; form is
     * according to RFC 2109
     *
     * @see #getDomain
     *
     */

    public void setDomain(String pattern) {
        if (pattern != null) {
            domain = pattern.toLowerCase(); // IE allegedly needs this
        }
    }

    /**
     * Returns the domain name set for this cookie. The form of the domain name is set by RFC 2109.
     *
     * @return a <code>String</code> containing the domain name
     *
     * @see #setDomain
     *
     */

    public String getDomain() {
        if (domain == null && usingLazyCookieState) {
            final String domainStr = lazyCookieState.getDomain().toString(Charsets.ASCII_CHARSET);
            if (domainStr != null) {
                domain = unescape(domainStr);
            }
        }
        return domain;
    }

    /**
     * Sets the maximum age of the cookie in seconds.
     *
     * <p>
     * A positive value indicates that the cookie will expire after that many seconds have passed. Note that the value is
     * the <i>maximum</i> age when the cookie will expire, not the cookie's current age.
     *
     * <p>
     * A negative value means that the cookie is not stored persistently and will be deleted when the Web browser exits. A
     * zero value causes the cookie to be deleted.
     *
     * @param expiry an integer specifying the maximum age of the cookie in seconds; if negative, means the cookie is not
     * stored; if zero, deletes the cookie
     *
     *
     * @see #getMaxAge
     *
     */

    public void setMaxAge(int expiry) {
        maxAge = expiry;
    }

    /**
     * Returns the maximum age of the cookie, specified in seconds, By default, <code>-1</code> indicating the cookie will
     * persist until browser shutdown.
     *
     *
     * @return an integer specifying the maximum age of the cookie in seconds; if negative, means the cookie persists until
     * browser shutdown
     *
     *
     * @see #setMaxAge
     *
     */

    public int getMaxAge() {
        return maxAge;
    }

    /**
     * Specifies a path for the cookie to which the client should return the cookie.
     *
     * <p>
     * The cookie is visible to all the pages in the directory you specify, and all the pages in that directory's
     * subdirectories. A cookie's path must include the servlet that set the cookie, for example, <i>/catalog</i>, which
     * makes the cookie visible to all directories on the server under <i>/catalog</i>.
     *
     * <p>
     * Consult RFC 2109 (available on the Internet) for more information on setting path names for cookies.
     *
     *
     * @param uri a <code>String</code> specifying a path
     *
     *
     * @see #getPath
     *
     */

    public void setPath(String uri) {
        path = uri;
    }

    /**
     * Returns the path on the server to which the browser returns this cookie. The cookie is visible to all subpaths on the
     * server.
     *
     *
     * @return a <code>String</code> specifying a path that contains a servlet name, for example, <i>/catalog</i>
     *
     * @see #setPath
     *
     */

    public String getPath() {
        if (path == null && usingLazyCookieState) {
            path = unescape(lazyCookieState.getPath().toString(Charsets.ASCII_CHARSET));
        }
        return path;
    }

    /**
     * Indicates to the browser whether the cookie should only be sent using a secure protocol, such as HTTPS or SSL.
     *
     * <p>
     * The default value is <tt>false</tt>.
     *
     * @param flag if <tt>true</tt>, sends the cookie from the browser to the server only when using a secure protocol; if
     * <tt>false</tt>, sent on any protocol
     *
     * @see #isSecure
     *
     */

    public void setSecure(boolean flag) {
        secure = flag;
    }

    /**
     * Returns <tt>true</tt> if the browser is sending cookies only over a secure protocol, or <tt>false</tt> if the browser
     * can send cookies using any protocol.
     *
     * @return <tt>true</tt> if the browser uses a secure protocol; otherwise, <tt>true</tt>
     *
     * @see #setSecure
     *
     */

    public boolean isSecure() {
        return secure;
    }

    /**
     * Returns the name of the cookie. The name cannot be changed after creation.
     *
     * @return a <code>String</code> specifying the cookie's name
     *
     */

    public String getName() {
        if (name == null && usingLazyCookieState) {
            name = lazyCookieState.getName().toString(Charsets.ASCII_CHARSET);
        }
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     *
     * Assigns a new value to a cookie after the cookie is created. If you use a binary value, you may want to use BASE64
     * encoding.
     *
     * <p>
     * With Version 0 cookies, values should not contain white space, brackets, parentheses, equals signs, commas, double
     * quotes, slashes, question marks, at signs, colons, and semicolons. Empty values may not behave the same way on all
     * browsers.
     *
     * @param newValue a <code>String</code> specifying the new value
     *
     *
     * @see #getValue
     * @see Cookie
     *
     */

    public void setValue(String newValue) {
        value = newValue;
    }

    /**
     * Returns the value of the cookie.
     *
     * @return a <code>String</code> containing the cookie's present value
     *
     * @see #setValue
     * @see Cookie
     *
     */

    public String getValue() {
        if (value == null && usingLazyCookieState) {
            value = unescape(lazyCookieState.getValue().toString(Charsets.ASCII_CHARSET));
        }
        return value;
    }

    /**
     * Returns the version of the protocol this cookie complies with. Version 1 complies with RFC 2109, and version 0
     * complies with the original cookie specification drafted by Netscape. Cookies provided by a browser use and identify
     * the browser's cookie version.
     *
     *
     * @return 0 if the cookie complies with the original Netscape specification; 1 if the cookie complies with RFC 2109
     *
     * @see #setVersion
     *
     */

    public int getVersion() {
        return version;
    }

    /**
     * Sets the version of the cookie protocol this cookie complies with. Version 0 complies with the original Netscape
     * cookie specification. Version 1 complies with RFC 2109.
     *
     * <p>
     * Since RFC 2109 is still somewhat new, consider version 1 as experimental; do not use it yet on production sites.
     *
     *
     * @param v 0 if the cookie should comply with the original Netscape specification; 1 if the cookie should comply with
     * RFC 2109
     *
     * @see #getVersion
     *
     */

    public void setVersion(int v) {
        if (v < 0 || v > 1) {
            throw new IllegalArgumentException("Illegal Cookie Version");
        }
        version = v;
    }

    public boolean isVersionSet() {
        return version != UNSET;
    }

    /**
     * HttpOnly feature is used in server->client communication only to let client know, that the cookie can not be accessed
     * on the client-side (script etc).
     *
     * Returns <tt>true</tt> if this cookie is HTTP only, or <tt>false</tt> otherwise.
     * 
     * @return <tt>true</tt> if this cookie is HTTP only, or <tt>false</tt> otherwise.
     */
    public boolean isHttpOnly() {
        return isHttpOnly;
    }

    /**
     * HttpOnly feature is used in server->client communication only to let client know, that the cookie can not be accessed
     * on the client-side (script etc).
     *
     * @param isHttpOnly <tt>true</tt> if this cookie is HTTP only, or <tt>false</tt> otherwise.
     */
    public void setHttpOnly(boolean isHttpOnly) {
        this.isHttpOnly = isHttpOnly;
    }

    /**
     * Sets a cookie attribute that has no dedicated property, e.g. <code>SameSite</code> or <code>Partitioned</code>. An
     * empty value renders the attribute without a value (<code>; Partitioned</code>), <code>null</code> removes it.
     *
     * @param name the attribute name, case-insensitive
     * @param value the attribute value, or <code>null</code> to remove the attribute
     *
     * @throws IllegalArgumentException if the name is empty, or one of the attributes with a dedicated property (Comment,
     * Domain, Max-Age, Expires, Path, Secure, HttpOnly, Version)
     */
    public void setAttribute(String name, String value) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Cookie attribute name must not be empty");
        }
        if (isReservedAttribute(name)) {
            throw new IllegalArgumentException("Cookie attribute " + name + " has a dedicated property");
        }
        if (value == null) {
            if (attributes != null) {
                attributes.remove(name);
            }
            return;
        }
        if (attributes == null) {
            attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        }
        attributes.put(name, value);
    }

    /**
     * @param name the attribute name, case-insensitive
     * @return the value of the attribute set via {@link #setAttribute(String, String)}, or <code>null</code>
     */
    public String getAttribute(String name) {
        return getAttributes().get(name);
    }

    /**
     * @return the attributes set via {@link #setAttribute(String, String)}, never <code>null</code>
     */
    public Map<String, String> getAttributes() {
        return attributes == null ? Collections.emptyMap() : Collections.unmodifiableMap(attributes);
    }

    /**
     * Sets the <code>SameSite</code> attribute, e.g. <code>Strict</code>, <code>Lax</code> or <code>None</code>.
     *
     * @param sameSite the attribute value, or <code>null</code> to remove the attribute
     */
    public void setSameSite(String sameSite) {
        setAttribute(SAME_SITE_ATTR, sameSite);
    }

    /**
     * @return the <code>SameSite</code> attribute, or <code>null</code> if not set
     */
    public String getSameSite() {
        return getAttribute(SAME_SITE_ATTR);
    }

    /**
     * @param name an attribute name
     * @return <code>true</code> if the attribute has a dedicated property and is serialized from it
     */
    public static boolean isReservedAttribute(String name) {
        return RESERVED_ATTRIBUTES.contains(name);
    }

    private static Set<String> newCaseInsensitiveSet(String... names) {
        Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Collections.addAll(set, names);
        return set;
    }

    public String asServerCookieString() {
        final StringBuilder sb = new StringBuilder();
        CookieSerializerUtils.serializeServerCookie(sb, this);
        return sb.toString();
    }

    public Buffer asServerCookieBuffer() {
        return asServerCookieBuffer(null);
    }

    public Buffer asServerCookieBuffer(MemoryManager memoryManager) {
        if (memoryManager == null) {
            memoryManager = MemoryManager.DEFAULT_MEMORY_MANAGER;
        }

        final Buffer buffer = memoryManager.allocate(4096);
        CookieSerializerUtils.serializeServerCookie(buffer, this);
        buffer.trim();

        return buffer;
    }

    public String asClientCookieString() {
        final StringBuilder sb = new StringBuilder();
        CookieSerializerUtils.serializeClientCookies(sb, this);
        return sb.toString();
    }

    public Buffer asClientCookieBuffer() {
        return asClientCookieBuffer(null);
    }

    public Buffer asClientCookieBuffer(MemoryManager memoryManager) {
        if (memoryManager == null) {
            memoryManager = MemoryManager.DEFAULT_MEMORY_MANAGER;
        }

        final Buffer buffer = memoryManager.allocate(4096);
        CookieSerializerUtils.serializeClientCookies(buffer, this);
        buffer.trim();

        return buffer;
    }

    public LazyCookieState getLazyCookieState() {
        usingLazyCookieState = true;
        if (lazyCookieState == null) {
            lazyCookieState = new LazyCookieState();
        }
        return lazyCookieState;
    }

    // -------------------- Cookie parsing tools
    /**
     * Return the header name to set the cookie, based on cookie version.
     */
    @SuppressWarnings("UnusedDeclaration")
    public String getCookieHeaderName() {
        return getCookieHeaderName(version);
    }

    /**
     * Return the header name to set the cookie, based on cookie version.
     */
    public static String getCookieHeaderName(int version) {
        // TODO Re-enable logging when RFC2965 is implemented
        // log( (version==1) ? "Set-Cookie2" : "Set-Cookie");
        if (version == 1) {
            // XXX RFC2965 not referenced in Servlet Spec
            // Set-Cookie2 is not supported by Netscape 4, 6, IE 3, 5
            // Set-Cookie2 is supported by Lynx and Opera
            // Need to check on later IE and FF releases but for now...
            // RFC2109
            return "Set-Cookie";
            // return "Set-Cookie2";
        } else {
            // Old Netscape
            return "Set-Cookie";
        }
    }

    protected boolean lazyNameEquals(String name) {
        return this.name.equals(name);
    }

    // Note -- disabled for now to allow full Netscape compatibility
    // from RFC 2068, token special case characters
    //
    // private static final String tspecials = "()<>@,;:\\\"/[]?={} \t";

    private static final String tspecials = ",; ";

    protected String unescape(String s) {
        if (s == null) {
            return null;
        }
        if (s.indexOf('\\') == -1) {
            return s;
        }

        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                buf.append(c);
            } else {
                if (++i >= s.length()) {
                    // invalid escape, hence invalid cookie
                    throw new IllegalArgumentException();
                }
                c = s.charAt(i);
                buf.append(c);
            }
        }
        return buf.toString();
    }

    /**
     *
     * Overrides the standard <code>java.lang.Object.clone</code> method to return a copy of this cookie.
     *
     *
     */

    @Override
    public Object clone() throws CloneNotSupportedException {
        try {
            Cookie clone = (Cookie) super.clone();
            if (attributes != null) {
                clone.attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                clone.attributes.putAll(attributes);
            }
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    @Override
    public void recycle() {
        name = null;
        value = null;
        comment = null;
        domain = null;
        maxAge = -1;
        path = null;
        secure = false;
        version = UNSET;
        isHttpOnly = false;
        attributes = null;
        if (usingLazyCookieState) {
            usingLazyCookieState = false;
            lazyCookieState.recycle();
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Cookie{");
        sb.append("name='").append(name).append('\'');
        sb.append(", value='").append(value).append('\'');
        sb.append(", comment='").append(comment).append('\'');
        sb.append(", domain='").append(domain).append('\'');
        sb.append(", maxAge=").append(maxAge);
        sb.append(", path='").append(path).append('\'');
        sb.append(", secure=").append(secure);
        sb.append(", version=").append(version);
        sb.append(", isHttpOnly=").append(isHttpOnly);
        sb.append(", attributes=").append(attributes);
        sb.append(", usingLazyCookieState=").append(usingLazyCookieState);
        sb.append('}');
        return sb.toString();
    }
}
