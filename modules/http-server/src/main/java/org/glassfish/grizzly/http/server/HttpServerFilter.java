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

package org.glassfish.grizzly.http.server;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.ReadHandler;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.Filter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.ShutdownEvent;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpContext;
import org.glassfish.grizzly.http.HttpPacket;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.server.util.HtmlHelper;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.localization.LogMessages;
import org.glassfish.grizzly.monitoring.DefaultMonitoringConfig;
import org.glassfish.grizzly.monitoring.MonitoringAware;
import org.glassfish.grizzly.monitoring.MonitoringConfig;
import org.glassfish.grizzly.monitoring.MonitoringUtils;
import org.glassfish.grizzly.utils.DelayedExecutor;
import org.glassfish.grizzly.utils.Futures;

/**
 * Filter implementation to provide high-level HTTP request/response processing.
 */
public class HttpServerFilter extends BaseFilter implements MonitoringAware<HttpServerProbe> {

    private final static Logger LOGGER = Grizzly.logger(HttpHandler.class);
    /**
     * The {@link CompletionHandler} to be used to make sure the response data have been flushed
     */
    private final FlushResponseHandler flushResponseHandler = new FlushResponseHandler();

    /**
     * Attribute, which holds the current HTTP Request in progress associated with an HttpContext
     */
    private final Attribute<Request> httpRequestInProgress;

    /**
     * Delay queue to control suspended request/response processing timeouts
     */
    private final DelayedExecutor.DelayQueue<Response.SuspendTimeout> suspendedResponseQueue;

    /**
     * Root {@link HttpHandler}
     */
    private volatile HttpHandler httpHandler;

    /**
     * Server configuration
     */
    private final ServerFilterConfiguration config;

    /**
     * The flag, which indicates if the server is currently in the shutdown phase
     */
    private AtomicBoolean shuttingDown = new AtomicBoolean();

    /**
     * CompletionHandler to be notified, when shutdown could be gracefully completed
     */
    private AtomicReference<FutureImpl<HttpServerFilter>> shutdownCompletionFuture;

    /**
     * The number of requests, which are currently in process.
     */
    private final AtomicInteger activeRequestsCounter = new AtomicInteger();

    /**
     * Web server probes
     */
    protected final DefaultMonitoringConfig<HttpServerProbe> monitoringConfig = new DefaultMonitoringConfig<HttpServerProbe>(HttpServerProbe.class) {

        @Override
        public Object createManagementObject() {
            return createJmxManagementObject();
        }

    };

    // ------------------------------------------------------------ Constructors

    public HttpServerFilter(final ServerFilterConfiguration config, final DelayedExecutor delayedExecutor) {
        this.config = config;
        suspendedResponseQueue = Response.createDelayQueue(delayedExecutor);
        httpRequestInProgress = Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute("HttpServerFilter.Request");
    }

    @SuppressWarnings({ "UnusedDeclaration" })
    public HttpHandler getHttpHandler() {
        return httpHandler;
    }

    public void setHttpHandler(final HttpHandler httpHandler) {
        this.httpHandler = httpHandler;
    }

    public ServerFilterConfiguration getConfiguration() {
        return config;
    }

    // ----------------------------------------------------- Methods from Filter

