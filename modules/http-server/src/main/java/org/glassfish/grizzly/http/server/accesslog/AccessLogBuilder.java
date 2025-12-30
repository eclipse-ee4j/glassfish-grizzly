/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2014, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http.server.accesslog;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.HttpServerMonitoringConfig;
import org.glassfish.grizzly.http.server.ServerConfiguration;

/**
 * A simple <em>builder</em> to configure access logging for Grizzly.
 *
 * <p>
 * If the {@linkplain #format(AccessLogFormat) format} is left unspecified, the default
 * {@linkplain ApacheLogFormat#COMBINED Apache combined format} will be used.
 * </p>
 *
 * <p>
 * If the {@linkplain #timeZone(ZoneId) time zone} is left unspecified, the {@link ZoneId#systemDefault() default zone
 * id} will be used.
 * </p>
 *
 * @author <a href="mailto:pier@usrz.com">Pier Fumagalli</a>
 * @author <a href="http://www.usrz.com/">USRZ.com</a>
 */
public class AccessLogBuilder {

    /* Our default access log format (Apache "combined" log) */
    private AccessLogFormat format = ApacheLogFormat.COMBINED;
    /* The default status threshold (log everything) */
    private int statusThreshold = AccessLogProbe.DEFAULT_STATUS_THRESHOLD;
    /* Null rotation pattern, do NOT rotate by default */
    private String rotationPattern;
    /* Non-synchronous, always use a Queue+Thread */
    private boolean synchronous;

    /* The base file name of the access log */
    private final File file;

    /**
     * Create a new {@link AccessLogBuilder} writing logs to the specified file.
     *
     * @param file The location of the access log file.
     */
    public AccessLogBuilder(String file) {
        if (file == null) {
            throw new NullPointerException("Null file");
        }
        this.file = new File(file).getAbsoluteFile();
    }

    /**
     * Create a new {@link AccessLogBuilder} writing logs to the specified file.
     *
     * @param file The location of the access log file.
     */
    public AccessLogBuilder(File file) {
        if (file == null) {
            throw new NullPointerException("Null file");
        }
        this.file = file;
    }

