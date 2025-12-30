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

import static java.util.logging.Level.WARNING;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.http.server.HttpServer;

/**
 * An {@link AccessLogAppender appender} writing log entries to {@link File}s, and rotating/archiving them when
 * necessary.
 *
 * @author <a href="mailto:pier@usrz.com">Pier Fumagalli</a>
 * @author <a href="http://www.usrz.com/">USRZ.com</a>
 */
public class RotatingFileAppender implements AccessLogAppender {

    private static final Logger LOGGER = Grizzly.logger(HttpServer.class);

    /* The DTF that will format the "current" file name */
    private final DateTimeFormatter fileFormat;
    /* The DTF that will format the "archive" file name */
    private final DateTimeFormatter archiveFormat;

    /* Our current file appender */
    private FileAppender appender;
    /* The directory where to keep files */
    private final File directory;
    /* The name of the current archive file name */
    private File currentArchive;
    /* The name of the file we're actualy writing to */
    private File currentFile;
    /* Flag, closed, byebye */
    private boolean closed;

    /**
     * Create a {@link RotatingFileAppender} writing access log files in the specified directory and using the specified
     * {@link DateTimeFormatter} pattern to generate file names.
     *
     * <p>
     * For example when the specified pattern is <code>'access-'yyyyMMDDhh'.log'</code> (note the quotes), access log files
     * will be rotated on a hourly basis, and the output will be written to files like <code>access-2013120422.log</code>,
     * <code>access-2013120423.log</code>, ... and so on.
     *
     * @param directory The directory where access log files will be written to.
     * @param filePattern A properly escaped {@link DateTimeFormatter} pattern for the access log files.
     * @throws IOException If an I/O error occurred accessing the filesystem.
     */
    public RotatingFileAppender(File directory, String filePattern) throws IOException {
        this(filePattern, filePattern, directory);
        LOGGER.fine("Creating rotating log appender in \"" + directory + "\" with file pattern \"" + filePattern + "\"");
    }

    /**
     * Create a {@link RotatingFileAppender} writing access log files in the specified directory.
     *
     * <p>
     * When using this constructor the <em>current</em> log file (the one being written to) will <em>always</em> be the one
     * identified by the <code>fileName</code> parameter, and then archival of files will be delegated to the archive
     * pattern.
     * </p>
     *
     * <p>
     * For example when <code>fileName</code> is <code>current.log</code> and <code>archivePattern</code> is
     * <code>'archive-'yyyyMMDD'.log'</code> (note the quotes), access logs will be written to the <code>current.log</code>
     * file and this file will be rotated on a daily basis to files like <code>archive-20131204.log</code>,
     * <code>archive-20131205.log</code>, ... and so on.
     *
     * @param directory The directory where access log files will be written to.
     * @param fileName A file name where log entries will be written to.
     * @param archivePattern A properly escaped {@link DateTimeFormatter} pattern for the access log archive files.
     * @throws IOException If an I/O error occurred accessing the filesystem.
     */
    public RotatingFileAppender(File directory, String fileName, String archivePattern) throws IOException {
        this(escape(fileName), archivePattern, directory);
        LOGGER.fine("Creating rotating log appender in \"" + directory + "\" writing to \"" + fileName + "\" and archive pattern \"" + archivePattern + "\"");
    }

    /* ====================================================================== */

    private static String escape(String fileName) {
        if (fileName == null) {
            throw new NullPointerException("Null file name");
        }
        return "'" + fileName.replace("'", "''") + "'";
    }

    /* ====================================================================== */

    private RotatingFileAppender(String filePattern, String archivePattern, File directory) throws IOException {

        this.directory = directory.getCanonicalFile();
        archiveFormat = DateTimeFormatter.ofPattern(archivePattern);
        fileFormat = DateTimeFormatter.ofPattern(filePattern);

        final Instant now = Instant.now();
        currentArchive = new File(directory, archiveFormat.format(now)).getCanonicalFile();
        currentFile = new File(directory, fileFormat.format(now)).getCanonicalFile();

        /* Validate the arguments */
        if (!this.directory.equals(currentArchive.getParentFile())) {
            throw new IllegalArgumentException("Archive file \"" + currentArchive + "\" is not a child of the configured directory \"" + this.directory + "\"");
        }
        if (!this.directory.equals(currentFile.getParentFile())) {
            throw new IllegalArgumentException("Access log file \"" + currentFile + "\" is not a child of the configured directory \"" + this.directory + "\"");
        }
        if (currentArchive.equals(currentFile)) {
            throw new IllegalArgumentException("Access log file and archive file point to the same file \"" + currentFile + "\"");
        }

        /* Validated, we can open files */
        appender = new FileAppender(currentFile, true);
    }

    /* ====================================================================== */
    /* DO SOME ACTUAL WORK */
    /* ====================================================================== */

    @Override
    public void append(String accessLogEntry) throws IOException {
        if (closed) {
            return;
        }

        /* It's all about date and time */
        final Instant date = Instant.now();
        synchronized (this) {
            /* Calculate the name of the current archive */
            final DateTimeFormatter archiveFormat = this.archiveFormat;
            final File archive = new File(directory, archiveFormat.format(date));

            /* If this archive is *NOT* the one we wrote to last, rotate */
            if (!archive.equals(currentArchive)) {
                try {

                    /* Close our current appender */
                    appender.close();

                    /* If we have different file names, move the file to archive */
                    if (!currentFile.equals(currentArchive)) {
                        LOGGER.info("Archiving \"" + currentFile + "\" to \"" + currentArchive + "\"");
                        if (!currentFile.renameTo(currentArchive)) {
                            throw new IOException("Unable to rename \"" + currentFile + "\" to \"" + currentArchive + "\"");
                        }
                    }

                    /* Save our new state */
                    currentArchive = archive;
                    currentFile = new File(directory, fileFormat.format(date));

                    /* Create our new appender */
                    appender = new FileAppender(currentFile, true);

                } catch (IOException exception) {
                    LOGGER.log(WARNING, "I/O error rotating access log file", exception);
                }
            }

            appender.append(accessLogEntry);
        }

    }

    @Override
    public void close() throws IOException {
        closed = true;
        appender.close();
    }

}
