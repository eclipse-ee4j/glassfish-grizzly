/*
 * Copyright (c) 2008, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.localization.LogMessages;
import org.glassfish.grizzly.nio.NIOConnection;

/**
 *
 * @author oleksiys
 */
public final class ProcessorExecutor {

    private static final Logger LOGGER = Grizzly.logger(ProcessorExecutor.class);

    public static void execute(final Connection connection, final IOEvent ioEvent, final Processor processor,
            final IOEventLifeCycleListener lifeCycleListener) {

        execute(Context.create(connection, processor, ioEvent, lifeCycleListener));
    }

    @SuppressWarnings("unchecked")
    public static void execute(Context context) {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "executing connection ({0}). IOEvent={1} processor={2}",
                    new Object[] { context.getConnection(), context.getIoEvent(), context.getProcessor() });
        }

        // Writes corked while processing input (see NIOConnection#corkWrites) are released when that processing run
        // ends, including a suspension: the counterpart of Netty's flush on channelReadComplete. Nested executions
        // (writes, events) don't uncork.
        final Connection connection = context.getConnection();
        final boolean isRead = context.getIoEvent() == IOEvent.READ;
        try {
            execute0(context);
        } finally {
            if (isRead && connection instanceof NIOConnection) {
                ((NIOConnection) connection).uncorkWrites();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void execute0(Context context) {
        boolean isRerun;
        ProcessorResult result;

        try {
            do {
                result = context.getProcessor().process(context);
                isRerun = result.getStatus() == ProcessorResult.Status.RERUN;
                if (isRerun) {
                    final Context newContext = (Context) result.getData();
                    rerun(context, newContext);
                    context = newContext;
                }
            } while (isRerun);

            complete0(context, result);

        } catch (Throwable t) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, LogMessages.WARNING_GRIZZLY_PROCESSOR_ERROR(context.getConnection(), context.getIoEvent(), context.getProcessor()),
                        t);
            }

            try {
                error(context, t);
            } catch (Exception ignored) {
            }
        }
    }

    public static void resume(final Context context) throws IOException {
        execute(context);
    }

    private static void complete(final Context context, final Object data) throws IOException {

        final int sz = context.lifeCycleListeners.size();
        final IOEventLifeCycleListener[] listeners = context.lifeCycleListeners.array();
        try {
            for (int i = 0; i < sz; i++) {
                listeners[i].onComplete(context, data);
            }
        } finally {
            context.recycle();
        }
    }

    private static void leave(final Context context) throws IOException {
        final int sz = context.lifeCycleListeners.size();
        final IOEventLifeCycleListener[] listeners = context.lifeCycleListeners.array();
        try {
            for (int i = 0; i < sz; i++) {
                listeners[i].onLeave(context);
            }
        } finally {
            context.recycle();
        }
    }

    private static void reregister(final Context context, final Object data) throws IOException {

        // "Context context" was suspended, so we reregister with its copy
        // which is passed as "Object data"
        final Context realContext = (Context) data;

        final int sz = context.lifeCycleListeners.size();
        final IOEventLifeCycleListener[] listeners = context.lifeCycleListeners.array();
        try {
            for (int i = 0; i < sz; i++) {
                listeners[i].onReregister(realContext);
            }
        } finally {
            realContext.recycle();
        }
    }

    private static void rerun(final Context context, final Context newContext) throws IOException {

        final int sz = context.lifeCycleListeners.size();
        final IOEventLifeCycleListener[] listeners = context.lifeCycleListeners.array();
        for (int i = 0; i < sz; i++) {
            listeners[i].onRerun(context, newContext);
        }
    }

    private static void error(final Context context, final Object description) throws IOException {
        final int sz = context.lifeCycleListeners.size();
        final IOEventLifeCycleListener[] listeners = context.lifeCycleListeners.array();
        try {
            for (int i = 0; i < sz; i++) {
                listeners[i].onError(context, description);
            }
        } finally {
            context.release();
        }
    }

    private static void notRun(final Context context) throws IOException {
        final int sz = context.lifeCycleListeners.size();
        final IOEventLifeCycleListener[] listeners = context.lifeCycleListeners.array();
        try {
            for (int i = 0; i < sz; i++) {
                listeners[i].onNotRun(context);
            }
        } finally {
            context.recycle();
        }
    }

    static void complete(final Context context, final ProcessorResult result) {

        try {
            complete0(context, result);
        } catch (Throwable t) {
            try {
                error(context, t);
            } catch (Exception ignored) {
            }
        }
    }

    private static void complete0(final Context context, final ProcessorResult result) throws IllegalStateException, IOException {

        final ProcessorResult.Status status = result.getStatus();

        switch (status) {
        case COMPLETE:
            complete(context, result.getData());
            break;

        case LEAVE:
            leave(context);
            break;

        case TERMINATE:
//                    terminate(context);
            break;

        case REREGISTER:
            reregister(context, result.getData());
            break;

        case ERROR:
            error(context, result.getData());
            break;

        case NOT_RUN:
            notRun(context);
            break;

        default:
            throw new IllegalStateException();
        }
    }
}