    /**
     * Build an {@link AccessLogProbe} instance which can be injected into an {@link HttpServer}'s
     * {@linkplain HttpServerMonitoringConfig monitoring configuration} to provide access logging.
     */
    public AccessLogProbe build() {
        /* Build an appender, plain or rotating */
        AccessLogAppender appender;
        try {
            if (rotationPattern == null) {
                appender = new FileAppender(file.getCanonicalFile());
            } else {
                /* Get directory and base file name (encode ' single quotes) */
                final File directory = file.getCanonicalFile().getParentFile();
                final String name = file.getName();

                /* Split "name.ext" name in "name" + ".ext" */
                final String base;
                final String extension;
                final int position = name.lastIndexOf(".");
                if (position < 0) {
                    base = name.replace("'", "''");
                    extension = "";
                } else {
                    base = name.substring(0, position).replace("'", "''");
                    extension = name.substring(position).replace("'", "''");
                }

                /* Build a simple date format pattern like "'name-'pattern'.ext'" */
                final String archive = new StringBuilder().append('\'').append(base).append("'-").append(rotationPattern).append('\'').append(extension)
                        .append('\'').toString();

                /* Create our appender */
                appender = new RotatingFileAppender(directory, name, archive);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("I/O error creating access log", exception);
        }

        /* Wrap the synch in a queue in a-synchronous */
        if (!synchronous) {
            appender = new QueueingAppender(appender);
        }

        /* Create and return our probe */
        return new AccessLogProbe(appender, format, statusThreshold);
    }

    /**
     * Build an {@link AccessLogProbe} instance and directly instrument it in an {@link HttpServer}'s
     * {@linkplain HttpServerMonitoringConfig monitoring configuration} to provide access logging.
     *
     * @param serverConfiguration The {@link ServerConfiguration} to instrument.
     */
    public ServerConfiguration instrument(ServerConfiguration serverConfiguration) {
        serverConfiguration.getMonitoringConfig().getWebServerConfig().addProbes(build());
        return serverConfiguration;
    }

    /**
     * Set the {@link AccessLogFormat} instance that will be used by the access logs configured by this instance.
     */
    public AccessLogBuilder format(AccessLogFormat format) {
        if (format == null) {
            throw new NullPointerException("Null format");
        }
        this.format = format;
        return this;
    }

    /**
     * Set the <em>format</em> as a {@link String} compatible with the default {@linkplain ApacheLogFormat Apache access log
     * format} that will be used by the access logs configured by this instance.
     */
    public AccessLogBuilder format(String format) {
        if (format == null) {
            throw new NullPointerException("Null format");
        }
        return this.format(new ApacheLogFormat(format));
    }

    /**
     * Set the <em>time zone</em> that will be used to represent dates.
     */
    public AccessLogBuilder timeZone(TimeZone timeZone) {
        if (timeZone == null) {
            throw new NullPointerException("Null time zone");
        }
        if (format instanceof ApacheLogFormat) {
            final ApacheLogFormat apacheFormat = (ApacheLogFormat) format;
            format = new ApacheLogFormat(timeZone.toZoneId(), apacheFormat.getFormat());
            return this;
        }
        throw new IllegalStateException("TimeZone can not be set for " + format.getClass().getName());
    }

    /**
     * Set the <em>time zone</em> that will be used to represent dates.
     */
    public AccessLogBuilder timeZone(final ZoneId zoneId) {
        if (zoneId == null) {
            throw new NullPointerException("Null zoneId");
        }
        if (format instanceof ApacheLogFormat apacheFormat) {
            format = new ApacheLogFormat(zoneId, apacheFormat.getFormat());
            return this;
        }
        throw new IllegalStateException("TimeZone can not be set for " + format.getClass().getName());
    }

    /**
     * Set the <em>time zone</em> that will be used to represent dates.
     *
     * <p>
     * The time zone will be looked up by {@linkplain TimeZone#getTimeZone(String) time zone identifier}, and if this is
     * invalid or unrecognized, it will default to <em>GMT</em>.
     * </p>
     */
    public AccessLogBuilder timeZone(String timeZone) {
        if (timeZone == null) {
            throw new NullPointerException("Null time zone");
        }
        return this.timeZone(TimeZone.getTimeZone(timeZone));
    }

    /**
     * Set the minimum <em>response status</em> that will trigger an entry in an access log configured by this instance.
     *
     * <p>
     * For example a threshold of <code>500</code> will only generate log entries for requests that terminated in error.
     * </p>
     */
    public AccessLogBuilder statusThreshold(int statusThreshold) {
        this.statusThreshold = statusThreshold;
        return this;
    }

    /**
     * Set up automatic log-file rotation, on a hourly basis.
     *
     * <p>
     * For example, if the file name specified at {@linkplain #AccessLogBuilder(File) construction} was
     * <code>access.log</code>, files will be archived on a hourly basis with names like <code>access-yyyyMMDDhh.log</code>.
     * </p>
     */
    public AccessLogBuilder rotatedHourly() {
        return rotationPattern("yyyyMMDDhh");
    }

    /**
     * Set up automatic log-file rotation, on a daily basis.
     *
     * <p>
     * For example, if the file name specified at {@linkplain #AccessLogBuilder(File) construction} was
     * <code>access.log</code>, files will be archived on a daily basis with names like <code>access-yyyyMMDD.log</code>.
     * </p>
     */
    public AccessLogBuilder rotatedDaily() {
        return rotationPattern("yyyyMMDD");
    }

    /**
     * Set up automatic log-file rotation based on a specified {@link DateTimeFormatter} <em>pattern</em>.
     *
     * <p>
     * For example, if the file name specified at {@linkplain #AccessLogBuilder(File) construction} was
     * <code>access.log</code> and the <em>rotation pattern</code> specified here is <code>EEE</code> <em>(day name in
     * week)</em>, files will be archived on a daily basis with names like <code>access-Mon.log</code>,
     * <code>access-Tue.log</code>, ...
     * </p>
     */
    public AccessLogBuilder rotationPattern(String rotationPattern) {
        if (rotationPattern == null) {
            throw new NullPointerException("Null rotation pattern");
        }
        this.rotationPattern = rotationPattern;
        return this;
    }

    /**
     * Specify whether access log entries should be written <en>synchronously</em> or not.
     *
     * <p>
     * If <b>false</b> (the default) a {@link QueueingAppender} will be used to enqueue entries and append to the final
     * appenders when possible.
     * </p>
     */
    public AccessLogBuilder synchronous(boolean synchronous) {
        this.synchronous = synchronous;
        return this;
    }
}
