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

package org.glassfish.grizzly.servlet;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.logging.Logger;

import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.http.server.HttpServer;
import org.junit.Test;

import static java.util.logging.Level.INFO;

/**
 * {@link HttpServer} tests.
 *
 * @author Hubert Iwaniuk
 * @since Jan 22, 2009
 */
public class HttpServerTest extends HttpServerAbstractTest {

    public static int PORT = PORT();
    private static Logger logger = Grizzly.logger(HttpServerTest.class);

    @Test
    public void testAddHttpHandlerAfterStart() throws Exception {
        System.out.println("testAddHttpHandlerAfterStart");

        try {
            int port = PORT + 1;
            startHttpServer(port);
            String alias = "/1";
            WebappContext ctx = new WebappContext("Test");

            addServlet(ctx, alias);
            ctx.deploy(httpServer);
            HttpURLConnection conn = getConnection(alias, port);
            assertEquals(HttpServletResponse.SC_OK, getResponseCodeFromAlias(conn));
            assertEquals(alias, readResponse(conn));
        } finally {
            stopHttpServer();
        }
    }

    @Test
    public void testMultipleAddHttpHandlerAfterStart() throws Exception {
        System.out.println("testMultipleAddHttpHandlerAfterStart");

        try {
            int port = PORT + 2;
            startHttpServer(port);
            String[] aliases = new String[] { "/1", "/2", "/3" };
            WebappContext ctx = new WebappContext("Test");
            for (String alias : aliases) {
                addServlet(ctx, alias);
            }
            ctx.deploy(httpServer);
            for (String alias : aliases) {
                HttpURLConnection conn = getConnection(alias, port);
                assertEquals(HttpServletResponse.SC_OK, getResponseCodeFromAlias(conn));
                assertEquals(alias, readResponse(conn));
            }
        } finally {
            stopHttpServer();
        }
    }

    @Test
    public void testOverlapingAddHttpHandlerAfterStart() throws Exception {
        System.out.println("testOverlapingAddHttpHandlerAfterStart");
        try {
            int port = PORT + 3;
            startHttpServer(port);
            WebappContext ctx = new WebappContext("Test");
            String[] aliases = new String[] { "/1", "/2", "/2/1", "/1/2/3/4/5" };
            for (String alias : aliases) {
                addServlet(ctx, alias);
            }
            ctx.deploy(httpServer);
            for (String alias : aliases) {
                HttpURLConnection conn = getConnection(alias, port);
                assertEquals(HttpServletResponse.SC_OK, getResponseCodeFromAlias(conn));
                if (alias.startsWith(readResponse(conn))) {
                    assertTrue(true);
                } else {
                    assertFalse(false);
                }
            }
        } finally {
            stopHttpServer();
        }
    }

