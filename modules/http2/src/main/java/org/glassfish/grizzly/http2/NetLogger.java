/*
 * Copyright (c) 2015, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http2;

import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.http2.frames.ContinuationFrame;
import org.glassfish.grizzly.http2.frames.DataFrame;
import org.glassfish.grizzly.http2.frames.GoAwayFrame;
import org.glassfish.grizzly.http2.frames.HeadersFrame;
import org.glassfish.grizzly.http2.frames.Http2Frame;
import org.glassfish.grizzly.http2.frames.PingFrame;
import org.glassfish.grizzly.http2.frames.PriorityFrame;
import org.glassfish.grizzly.http2.frames.PushPromiseFrame;
import org.glassfish.grizzly.http2.frames.RstStreamFrame;
import org.glassfish.grizzly.http2.frames.SettingsFrame;
import org.glassfish.grizzly.http2.frames.UnknownFrame;
import org.glassfish.grizzly.http2.frames.WindowUpdateFrame;

final class NetLogger {

    private static final Logger LOGGER = Grizzly.logger(NetLogger.class);
    private static final Level LEVEL = Level.FINE;

    private static final String CLOSE_FMT = "'{' \"session\":\"{0}\", \"event\":\"SESSION_CLOSE\" '}'";
    private static final String DATA = "DATA";
    private static final String DATA_FMT = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"stream\":\"{2}\", \"fin\":\"{3}\", \"len\":\"{4}\" '}'";
    private static final String CONTINUATION = "CONTINUATION";
    private static final String CONTINUATION_FMT = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"stream\":\"{2}\", \"len\":\"{3}\" '}'";
    private static final String GOAWAY = "GOAWAY";
    private static final String GOAWAY_FMT = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"stream\":\"{2}\", \"last-stream\":\"{3}\", \"error-code\":\"{4}\", \"detail\":\"{5}\" '}'";
    private static final String HEADERS = "HEADERS";
    private static final String HEADERS_FMT = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"stream\":\"{2}\", \"parent-stream\":\"{3}\", \"prioritized\":\"{4}\", \"exclusive\":\"{5}\", \"weight\":\"{6}\", \"fin\":\"{7}\", \"len\":\"{8}\", \"headers\":{9} '}'";
    private static final String RESET_BUDGET_EXCEEDED_FMT = "'{' \"session\":\"{0}\", \"event\":\"RESET_BUDGET_EXCEEDED\", \"detail\":\"{1}\" '}'";
    private static final String OPEN_FMT = "'{' \"session\":\"{0}\", \"event\":\"SESSION_OPEN\" '}'";
    private static final String PING = "PING";
    private static final String PING_FMT = "'{' session=\"{0}\", event=\"{1}\", is-ack=\"{2}\", opaque-data=\"{3}\" '}'";
    private static final String PRIORITY = "PRIORITY";
    private static final String PRIORITY_FMT = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"stream\":\"{2}\", \"parent-stream\":\"{3}\", \"exclusive\":\"{4}\", \"weight\":\"{5}\" '}'";
    private static final String PUSH_PROMISE = "PUSH_PROMISE";
    private static final String PUSH_PROMISE_FMT = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"stream\":\"{2}\", \"promised-stream\":\"{3}\", \"len\":\"{4}\", \"headers\":{5} '}'";
    private static final String RST = "RST";
    private static final String RST_FMT = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"stream\":\"{2}\", \"error-code\":\"{3}\" '}'";
    private static final String SETTINGS = "SETTINGS";
    private static final String SETTINGS_FMT = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"settings\":'{'{2}'}' '}'";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String UNKNOWN_FMT = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"frame-type\":\"{2}\", \"len\":\"{3}\" '}'";
    private static final String WINDOW_UPDATE = "WINDOW_UPDATE";
    private static final String WINDOW_UPDATE_FMT = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"delta\":\"{2}\" '}'";

    private static final String NOT_AVAILABLE = "None Available";

    enum Context {
        TX("SEND_"), RX("RECV_");

        final String prefix;

        Context(final String prefix) {
            this.prefix = prefix;
        }

        String getPrefix() {
            return prefix;
        }
    }

    static boolean isActive() {
        return LOGGER.isLoggable(LEVEL);
    }

    static void log(final Context ctx, final Http2Session c, final Http2Frame frame) {
        switch (frame.getType()) {
        case ContinuationFrame.TYPE:
            log(ctx, c, (ContinuationFrame) frame);
            break;
        case DataFrame.TYPE:
            log(ctx, c, (DataFrame) frame);
            break;
        case GoAwayFrame.TYPE:
            log(ctx, c, (GoAwayFrame) frame);
            break;
        case HeadersFrame.TYPE:
            break;
        case PingFrame.TYPE:
            log(ctx, c, (PingFrame) frame);
            break;
        case PriorityFrame.TYPE:
            log(ctx, c, (PriorityFrame) frame);
            break;
        case PushPromiseFrame.TYPE:
            break;
        case RstStreamFrame.TYPE:
            log(ctx, c, (RstStreamFrame) frame);
            break;
        case SettingsFrame.TYPE:
            log(ctx, c, (SettingsFrame) frame);
            break;
        case WindowUpdateFrame.TYPE:
            log(ctx, c, (WindowUpdateFrame) frame);
            break;
        default:
            log(ctx, c, (UnknownFrame) frame);

        }
    }

    static void log(final Context ctx, final Http2Session c, final ContinuationFrame frame) {
        validateParams(ctx, c, frame);
        if (isActive()) {
            LOGGER.log(LEVEL, CONTINUATION_FMT,
                    new Object[] { escape(c.getConnection().toString()), ctx.getPrefix() + CONTINUATION, frame.getStreamId(), frame.getLength() });
        }
    }

    static void log(final Context ctx, final Http2Session c, final DataFrame frame) {
        validateParams(ctx, c, frame);
        if (isActive()) {
            LOGGER.log(LEVEL, DATA_FMT, new Object[] { escape(c.getConnection().toString()), ctx.getPrefix() + DATA, frame.getStreamId(), frame.isEndStream(),
                    frame.getData().remaining() });
        }
    }

    static void log(final Context ctx, final Http2Session c, final GoAwayFrame frame) {
        validateParams(ctx, c, frame);
        if (isActive()) {
            final Buffer b = frame.getAdditionalDebugData();
            final String details = b != null ? b.toStringContent() : NOT_AVAILABLE;
            LOGGER.log(LEVEL, GOAWAY_FMT, new Object[] { escape(c.getConnection().toString()), ctx.getPrefix() + GOAWAY, frame.getStreamId(),
                    frame.getLastStreamId(), frame.getErrorCode().getCode(), escape(details) });
        }
    }

    static void log(final Context ctx, final Http2Session c, final HeadersFrame frame, final Map<String, String> headers) {
        validateParams(ctx, c, frame);
        if (isActive()) {
            LOGGER.log(LEVEL, HEADERS_FMT,
                    new Object[] { escape(c.getConnection().toString()), ctx.getPrefix() + HEADERS, frame.getStreamId(), frame.getStreamDependency(),
                            frame.isPrioritized(), frame.isExclusive(), frame.getWeight(), frame.isEndStream(), frame.getLength(), toJSON(headers) });

        }
    }

    static void log(final Context ctx, final Http2Session c, final PingFrame frame) {
        validateParams(ctx, c, frame);
        if (isActive()) {
            LOGGER.log(LEVEL, PING_FMT, new Object[] { escape(c.getConnection().toString()), ctx.getPrefix() + PING, frame.isAckSet(), frame.getOpaqueData() });
        }
    }

    static void log(final Context ctx, final Http2Session c, final PriorityFrame frame) {
        validateParams(ctx, c, frame);
        if (isActive()) {
            LOGGER.log(LEVEL, PRIORITY_FMT, new Object[] { escape(c.getConnection().toString()), ctx.getPrefix() + PRIORITY, frame.getStreamId(),
                    frame.getStreamDependency(), frame.isExclusive(), frame.getWeight() });
        }
    }

    static void log(final Context ctx, final Http2Session c, final PushPromiseFrame frame, final Map<String, String> headers) {
        validateParams(ctx, c, frame);
        if (isActive()) {
            LOGGER.log(LEVEL, PUSH_PROMISE_FMT, new Object[] { escape(c.getConnection().toString()), ctx.getPrefix() + PUSH_PROMISE, frame.getStreamId(),
                    frame.getPromisedStreamId(), frame.getLength(), toJSON(headers) });
        }
    }

    static void log(final Context ctx, final Http2Session c, final RstStreamFrame frame) {
        validateParams(ctx, c, frame);
        if (isActive()) {
            LOGGER.log(LEVEL, RST_FMT,
                    new Object[] { escape(c.getConnection().toString()), ctx.getPrefix() + RST, frame.getStreamId(), frame.getErrorCode().getCode() });
        }
    }

    static void log(final Context ctx, final Http2Session c, final SettingsFrame frame) {
        validateParams(ctx, c, frame);
        if (isActive()) {
            final int numSettings = frame.getNumberOfSettings();
            final StringBuilder sb = new StringBuilder();
            if (numSettings > 0) {
                for (int i = 0; i < numSettings; i++) {
                    final SettingsFrame.Setting setting = frame.getSettingByIndex(i);
                    sb.append('"').append(frame.getSettingNameById(setting.getId())).append('"');
                    sb.append(": ");
                    sb.append('"').append(setting.getValue()).append('"');
                    if (i + 1 < numSettings) {
                        sb.append(", ");
                    }
                }
            }
            LOGGER.log(LEVEL, SETTINGS_FMT, new Object[] { escape(c.getConnection().toString()), ctx.getPrefix() + SETTINGS, sb.toString() });
        }
    }

    static void log(final Context ctx, final Http2Session c, final WindowUpdateFrame frame) {
        validateParams(ctx, c, frame);
        if (isActive()) {
            LOGGER.log(LEVEL, WINDOW_UPDATE_FMT,
                    new Object[] { escape(c.getConnection().toString()), ctx.getPrefix() + WINDOW_UPDATE, frame.getWindowSizeIncrement() });
        }
    }

    static void log(final Context ctx, final Http2Session c, final UnknownFrame frame) {
        validateParams(ctx, c, frame);
        if (isActive()) {
            LOGGER.log(LEVEL, UNKNOWN_FMT,
                    new Object[] { escape(c.getConnection().toString()), ctx.getPrefix() + UNKNOWN, frame.getType(), frame.getLength() });
        }
    }

    static void logClose(final Http2Session c) {
        logSessionEvent(CLOSE_FMT, c);
    }

    static void logResetBudgetExceeded(final Http2Session c, final String cause) {
        if (isActive()) {
            LOGGER.log(LEVEL, RESET_BUDGET_EXCEEDED_FMT, new Object[] { escape(c.getConnection().toString()), escape(cause) });
        }
    }

    static void logOpen(final Http2Session c) {
        logSessionEvent(OPEN_FMT, c);
    }

    // --------------------------------------------------------- Private Methods

    private static void logSessionEvent(final String msg, final Http2Session c) {
        if (c == null) {
            throw new NullPointerException("Http2Session cannot be null");
        }
        if (isActive()) {
            LOGGER.log(LEVEL, msg, new Object[] { escape(c.getConnection().toString()) });
        }
    }

    private static StringBuilder toJSON(final Map<String, String> headers) {
        final StringBuilder result = new StringBuilder(64);
        result.append("{ ");
        for (Iterator<Map.Entry<String, String>> i = headers.entrySet().iterator(); i.hasNext();) {
            Map.Entry<String, String> entry = i.next();
            result.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
            if (i.hasNext()) {
                result.append(", ");
            }
        }
        result.append(" }");
        return result;
    }

    private static void validateParams(final Context ctx, final Http2Session c, final Http2Frame frame) {
        if (ctx == null) {
            throw new NullPointerException("Context cannot be null.");
        }
        if (c == null) {
            throw new NullPointerException("Http2Session cannot be null.");
        }
        if (frame == null) {
            throw new NullPointerException("Http2Frame cannot be null.");
        }
    }

    private static String escape(final String s) {
        final StringBuilder sb = new StringBuilder(s.length() + 20);
        for (int i = 0, len = s.length(); i < len; i++) {
            final char c = s.charAt(i);
            switch (c) {
            case '\'':
                sb.append("\'");
                break;
            case '"':
                sb.append("\"");
                break;
            case '\\':
                sb.append("\\");
                break;
            default:
                sb.append(c);
            }
        }
        return sb.toString();
    }

}