    @SuppressWarnings({ "unchecked", "ReturnInsideFinallyBlock" })
    @Override
    public NextAction handleRead(final FilterChainContext ctx) throws IOException {

        // every message coming to HttpServerFilter#handleRead has to have
        // HttpContext associated with the FilterChainContext
        assert HttpContext.get(ctx) != null;

        final Object message = ctx.getMessage();
        final Connection connection = ctx.getConnection();

        if (HttpPacket.isHttp(message)) {

            // Otherwise cast message to a HttpContent
            final HttpContent httpContent = (HttpContent) message;
            final HttpContext context = httpContent.getHttpHeader().getProcessingState().getHttpContext();
            Request handlerRequest = httpRequestInProgress.get(context);

            if (handlerRequest == null) {
                // It's a new HTTP request
                final HttpRequestPacket request = (HttpRequestPacket) httpContent.getHttpHeader();
                final HttpResponsePacket response = request.getResponse();

                handlerRequest = Request.create();
                handlerRequest.parameters.setLimit(config.getMaxRequestParameters());
                httpRequestInProgress.set(context, handlerRequest);
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST, "[{0}][S] {1} {2} {3} {4}",
                               new Object[]{Thread.currentThread().getName(), httpRequestInProgress,
                                            connection.getPeerAddress(), request.getRequestURI(), handlerRequest});
                }
                final Response handlerResponse = handlerRequest.getResponse();

                handlerRequest.initialize(request, ctx, this);
                handlerResponse.initialize(handlerRequest, response, ctx, suspendedResponseQueue, this);

                if (config.isGracefulShutdownSupported()) {
                    activeRequestsCounter.incrementAndGet();
                    handlerRequest.addAfterServiceListener(flushResponseHandler);
                }

                HttpServerProbeNotifier.notifyRequestReceive(this, connection, handlerRequest);

                boolean wasSuspended = false;

                try {
                    ctx.setMessage(handlerResponse);

                    if (shuttingDown.get()) { // if we're in the shutting down phase - serve shutdown page and exit
                        handlerResponse.getResponse().getProcessingState().setError(true);
                        HtmlHelper.setErrorAndSendErrorPage(handlerRequest, handlerResponse, config.getDefaultErrorPageGenerator(), 503,
                                HttpStatus.SERVICE_UNAVAILABLE_503.getReasonPhrase(), "The server is being shutting down...", null);
                    } else if (!config.isPassTraceRequest() && request.getMethod() == Method.TRACE) {
                        onTraceRequest(handlerRequest, handlerResponse);
                    } else if (!checkMaxPostSize(request.getContentLength())) {
                        handlerResponse.getResponse().getProcessingState().setError(true);
                        HtmlHelper.setErrorAndSendErrorPage(handlerRequest, handlerResponse, config.getDefaultErrorPageGenerator(), 413,
                                HttpStatus.REQUEST_ENTITY_TOO_LARGE_413.getReasonPhrase(), "The request payload size exceeds the max post size limitation",
                                null);
                    } else {
                        final HttpHandler httpHandlerLocal = httpHandler;
                        if (httpHandlerLocal != null) {
                            wasSuspended = !httpHandlerLocal.doHandle(handlerRequest, handlerResponse);
                        }
                    }
                } catch (Exception t) {
                    LOGGER.log(Level.WARNING, LogMessages.WARNING_GRIZZLY_HTTP_SERVER_FILTER_HTTPHANDLER_INVOCATION_ERROR(), t);

                    request.getProcessingState().setError(true);

                    if (!response.isCommitted()) {
                        HtmlHelper.setErrorAndSendErrorPage(handlerRequest, handlerResponse, config.getDefaultErrorPageGenerator(), 500,
                                HttpStatus.INTERNAL_SERVER_ERROR_500.getReasonPhrase(), HttpStatus.INTERNAL_SERVER_ERROR_500.getReasonPhrase(), t);
                    }
                } catch (Throwable t) {
                    LOGGER.log(Level.WARNING, LogMessages.WARNING_GRIZZLY_HTTP_SERVER_FILTER_UNEXPECTED(), t);
                    throw new IllegalStateException(t);
                }

                if (!wasSuspended) {
                    return afterService(ctx, connection, handlerRequest, handlerResponse);
                } else {
                    return ctx.getSuspendAction();
                }
            } else {
                // We're working with suspended HTTP request
                if (LOGGER.isLoggable(Level.FINEST)) {
                    final boolean isEquals = httpContent.getHttpHeader() == handlerRequest.getRequest();
                    if (isEquals) {
                        LOGGER.log(Level.FINEST,
                                   "Working with suspended HTTP request. connection={0} httpContent.isLast={1} httpContent.isBroken={2} httpContent.getHttpHeader={3} isEquals={4} httpContext={5} handlerRequest={6}",
                                   new Object[]{connection, httpContent.isLast(), HttpContent.isBroken(httpContent),
                                                httpContent.getHttpHeader(), isEquals, context, handlerRequest});
                    } else {
                        LOGGER.log(Level.FINEST,
                                   "Working with suspended HTTP request. connection={0} httpContent.isLast={1} httpContent.isBroken={2} httpContent.getHttpHeader={3} handlerRequest.getRequest={4} isEquals={5} httpContext={6} handlerRequest={7}",
                                   new Object[]{connection, httpContent.isLast(), HttpContent.isBroken(httpContent),
                                                httpContent.getHttpHeader(), handlerRequest.getRequest(), isEquals,
                                                context, handlerRequest});
                    }
                }
                try {
                    ctx.suspend();
                    final NextAction action = ctx.getSuspendAction();

                    if (!handlerRequest.getInputBuffer().append(httpContent)) {
                        // we don't want this thread/context to reset
                        // OP_READ on Connection

                        // we have enough data? - terminate filter chain execution
                        ctx.completeAndRecycle();
                    } else {
                        ctx.resume(ctx.getStopAction());
                    }

                    return action;
                } catch (IOException e) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        final boolean isEquals = httpContent.getHttpHeader() == handlerRequest.getRequest();
                        if (isEquals) {
                            LOGGER.log(Level.FINE,
                                       "Error during working with suspended HTTP request. connection=" + connection +
                                       " httpContent.isLast=" + httpContent.isLast() + " httpContent.isBroken=" +
                                       HttpContent.isBroken(httpContent) + " httpContent.getHttpHeader=" +
                                       httpContent.getHttpHeader() + " isEquals=" + isEquals + " httpContext=" +
                                       context + " handlerRequest=" + handlerRequest, e);
                        } else {
                            LOGGER.log(Level.FINE,
                                       "Error during working with suspended HTTP request. connection=" + connection +
                                       " httpContent.isLast=" + httpContent.isLast() + " httpContent.isBroken=" +
                                       HttpContent.isBroken(httpContent) + " httpContent.getHttpHeader=" +
                                       httpContent.getHttpHeader() + " handlerRequest.getRequest=" +
                                       handlerRequest.getRequest() + " isEquals=" + isEquals + " httpContext=" +
                                       context + " handlerRequest=" + handlerRequest, e);
                        }
                    }
                    final ReadHandler handler = handlerRequest.getInputBuffer().getReadHandler();
                    if (handler != null) {
                        handler.onError(e);
                    }
                    // The connection will be closed during the previous filter's exceptionOccurred() process.
                    ctx.fail(e);
                    return ctx.getSuspendAction();
                } finally {
                    httpContent.recycle();
                }
            }
        } else { // this code will be run, when we resume the context
            // We're finishing the request processing
            final Response response = (Response) message;
            final Request request = response.getRequest();
            return afterService(ctx, connection, request, response);
        }
    }

    /**
     * Override the default implementation to notify the {@link ReadHandler}, if available, of any read error that has
     * occurred during processing.
     *
     * @param ctx event processing {@link FilterChainContext}
     * @param error error, which occurred during <tt>FilterChain</tt> execution
     */
    @Override
    public void exceptionOccurred(final FilterChainContext ctx, final Throwable error) {
        final HttpContext context = HttpContext.get(ctx);
        if (context != null) {
            final Request request = httpRequestInProgress.get(context);

            if (request != null) {
                final ReadHandler handler = request.getInputBuffer().getReadHandler();
                if (handler != null) {
                    handler.onError(error);
                }
            }
        }
    }

    @Override
    public NextAction handleEvent(final FilterChainContext ctx, final FilterChainEvent event) throws IOException {
        if (event.type() == ShutdownEvent.TYPE) {
            if (shuttingDown.compareAndSet(false, true)) {
                final ShutdownEvent shutDownEvent = (ShutdownEvent) event;
                final FutureImpl<HttpServerFilter> future = Futures.createSafeFuture();

                shutDownEvent.addShutdownTask(new Callable<Filter>() {
                    @Override
                    public Filter call() throws Exception {
                        return future.get();
                    }
                });

                shutdownCompletionFuture = new AtomicReference<>(future);
                if (activeRequestsCounter.get() == 0) {
                    future.result(this);
                }
            }
        }
        return ctx.getInvokeAction();
    }

    // ---------------------------------------------------------- Public Methods

    /**
     * {@inheritDoc}
     */
    @Override
    public MonitoringConfig<HttpServerProbe> getMonitoringConfig() {
        return monitoringConfig;
    }

    // ------------------------------------------------------- Protected Methods

    protected Object createJmxManagementObject() {
        return MonitoringUtils.loadJmxObject("org.glassfish.grizzly.http.server.jmx.HttpServerFilter", this, HttpServerFilter.class);
    }

    protected void onTraceRequest(final Request request, final Response response) throws IOException {
        if (config.isTraceEnabled()) {
            HtmlHelper.writeTraceMessage(request, response);
        } else {
            response.setStatus(HttpStatus.METHOD_NOT_ALLOWED_405);
            response.setHeader(Header.Allow, "POST, GET, DELETE, OPTIONS, PUT, HEAD");
        }
    }

    protected String getFullServerName() {
        return config.getHttpServerName() + " " + config.getHttpServerVersion();
    }

    // --------------------------------------------------------- Private Methods

    private NextAction afterService(final FilterChainContext ctx, final Connection connection, final Request request, final Response response)
            throws IOException {

        final HttpContext context = request.getRequest().getProcessingState().getHttpContext();

        if (request.getRequest().isUpgrade() && !response.getResponse().isUpgrade()) {
            request.getRequest().setIgnoreContentModifiers(false);
        }

        final Request removedHandlerRequest = httpRequestInProgress.remove(context);
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "[{0}][R] {1} {2} {3} {4}",
                       new Object[]{Thread.currentThread().getName(), httpRequestInProgress,
                                    connection.getPeerAddress(), request.getRequestURI(), removedHandlerRequest});
        }
        response.finish();
        request.onAfterService();

        HttpServerProbeNotifier.notifyRequestComplete(this, connection, response);

        final HttpRequestPacket httpRequest = request.getRequest();
        final boolean isBroken = httpRequest.isContentBroken();

        // Suspend state is cancelled - it means normal processing might have
        // been broken. We don't want to reuse Request and Response in this state,
        // cause there still might be threads referencing them.
        if (response.suspendState != Response.SuspendState.CANCELLED) {
            response.recycle();
            request.recycle();
        }

        if (isBroken) {
            // if content is broken - we're not able to distinguish
            // the end of the message - so stop processing any input data on
            // this connection (connection is being closed by
            // {@link org.glassfish.grizzly.http.HttpServerFilter#handleEvent(...)})
            final NextAction suspendNextAction = ctx.getSuspendAction();
            ctx.completeAndRecycle();
            return suspendNextAction;
        }

        return ctx.getStopAction();
    }

    /**
     * Will be called, once HTTP request processing is complete and response is flushed.
     */
    private void onRequestCompleteAndResponseFlushed() {
        final int count = activeRequestsCounter.decrementAndGet();
        if (count == 0 && shuttingDown.get()) {
            final FutureImpl<HttpServerFilter> shutdownFuture = shutdownCompletionFuture != null ? shutdownCompletionFuture.getAndSet(null) : null;

            if (shutdownFuture != null) {
                shutdownFuture.result(this);
            }
        }
    }

    /**
     * @return <tt>true</tt> if request content-length doesn't exceed the max post size limit, or <tt>false</tt> otherwise
     */
    private boolean checkMaxPostSize(final long requestContentLength) {
        final long maxPostSize = config.getMaxPostSize();
        return requestContentLength <= 0 || maxPostSize < 0 || maxPostSize >= requestContentLength;
    }

    /**
     * The {@link CompletionHandler} to be used to make sure the response data have been flushed.
     */
    private final class FlushResponseHandler extends EmptyCompletionHandler<Object> implements AfterServiceListener {

        private final FilterChainEvent event = TransportFilter.createFlushEvent(this);

        @Override
        public void cancelled() {
            onRequestCompleteAndResponseFlushed();
        }

        @Override
        public void failed(final Throwable throwable) {
            onRequestCompleteAndResponseFlushed();
        }

        @Override
        public void completed(final Object result) {
            onRequestCompleteAndResponseFlushed();
        }

        @Override
        public void onAfterService(final Request request) {
            // same as request.getContext().flush(this), but less garbage
            request.getContext().notifyDownstream(event);
        }
    }
}