    /**
     * Test if {@link HttpServer#start} throws {@link IOException} if can't bind.
     *
     * @throws IOException Error.
     */
    @Test
    public void testStartContract() throws IOException {
        System.out.println("testStartContract");
        // lock port
        int port = PORT + 5;
        ServerSocket soc = null;
        try {
            soc = new ServerSocket(port);
        } catch (IOException e) {
            fail("Could not bind to port: " + port + ". " + e.getMessage());
        }

        System.out.println("Bound to port: " + port);

        try {
            httpServer = HttpServer.createSimpleServer(".", port);
            httpServer.getListener("grizzly").getTransport().setReuseAddress(false);
            httpServer.start();
            fail("Should throw exception that can't bind to port.");
        } catch (IOException e) {
            System.out.println("Ha got you this time.");
        } finally {
            soc.close();
            stopHttpServer();
        }
    }

//    /**
//     * Tests that {@link HttpServer} will start properly in secure mode with modified configuration.
//     *
//     * @throws IOException Not much to say here.
//     * @throws URISyntaxException Could not find keystore file.
//     * @throws GeneralSecurityException Security failure.
//     */
//    @Ignore(value = "Intermittently fails with javax.net.ssl.SSLHandshakeException: Remote host terminated the handshake")
//    public void testStartSecureWithConfiguration() throws Exception {
//        System.out.println("testStartSecureWithConfiguration");
//        URL resource = getClass().getClassLoader().getResource("test-keystore.jks");
//
//        SSLContextConfigurator sslContextConfig = new SSLContextConfigurator();
//        sslContextConfig.setKeyStorePass("changeit");
//        if (resource != null) {
//            sslContextConfig.setKeyStoreFile(new File(resource.toURI()).getAbsolutePath());
//        } else {
//            fail("Couldn't find keystore");
//        }
//        int port = PORT + 7;
//        httpServer = HttpServer.createSimpleServer(".", port);
//
//        httpServer.getListener("grizzly").setSecure(true);
//        httpServer.getListener("grizzly").setSSLEngineConfig(new SSLEngineConfigurator(sslContextConfig.createSSLContext(), false, false, false));
//
//        String encMsg = "Secured.";
//        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
//            @Override
//            public void service(Request request, Response response) {
//                response.setStatus(200);
//                try {
//                    response.getWriter().write(encMsg);
//                } catch (IOException e) {
//                    response.setStatus(500, "Server made a boo.");
//                }
//            }
//        }, "/sec");
//        try {
//            Thread.sleep(10);
//            httpServer.start();
//            Thread.sleep(10);
//        } catch (IOException e) {
//            e.printStackTrace();
//            fail("Could not bind to port: " + port + ". " + e.getMessage());
//        } catch (RuntimeException e) {
//            fail("Should be able to start in secure mode.");
//        }
//        try {
//            URL res = getClass().getClassLoader().getResource("test-truststore.jks");
//            if (res != null) {
//                URI uri = res.toURI();
//
//                SSLContextConfigurator sslClientContextConfig = new SSLContextConfigurator();
//                sslClientContextConfig.setTrustStoreFile(new File(uri).getAbsolutePath());
//                sslClientContextConfig.setTrustStorePass("changeit");
//
//                HttpsURLConnection.setDefaultSSLSocketFactory(sslClientContextConfig.createSSLContext().getSocketFactory());
//            } else {
//                fail("Couldn't find truststore");
//            }
//
//            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
//                @Override
//                public boolean verify(String s, SSLSession sslSession) {
//                    return true;
//                }
//            });
//            HttpURLConnection conn = (HttpURLConnection) new URL("https", "localhost", port, "/sec").openConnection();
//            assertEquals(HttpServletResponse.SC_OK, getResponseCodeFromAlias(conn));
//            assertEquals(encMsg, readResponse(conn));
//        } finally {
//            httpServer.shutdownNow();
//        }
//    }

    /**
     * Tests if {@link Filter} is getting destroyed on {@link HttpServer#stop}.
     *
     * @throws IOException Couldn't start {@link HttpServer}.
     */
    @Test
    public void testFilterLifecycle() throws IOException {

        int port = PORT + 8;
        httpServer = HttpServer.createSimpleServer(".", port);
        boolean init[] = new boolean[] { false };
        boolean filter[] = new boolean[] { false };
        boolean destroy[] = new boolean[] { false };
        WebappContext ctx = new WebappContext("Test");

        // Overload the test by not adding a Servlet to the webapp.
        // This means the default Servlet will be invoked in the FilterChain.
        // The default servlet should return a 404 as /foo can't be resolved.
        FilterRegistration reg = ctx.addFilter("filter", new Filter() {
            @Override
            public void init(FilterConfig filterConfig) {
                assertEquals("filter", filterConfig.getFilterName());
                init[0] = true;
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
                filter[0] = true;
                chain.doFilter(request, response);
            }

            @Override
            public void destroy() {
                destroy[0] = true;
            }
        });
        reg.addMappingForUrlPatterns(null, "/*");
        ctx.deploy(httpServer);

        httpServer.start();
        HttpURLConnection conn = (HttpURLConnection) new URL("http", "localhost", port, "/foo").openConnection();
        assertEquals(HttpServletResponse.SC_NOT_FOUND, getResponseCodeFromAlias(conn));
        ctx.undeploy();
        httpServer.shutdownNow();
        assertTrue(init[0]);
        assertTrue(filter[0]);
        assertTrue(destroy[0]);
    }

