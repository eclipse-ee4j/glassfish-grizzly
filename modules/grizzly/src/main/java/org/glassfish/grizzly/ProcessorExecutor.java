/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.localization.LogMessages;

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

        if (context.getIoEvent() != IOEvent.READ) {
            execute0(context, null);
            return;
        }

        // Tasks registered with runAtEndOfReadRun() (e.g. flushing writes corked while processing the input) run when the
        // outermost READ processing run of this thread ends, including a suspension, and before the run completes, so
        // before READ interest is enabled again: the counterpart of Netty's flush on channelReadComplete. Nested READ
        // runs (e.g. the HTTP/2 streams of the connection being read) don't end it.
        final ReadRun run = READ_RUN.get();
        run.depth++;
        execute0(context, run);
    }

    private static final ThreadLocal<ReadRun> READ_RUN = ThreadLocal.withInitial(ReadRun::new);

    /**
     * Registers a task to run when the READ processing run of the current thread ends, before READ interest is enabled
     * again, or earlier when the READ processing is suspended. A task registered more than once runs once.
     * <p>
     * Internal API, used by the transport to coalesce writes.
     *
     * @param task the task
     * @return {@code false} if nothing is registered: the current thread is not processing a READ, or is processing a
     * nested one (e.g. a READ resumed while processing another READ), whose end enables READ interest again while the
     * outer run continues
     * @since 5.0.4
     */
    public static boolean runAtEndOfReadRun(final Runnable task) {
        final ReadRun run = READ_RUN.get();
        if (run.depth != 1) {
            return false;
        }
        run.add(task);
        return true;
    }

    /**
     * Runs the tasks registered with {@link #runAtEndOfReadRun(Runnable)} now: a suspended READ may be resumed (and
     * complete) on another thread before this run ends.
     */
    static void onReadSuspended() {
        final ReadRun run = READ_RUN.get();
        if (run.depth != 0) {
            run.runTasks();
        }
    }

    private static final class ReadRun {
        private static final int INITIAL_CAPACITY = 4;

        int depth;
        private Runnable[] tasks = new Runnable[INITIAL_CAPACITY];
        private int count;

        void add(final Runnable task) {
            for (int i = 0; i < count; i++) {
                if (tasks[i] == task) {
                    return;
                }
            }
            if (count == tasks.length) {
                tasks = Arrays.copyOf(tasks, count * 2);
            }
            tasks[count++] = task;
        }

        void end() {
            if (depth != 1) {
                depth--;
                return;
            }
            // the run lasts until its tasks have run: READ processing started by a task is nested
            try {
                runTasks();
            } finally {
                depth = 0;
            }
        }

        void runTasks() {
            Error error = null;
            try {
                // a task may register further tasks: count is re-read
                for (int i = 0; i < count; i++) {
                    final Runnable task = tasks[i];
                    if (task == null) {
                        continue; // already run by a nested call (a task suspending a READ)
                    }
                    tasks[i] = null;
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        LOGGER.log(Level.WARNING, "Task at the end of a READ processing run failed", e);
                    } catch (Error e) {
                        // run the other tasks first
                        if (error == null) {
                            error = e;
                        }
                    }
                }
            } finally {
                Arrays.fill(tasks, 0, count, null);
                count = 0;
            }
            if (error != null) {
                throw error;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void execute0(Context context, final ReadRun run) {
        boolean isRerun;
        ProcessorResult result;
        boolean runEnded = run == null;

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

            runEnded = true;
            if (run != null) {
                run.end();
            }

            complete0(context, result);

        } catch (Throwable t) {
            if (!runEnded) {
                runEnded = true;
                try {
                    run.end();
                } catch (Throwable endFailure) {
                    t.addSuppressed(endFailure);
                }
            }

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
