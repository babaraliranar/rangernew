/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.audit.queue;

import org.apache.ranger.audit.model.AuditEventBase;
import org.apache.ranger.audit.model.AuthzAuditEvent;
import org.apache.ranger.audit.provider.AuditHandler;
import org.apache.ranger.audit.provider.MiscUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * This class temporarily stores logs in Local file system before it despatches each logs in file to the AuditBatchQueue Consumer.
 * This gets instantiated only when AuditFileCacheProvider is enabled (xasecure.audit.provider.filecache.is.enabled).
 * When AuditFileCacheProvider is all the logs are stored in local file system before sent to destination.
 */

public class AuditFileCacheProviderSpool implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(AuditFileCacheProviderSpool.class);

    public static final String PROP_FILE_SPOOL_LOCAL_DIR               = "filespool.dir";
    public static final String PROP_FILE_SPOOL_LOCAL_FILE_NAME         = "filespool.filename.format";
    public static final String PROP_FILE_SPOOL_ARCHIVE_DIR             = "filespool.archive.dir";
    public static final String PROP_FILE_SPOOL_ARCHIVE_MAX_FILES_COUNT = "filespool.archive.max.files";
    public static final String PROP_FILE_SPOOL_FILENAME_PREFIX         = "filespool.file.prefix";
    public static final String PROP_FILE_SPOOL_FILE_ROLLOVER           = "filespool.file.rollover.sec";
    public static final String PROP_FILE_SPOOL_INDEX_FILE              = "filespool.index.filename";
    public static final String PROP_FILE_SPOOL_DEST_RETRY_MS           = "filespool.destination.retry.ms";
    public static final String PROP_FILE_SPOOL_BATCH_SIZE              = "filespool.buffer.size";
    public static final String AUDIT_IS_FILE_CACHE_PROVIDER_ENABLE_PROP = "xasecure.audit.provider.filecache.is.enabled";
    public static final String FILE_CACHE_PROVIDER_NAME                 = "AuditFileCacheProviderSpool";

    AuditHandler                    consumerProvider;
    BlockingQueue<AuditIndexRecord> indexQueue   = new LinkedBlockingQueue<>();
    List<AuditIndexRecord>          indexRecords = new ArrayList<>();

    // Folder and File attributes
    File             logFolder;
    String           logFileNameFormat;
    File             archiveFolder;
    String           fileNamePrefix;
    String           indexFileName;
    File             indexFile;
    String           indexDoneFileName;
    File             indexDoneFile;
    long             lastErrorLogMS;
    boolean          isAuditFileCacheProviderEnabled;
    boolean          closeFile;
    boolean          isPending;
    long             lastAttemptTime;
    boolean          initDone;
    PrintWriter      logWriter;
    AuditIndexRecord currentWriterIndexRecord;
    AuditIndexRecord currentConsumerIndexRecord;
    BufferedReader   logReader;
    Thread           destinationThread;
    boolean          isDrain;
    boolean          isDestDown;
    int              retryDestinationMS   = 30 * 1000; // Default 30 seconds
    int              fileRolloverSec      = 24 * 60 * 60; // In seconds
    int              maxArchiveFiles      = 100;
    int              errorLogIntervalMS   = 30 * 1000; // Every 30 seconds
    int              auditBatchSize       = 1000;
    boolean          isWriting            = true;
    boolean          isSpoolingSuccessful = true;

    public AuditFileCacheProviderSpool(AuditHandler consumerProvider) {
        this.consumerProvider = consumerProvider;
    }

    public void init(Properties prop) {
        init(prop, null);
    }

    public boolean init(Properties props, String basePropertyName) {
        logger.debug("==> AuditFileCacheProviderSpool.init()");

        if (initDone) {
            logger.error("init() called more than once. queueProvider=, consumerProvider={}", consumerProvider.getName());

            return true;
        }

        String propPrefix = "xasecure.audit.filespool";

        if (basePropertyName != null) {
            propPrefix = basePropertyName;
        }

        try {
            // Initial folder and file properties
            String logFolderProp     = MiscUtil.getStringProperty(props, propPrefix + "." + PROP_FILE_SPOOL_LOCAL_DIR);
            String archiveFolderProp = MiscUtil.getStringProperty(props, propPrefix + "." + PROP_FILE_SPOOL_ARCHIVE_DIR);

            logFileNameFormat = MiscUtil.getStringProperty(props, basePropertyName + "." + PROP_FILE_SPOOL_LOCAL_FILE_NAME);

            fileNamePrefix                  = MiscUtil.getStringProperty(props, propPrefix + "." + PROP_FILE_SPOOL_FILENAME_PREFIX);
            indexFileName                   = MiscUtil.getStringProperty(props, propPrefix + "." + PROP_FILE_SPOOL_INDEX_FILE);
            retryDestinationMS              = MiscUtil.getIntProperty(props, propPrefix + "." + PROP_FILE_SPOOL_DEST_RETRY_MS, retryDestinationMS);
            fileRolloverSec                 = MiscUtil.getIntProperty(props, propPrefix + "." + PROP_FILE_SPOOL_FILE_ROLLOVER, fileRolloverSec);
            maxArchiveFiles                 = MiscUtil.getIntProperty(props, propPrefix + "." + PROP_FILE_SPOOL_ARCHIVE_MAX_FILES_COUNT, maxArchiveFiles);
            isAuditFileCacheProviderEnabled = MiscUtil.getBooleanProperty(props, AUDIT_IS_FILE_CACHE_PROVIDER_ENABLE_PROP, false);

            logger.info("retryDestinationMS={}, queueName={}", retryDestinationMS, FILE_CACHE_PROVIDER_NAME);
            logger.info("fileRolloverSec={}, queueName={}", fileRolloverSec, FILE_CACHE_PROVIDER_NAME);
            logger.info("maxArchiveFiles={}, queueName={}", maxArchiveFiles, FILE_CACHE_PROVIDER_NAME);

            if (logFolderProp == null || logFolderProp.isEmpty()) {
                logger.error("Audit spool folder is not configured. Please set {}.{}.queueName={}", propPrefix, PROP_FILE_SPOOL_LOCAL_DIR, FILE_CACHE_PROVIDER_NAME);

                return false;
            }

            logFolder = new File(logFolderProp);

            if (!logFolder.isDirectory()) {
                boolean result = logFolder.mkdirs();

                if (!logFolder.isDirectory() || !result) {
                    logger.error("File Spool folder not found and can't be created. folder={}, queueName={}", logFolder.getAbsolutePath(), FILE_CACHE_PROVIDER_NAME);

                    return false;
                }
            }

            logger.info("logFolder={}, queueName={}", logFolder, FILE_CACHE_PROVIDER_NAME);

            if (logFileNameFormat == null || logFileNameFormat.isEmpty()) {
                logFileNameFormat = "spool_" + "%app-type%" + "_" + "%time:yyyyMMdd-HHmm.ss%.log";
            }

            logger.info("logFileNameFormat={}, queueName={}", logFileNameFormat, FILE_CACHE_PROVIDER_NAME);

            if (archiveFolderProp == null || archiveFolderProp.isEmpty()) {
                archiveFolder = new File(logFolder, "archive");
            } else {
                archiveFolder = new File(archiveFolderProp);
            }

            if (!archiveFolder.isDirectory()) {
                boolean result = archiveFolder.mkdirs();

                if (!archiveFolder.isDirectory() || !result) {
                    logger.error("File Spool archive folder not found and can't be created. folder={}, queueName={}", archiveFolder.getAbsolutePath(), FILE_CACHE_PROVIDER_NAME);

                    return false;
                }
            }

            logger.info("archiveFolder={}, queueName={}", archiveFolder, FILE_CACHE_PROVIDER_NAME);

            if (indexFileName == null || indexFileName.isEmpty()) {
                if (fileNamePrefix == null || fileNamePrefix.isEmpty()) {
                    fileNamePrefix = FILE_CACHE_PROVIDER_NAME + "_" + consumerProvider.getName();
                }

                indexFileName = "index_" + fileNamePrefix + "_" + "%app-type%" + ".json";
                indexFileName = MiscUtil.replaceTokens(indexFileName, System.currentTimeMillis());
            }

            indexFile = new File(logFolder, indexFileName);

            if (!indexFile.exists()) {
                boolean ret = indexFile.createNewFile();

                if (!ret) {
                    logger.error("Error creating index file. fileName={}", indexFile.getPath());

                    return false;
                }
            }

            logger.info("indexFile={}, queueName={}", indexFile, FILE_CACHE_PROVIDER_NAME);

            int lastDot = indexFileName.lastIndexOf('.');

            if (lastDot < 0) {
                lastDot = indexFileName.length() - 1;
            }

            indexDoneFileName = indexFileName.substring(0, lastDot) + "_closed.json";
            indexDoneFile     = new File(logFolder, indexDoneFileName);

            if (!indexDoneFile.exists()) {
                boolean ret = indexDoneFile.createNewFile();

                if (!ret) {
                    logger.error("Error creating index done file. fileName={}", indexDoneFile.getPath());

                    return false;
                }
            }

            logger.info("indexDoneFile={}, queueName={}", indexDoneFile, FILE_CACHE_PROVIDER_NAME);

            // Load index file
            loadIndexFile();
            for (AuditIndexRecord auditIndexRecord : indexRecords) {
                if (!auditIndexRecord.status.equals(SPOOL_FILE_STATUS.done)) {
                    isPending = true;
                }

                if (auditIndexRecord.status.equals(SPOOL_FILE_STATUS.write_inprogress)) {
                    currentWriterIndexRecord = auditIndexRecord;

                    logger.info("currentWriterIndexRecord={}, queueName={}", currentWriterIndexRecord.filePath, FILE_CACHE_PROVIDER_NAME);
                }

                if (auditIndexRecord.status.equals(SPOOL_FILE_STATUS.read_inprogress)) {
                    indexQueue.add(auditIndexRecord);
                }
            }

            printIndex();

            for (AuditIndexRecord auditIndexRecord : indexRecords) {
                if (auditIndexRecord.status.equals(SPOOL_FILE_STATUS.pending)) {
                    File consumerFile = new File(auditIndexRecord.filePath);

                    if (!consumerFile.exists()) {
                        logger.error("INIT: Consumer file={} not found.", consumerFile.getPath());
                    } else {
                        indexQueue.add(auditIndexRecord);
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Error initializing File Spooler. queue=" + FILE_CACHE_PROVIDER_NAME, t);

            return false;
        }

        auditBatchSize = MiscUtil.getIntProperty(props, propPrefix + "." + PROP_FILE_SPOOL_BATCH_SIZE, auditBatchSize);
        initDone       = true;

        logger.debug("<== AuditFileCacheProviderSpool.init()");
        return true;
    }

    /**
     * Start looking for outstanding logs and update status according.
     */
    public void start() {
        if (!initDone) {
            logger.error("Cannot start Audit File Spooler. Initilization not done yet. queueName={}", FILE_CACHE_PROVIDER_NAME);

            return;
        }

        logger.info("Starting writerThread, queueName={}, consumer={}", FILE_CACHE_PROVIDER_NAME, consumerProvider.getName());

        // Let's start the thread to read
        destinationThread = new Thread(this, FILE_CACHE_PROVIDER_NAME + "_" + consumerProvider.getName() + "_destWriter");

        destinationThread.setDaemon(true);
        destinationThread.start();
    }

    public void stop() {
        if (!initDone) {
            logger.error("Cannot stop Audit File Spooler. Initilization not done. queueName={}", FILE_CACHE_PROVIDER_NAME);

            return;
        }

        logger.info("Stop called, queueName={}, consumer={}", FILE_CACHE_PROVIDER_NAME, consumerProvider.getName());

        isDrain = true;

        flush();

        PrintWriter out = getOpenLogFileStream();

        if (out != null) {
            // If write is still going on, then let's give it enough time to
            // complete
            for (int i = 0; i < 3; i++) {
                if (isWriting) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // ignore
                    }

                    continue;
                }

                try {
                    logger.info("Closing open file, queueName={}, consumer={}", FILE_CACHE_PROVIDER_NAME, consumerProvider.getName());

                    out.flush();
                    out.close();

                    break;
                } catch (Throwable t) {
                    logger.debug("Error closing spool out file.", t);
                }
            }
        }

        try {
            if (destinationThread != null) {
                destinationThread.interrupt();
            }

            destinationThread = null;
        } catch (Throwable e) {
            // ignore
        }
    }

    public void flush() {
        if (!initDone) {
            logger.error("Cannot flush Audit File Spooler. Initilization not done. queueName=" + FILE_CACHE_PROVIDER_NAME);

            return;
        }

        PrintWriter out = getOpenLogFileStream();

        if (out != null) {
            out.flush();
        }
    }

    /**
     * If any files are still not processed. Also, if the destination is not
     * reachable
     *
     * @return
     */
    public boolean isPending() {
        if (!initDone) {
            logError("isPending(): File Spooler not initialized. queueName=" + FILE_CACHE_PROVIDER_NAME);

            return false;
        }

        return isPending;
    }

    /**
     * Milliseconds from last attempt time
     *
     * @return
     */
    public long getLastAttemptTimeDelta() {
        if (lastAttemptTime == 0) {
            return 0;
        }

        return System.currentTimeMillis() - lastAttemptTime;
    }

    public synchronized void stashLogs(AuditEventBase event) {
        if (isDrain) {
            // Stop has been called, so this method shouldn't be called
            logger.error("stashLogs() is called after stop is called. event={}", event);

            return;
        }

        try {
            isWriting = true;

            PrintWriter logOut  = getLogFileStream();
            String      jsonStr = MiscUtil.stringify(event); // Convert event to json

            logOut.println(jsonStr);
            logOut.flush();

            isPending            = true;
            isSpoolingSuccessful = true;
        } catch (Throwable t) {
            isSpoolingSuccessful = false;

            logger.error("Error writing to file. event={}", event, t);
        } finally {
            isWriting = false;
        }
    }

    public synchronized void stashLogs(Collection<AuditEventBase> events) {
        for (AuditEventBase event : events) {
            stashLogs(event);
        }

        flush();
    }

    public synchronized void stashLogsString(String event) {
        if (isDrain) {
            // Stop has been called, so this method shouldn't be called
            logger.error("stashLogs() is called after stop is called. event={}", event);

            return;
        }

        try {
            isWriting = true;

            PrintWriter logOut = getLogFileStream();

            logOut.println(event);
        } catch (Exception ex) {
            logger.error("Error writing to file. event={}", event, ex);
        } finally {
            isWriting = false;
        }
    }

    public synchronized boolean isSpoolingSuccessful() {
        return isSpoolingSuccessful;
    }

    public synchronized void stashLogsString(Collection<String> events) {
        for (String event : events) {
            stashLogsString(event);
        }

        flush();
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
        try {
            //This is done to clear the MDC context to avoid issue with Ranger Auditing for Knox
            MDC.clear();
            runLogAudit();
        } catch (Throwable t) {
            logger.error("Exited thread without abnormaly. queue={}", consumerProvider.getName(), t);
        }
    }

    public void runLogAudit() {
        // boolean isResumed = false;
        while (true) {
            try {
                if (isDestDown) {
                    logger.info("Destination is down. sleeping for {} milli seconds. indexQueue={}, queueName={}, consumer={}", retryDestinationMS, indexQueue.size(), FILE_CACHE_PROVIDER_NAME, consumerProvider.getName());

                    Thread.sleep(retryDestinationMS);
                }

                // Let's pause between each iteration
                if (currentConsumerIndexRecord == null) {
                    currentConsumerIndexRecord = indexQueue.poll(retryDestinationMS, TimeUnit.MILLISECONDS);
                } else {
                    Thread.sleep(retryDestinationMS);
                }

                if (isDrain) {
                    // Need to exit
                    break;
                }

                if (currentConsumerIndexRecord == null) {
                    closeFileIfNeeded();

                    continue;
                }

                boolean isRemoveIndex = false;
                File    consumerFile  = new File(currentConsumerIndexRecord.filePath);

                if (!consumerFile.exists()) {
                    logger.error("Consumer file={} not found.", consumerFile.getPath());

                    printIndex();

                    isRemoveIndex = true;
                } else {
                    // Let's open the file to write
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(currentConsumerIndexRecord.filePath), StandardCharsets.UTF_8))) {
                        int                  startLine = currentConsumerIndexRecord.linePosition;
                        int                  currLine  = 0;
                        List<AuditEventBase> events    = new ArrayList<>();

                        for (String line = br.readLine(); line != null; line = br.readLine()) {
                            currLine++;

                            if (currLine < startLine) {
                                continue;
                            }

                            AuditEventBase event = MiscUtil.fromJson(line, AuthzAuditEvent.class);

                            events.add(event);

                            if (events.size() == auditBatchSize) {
                                boolean ret = sendEvent(events, currentConsumerIndexRecord, currLine);

                                if (!ret) {
                                    throw new Exception("Destination down");
                                }

                                events.clear();
                            }
                        }

                        if (!events.isEmpty()) {
                            boolean ret = sendEvent(events, currentConsumerIndexRecord, currLine);

                            if (!ret) {
                                throw new Exception("Destination down");
                            }

                            events.clear();
                        }

                        logger.info("Done reading file. file={}, queueName={}, consumer={}", currentConsumerIndexRecord.filePath, FILE_CACHE_PROVIDER_NAME, consumerProvider.getName());

                        // The entire file is read
                        currentConsumerIndexRecord.status           = SPOOL_FILE_STATUS.done;
                        currentConsumerIndexRecord.doneCompleteTime = new Date();
                        currentConsumerIndexRecord.lastAttempt      = true;

                        isRemoveIndex = true;
                    } catch (Exception ex) {
                        isDestDown = true;

                        logError("Destination down. queueName=" + FILE_CACHE_PROVIDER_NAME + ", consumer=" + consumerProvider.getName());

                        lastAttemptTime = System.currentTimeMillis();
                        // Update the index file
                        currentConsumerIndexRecord.lastFailedTime = new Date();
                        currentConsumerIndexRecord.failedAttemptCount++;
                        currentConsumerIndexRecord.lastAttempt = false;

                        saveIndexFile();
                    }
                }

                if (isRemoveIndex) {
                    // Remove this entry from index
                    removeIndexRecord(currentConsumerIndexRecord);

                    currentConsumerIndexRecord = null;

                    closeFileIfNeeded();
                }
            } catch (InterruptedException e) {
                logger.info("Caught exception in consumer thread. Shutdown might be in progress");
            } catch (Throwable t) {
                logger.error("Exception in destination writing thread.", t);
            }
        }

        logger.info("Exiting file spooler. provider={}, consumer={}", FILE_CACHE_PROVIDER_NAME, consumerProvider.getName());
    }

    /**
     * Load the index file
     *
     * @throws IOException
     */
    void loadIndexFile() throws IOException {
        logger.info("Loading index file. fileName={}", indexFile.getPath());

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(indexFile), StandardCharsets.UTF_8))) {
            indexRecords.clear();

            for (String line = br.readLine(); line != null; line = br.readLine()) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    try {
                        AuditIndexRecord record = MiscUtil.fromJson(line, AuditIndexRecord.class);

                        indexRecords.add(record);
                    } catch (Exception e) {
                        logger.error("Error parsing following JSON: {}", line, e);
                    }
                }
            }
        }
    }

    synchronized void printIndex() {
        logger.info("INDEX printIndex() ==== START");

        for (AuditIndexRecord record : indexRecords) {
            logger.info("INDEX={}, isFileExist={}", record, (new File(record.filePath).exists()));
        }

        logger.info("INDEX printIndex() ==== END");
    }

    synchronized void removeIndexRecord(AuditIndexRecord indexRecord) throws IOException {
        Iterator<AuditIndexRecord> iter = indexRecords.iterator();

        while (iter.hasNext()) {
            AuditIndexRecord record = iter.next();

            if (record.id.equals(indexRecord.id)) {
                logger.info("Removing file from index. file={}, queueName={}, consumer={}", record.filePath, FILE_CACHE_PROVIDER_NAME, consumerProvider.getName());

                iter.remove();

                appendToDoneFile(record);
            }
        }

        saveIndexFile();

        // If there are no more files in the index, then let's assume the destination is now available
        if (indexRecords.isEmpty()) {
            isPending = false;
        }
    }

    synchronized void saveIndexFile() throws IOException {
        PrintWriter out = new PrintWriter(indexFile, StandardCharsets.UTF_8.name());

        for (AuditIndexRecord auditIndexRecord : indexRecords) {
            out.println(MiscUtil.stringify(auditIndexRecord));
        }

        out.close();
        // printIndex();
    }

    void appendToDoneFile(AuditIndexRecord indexRecord) throws IOException {
        logger.info("Moving to done file. {}, queueName={}, consumer={}", indexRecord.filePath, FILE_CACHE_PROVIDER_NAME, consumerProvider.getName());

        try (PrintWriter out  = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(indexDoneFile, true), StandardCharsets.UTF_8)))) {
            String line = MiscUtil.stringify(indexRecord);

            out.println(line);
            out.flush();
        }

        // After Each file is read and audit events are pushed into pipe, we flush to reach the destination immediate.
        consumerProvider.flush();

        // Move to archive folder
        File logFile     = null;
        File archiveFile = null;

        try {
            logFile     = new File(indexRecord.filePath);
            archiveFile = new File(archiveFolder, logFile.getName());

            logger.info("Moving logFile {} to {}", logFile, archiveFile);

            boolean result = logFile.renameTo(archiveFile);

            if (!result) {
                logger.error("Error moving log file to archive folder. Unable to rename {} to archiveFile={}", logFile, archiveFile);
            }
        } catch (Throwable t) {
            logger.error("Error moving log file to archive folder. logFile={}, archiveFile={}", logFile, archiveFile, t);
        }

        // After archiving the file flush the pipe
        consumerProvider.flush();

        archiveFile = null;

        try {
            // Remove old files
            File[] logFiles = archiveFolder.listFiles(pathname -> pathname.getName().toLowerCase().endsWith(".log"));

            if (logFiles != null && logFiles.length > maxArchiveFiles) {
                int filesToDelete = logFiles.length - maxArchiveFiles;

                try (BufferedReader br = new BufferedReader(new FileReader(indexDoneFile))) {
                    int filesDeletedCount = 0;

                    for (String line = br.readLine(); line != null; line = br.readLine()) {
                        if (!line.isEmpty() && !line.startsWith("#")) {
                            try {
                                AuditIndexRecord record = MiscUtil.fromJson(line, AuditIndexRecord.class);

                                logFile     = new File(record.filePath);
                                archiveFile = new File(archiveFolder, logFile.getName());

                                if (archiveFile.exists()) {
                                    logger.info("Deleting archive file {}", archiveFile);

                                    boolean ret = archiveFile.delete();

                                    if (!ret) {
                                        logger.error("Error deleting archive file. archiveFile={}", archiveFile);
                                    }

                                    filesDeletedCount++;

                                    if (filesDeletedCount >= filesToDelete) {
                                        logger.info("Deleted {} files", filesDeletedCount);

                                        break;
                                    }
                                }
                            } catch (Exception e) {
                                logger.error("Error parsing following JSON: {}", line, e);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Error deleting older archive file. archiveFile={}", archiveFile, t);
        }
    }

    void logError(String msg) {
        long currTimeMS = System.currentTimeMillis();

        if (currTimeMS - lastErrorLogMS > errorLogIntervalMS) {
            logger.error(msg);

            lastErrorLogMS = currTimeMS;
        }
    }

    /**
     * This return the current file. If there are not current open output file,
     * then it will return null
     *
     * @return
     */
    private synchronized PrintWriter getOpenLogFileStream() {
        return logWriter;
    }

    /**
     * @return
     * @throws Exception
     */
    private synchronized PrintWriter getLogFileStream() throws Exception {
        closeFileIfNeeded();

        // Either there are no open log file or the previous one has been rolled over
        if (currentWriterIndexRecord == null) {
            // Create a new file
            Date   currentTime = new Date();
            String fileName    = MiscUtil.replaceTokens(logFileNameFormat, currentTime.getTime());
            String newFileName = fileName;
            File   outLogFile;
            int    i           = 0;

            while (true) {
                outLogFile = new File(logFolder, newFileName);

                File archiveLogFile = new File(archiveFolder, newFileName);

                if (!outLogFile.exists() && !archiveLogFile.exists()) {
                    break;
                }

                i++;

                int    lastDot   = fileName.lastIndexOf('.');
                String baseName  = fileName.substring(0, lastDot);
                String extension = fileName.substring(lastDot);

                newFileName = baseName + "." + i + extension;
            }

            fileName = newFileName;

            logger.info("Creating new file. queueName={}, fileName={}", FILE_CACHE_PROVIDER_NAME, fileName);

            // Open the file
            logWriter = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outLogFile), StandardCharsets.UTF_8)));

            AuditIndexRecord tmpIndexRecord = new AuditIndexRecord();

            tmpIndexRecord.id             = MiscUtil.generateUniqueId();
            tmpIndexRecord.filePath       = outLogFile.getPath();
            tmpIndexRecord.status         = SPOOL_FILE_STATUS.write_inprogress;
            tmpIndexRecord.fileCreateTime = currentTime;
            tmpIndexRecord.lastAttempt    = true;

            currentWriterIndexRecord      = tmpIndexRecord;

            indexRecords.add(currentWriterIndexRecord);

            saveIndexFile();
        } else {
            if (logWriter == null) {
                // This means the process just started. We need to open the file in append mode.
                logger.info("Opening existing file for append. queueName={}, fileName={}", FILE_CACHE_PROVIDER_NAME, currentWriterIndexRecord.filePath);

                logWriter = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(currentWriterIndexRecord.filePath, true), StandardCharsets.UTF_8)));
            }
        }

        return logWriter;
    }

    private synchronized void closeFileIfNeeded() throws IOException {
        // Is there file open to write or there are no pending file, then close the active file

        if (currentWriterIndexRecord != null) {
            // Check whether the file needs to rolled
            rollOverSpoolFileByTime();

            if (closeFile) {
                // Roll the file
                if (logWriter != null) {
                    logWriter.flush();
                    logWriter.close();

                    logWriter = null;
                    closeFile = false;
                }

                currentWriterIndexRecord.status            = SPOOL_FILE_STATUS.pending;
                currentWriterIndexRecord.writeCompleteTime = new Date();

                saveIndexFile();

                logger.info("Adding file to queue. queueName={}, fileName={}", FILE_CACHE_PROVIDER_NAME, currentWriterIndexRecord.filePath);

                indexQueue.add(currentWriterIndexRecord);

                currentWriterIndexRecord = null;
            }
        }
    }

    private void rollOverSpoolFileByTime() {
        if (System.currentTimeMillis() - currentWriterIndexRecord.fileCreateTime.getTime() > fileRolloverSec * 1000L) {
            closeFile = true;

            logger.info("Closing file. Rolling over. queueName={}, fileName={}", FILE_CACHE_PROVIDER_NAME, currentWriterIndexRecord.filePath);
        }
    }

    private boolean sendEvent(List<AuditEventBase> events, AuditIndexRecord indexRecord, int currLine) {
        boolean ret = true;

        try {
            ret = consumerProvider.log(events);

            if (!ret) {
                // Need to log error after fixed interval
                logError("Error sending logs to consumer. provider=" + FILE_CACHE_PROVIDER_NAME + ", consumer=" + consumerProvider.getName());
            } else {
                // Update index and save
                indexRecord.linePosition    = currLine;
                indexRecord.status          = SPOOL_FILE_STATUS.read_inprogress;
                indexRecord.lastSuccessTime = new Date();
                indexRecord.lastAttempt     = true;

                saveIndexFile();

                if (isDestDown) {
                    isDestDown = false;

                    logger.info("Destination up now. {}, queueName={}, consumer={}", indexRecord.filePath, FILE_CACHE_PROVIDER_NAME, consumerProvider.getName());
                }
            }
        } catch (Throwable t) {
            logger.error("Error while sending logs to consumer. provider={}, consumer={}, log={}", FILE_CACHE_PROVIDER_NAME, consumerProvider.getName(), events, t);
        }

        return ret;
    }

    public enum SPOOL_FILE_STATUS {
        pending, write_inprogress, read_inprogress, done
    }

    static class AuditIndexRecord {
        String            id;
        String            filePath;
        int               linePosition;
        SPOOL_FILE_STATUS status = SPOOL_FILE_STATUS.write_inprogress;
        Date              fileCreateTime;
        Date              writeCompleteTime;
        Date              doneCompleteTime;
        Date              lastSuccessTime;
        Date              lastFailedTime;
        int               failedAttemptCount;
        boolean           lastAttempt;

        @Override
        public String toString() {
            return "AuditIndexRecord [id=" + id + ", filePath=" + filePath
                    + ", linePosition=" + linePosition + ", status=" + status
                    + ", fileCreateTime=" + fileCreateTime
                    + ", writeCompleteTime=" + writeCompleteTime
                    + ", doneCompleteTime=" + doneCompleteTime
                    + ", lastSuccessTime=" + lastSuccessTime
                    + ", lastFailedTime=" + lastFailedTime
                    + ", failedAttemptCount=" + failedAttemptCount
                    + ", lastAttempt=" + lastAttempt + "]";
        }
    }
}