    @Test
    public void testFilterLifecycleByServletName() throws IOException {

        int port = PORT + 8;
        httpServer = HttpServer.createSimpleServer(".", port);
        boolean init[] = new boolean[] { false };
        boolean filter[] = new boolean[] { false };
        boolean destroy[] = new boolean[] { false };
        WebappContext ctx = new WebappContext("Test");

        addServlet(ctx, "/test");

        FilterRegistration reg = ctx.addFilter("filter", new Filter() {
            @Override
            public void init(FilterConfig filterConfig) {
                assertEquals("filter", filterConfig.getFilterName());
                init[0] = true;
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
                filter[0] = true;
                chain.doFilter(request, response);
            }

            @Override
            public void destroy() {
                destroy[0] = true;
            }
        });
        reg.addMappingForServletNames(null, "/test");
        ctx.deploy(httpServer);

        httpServer.start();
        HttpURLConnection conn = (HttpURLConnection) new URL("http", "localhost", port, "/test").openConnection();
        assertEquals(HttpServletResponse.SC_OK, getResponseCodeFromAlias(conn));
        ctx.undeploy();
        httpServer.shutdownNow();
        assertTrue(init[0]);
        assertTrue(filter[0]);
        assertTrue(destroy[0]);
    }

    /**
     * Test for https://grizzly.dev.java.net/issues/show_bug.cgi?id=513
     *
     * @throws IOException Fail.
     */
    @Test
    public void testAddHttpHandlerBeforeAndAfterStart() throws IOException {
        System.out.println("testAddHttpHandlerBeforeAndAfterStart");
        try {
            int port = PORT + 9;
            httpServer = HttpServer.createSimpleServer(".", port);
            WebappContext ctx = new WebappContext("Test");
            String[] aliases = new String[] { "/1" };
            for (String alias : aliases) {
                addServlet(ctx, alias);
            }
            ctx.deploy(httpServer);
            httpServer.start();
            for (String alias : aliases) {
                HttpURLConnection conn = getConnection(alias, port);
                assertEquals(HttpServletResponse.SC_OK, getResponseCodeFromAlias(conn));
                assertEquals(alias, readResponse(conn));
            }
            String context = "/ctx2";
            WebappContext ctx2 = new WebappContext("Test2", context);
            String alias = "/2";
            addServlet(ctx2, alias);
            ctx2.deploy(httpServer);

            HttpURLConnection conn = getConnection(context + alias, port);
            assertEquals(HttpServletResponse.SC_OK, getResponseCodeFromAlias(conn));
            assertEquals(alias, readResponse(conn));
        } finally {
            stopHttpServer();
        }
    }

    @Test
    public void testMultipleAddHttpHandlerBeforeStartAndOneAfter() throws IOException {
        System.out.println("testMultipleAddHttpHandlerBeforeStartAndOneAfter");
        try {
            int port = PORT + 10;
            httpServer = HttpServer.createSimpleServer(".", port);
            WebappContext ctx = new WebappContext("Test");
            String[] aliases = new String[] { "/1", "/2", "/3" };
            for (String alias : aliases) {
                addServlet(ctx, alias);
            }
            ctx.deploy(httpServer);
            httpServer.start();
            for (String alias : aliases) {
                HttpURLConnection conn = getConnection(alias, port);
                assertEquals(HttpServletResponse.SC_OK, getResponseCodeFromAlias(conn));
                assertEquals(alias, readResponse(conn));
            }
            String context = "/ctx2";
            WebappContext ctx2 = new WebappContext("Test2", context);
            String alias = "/4";
            addServlet(ctx2, alias);
            ctx2.deploy(httpServer);

            HttpURLConnection conn = getConnection(context + alias, port);
            assertEquals(HttpServletResponse.SC_OK, getResponseCodeFromAlias(conn));
            assertEquals(alias, readResponse(conn));
        } finally {
            stopHttpServer();
        }
    }

    private ServletRegistration addServlet(WebappContext ctx, String alias) {
        ServletRegistration reg = ctx.addServlet(alias, new HttpServlet() {

            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                logger.log(INFO, "{0} received request {1}", new Object[] { alias, req.getRequestURI() });
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write(alias);
            }
        });
        reg.addMapping(alias);
        return reg;
    }
}
