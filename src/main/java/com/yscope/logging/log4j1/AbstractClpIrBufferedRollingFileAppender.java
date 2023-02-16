package com.yscope.logging.log4j1;

import org.apache.log4j.spi.LoggingEvent;

import java.io.IOException;

/**
 * This abstract class extends {@code AbstractBufferedRollingFileAppender} with
 * buffered CLP streaming log compression functionality
 */
public abstract class AbstractClpBufferedRollingFileAppender
        extends AbstractBufferedRollingFileAppender
{
    // File size based rollover strategy for streaming compressed logging is
    // governed by both the compressed on-disk size and the size of raw
    // uncompressed content. The former is to ensure a reasonable local and/or
    // remote file size to reduce both the file system overhead and cost during
    // log generation, synchronization with remote log store, accessing and
    // searching the compressed log file at a later time. The uncompressed size
    // is also used to ensure that compressed log files when decompressed back
    // to its original content be opened efficiently by file editors.
    private long compressedRolloverSize = 16 * 1024 * 1024;
    private long compressedSizeSinceLastRollover = 0L;

    private long uncompressedRolloverSize = 2L * 1024 * 1024 * 1024;
    private long uncompressedSizeSinceLastRollover = 0L;

    // CLP streaming compression parameters
    private int compressionLevel = 3;
    private boolean closeFrameOnFlush = true;
    private boolean useCompactEncoding = false;

    protected String baseName;
    protected ClpIrFileAppender clpIrFileAppender = null;
    protected String currentFileName;
    protected String outputDir;

    public static final String CLP_COMPRESSED_IRSTREAM_FILE_EXTENSION = ".clp.zst";

    public void setCompressedRolloverSize(long compressedRolloverSize) {
        this.compressedRolloverSize = compressedRolloverSize;
    }

    public void setUncompressedRolloverSize(long uncompressedRolloverSize) {
        this.uncompressedRolloverSize = uncompressedRolloverSize;
    }

    public void setUseCompactEncoding(boolean useCompactEncoding) {
        this.useCompactEncoding = useCompactEncoding;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public void setBaseName(String baseName) {
        this.baseName = baseName;
    }

    public long getUncompressedSize() {
        return uncompressedSizeSinceLastRollover + clpIrFileAppender.getUncompressedSize();
    }

    public long getCompressedSize() {
        return compressedSizeSinceLastRollover + clpIrFileAppender.getCompressedSize();
    }

    @Override
    public void activateOptions() {
        super.activateOptions();
        try {
            clpIrFileAppender = new ClpIrFileAppender(currentLogPath, layout, useCompactEncoding,
                    closeFrameOnFlush, compressionLevel);
        } catch (IOException e) {
            logError("Failed to activate appender", e);
        }
    }

    @Override
    public void appendBufferedFile(LoggingEvent loggingEvent) {
        clpIrFileAppender.append(loggingEvent);
    }

    @Override
    protected boolean rolloverRequired() {
        return getCompressedSize() > compressedRolloverSize ||
                getUncompressedSize() > uncompressedRolloverSize;
    }

    @Override
    protected void closeOldBufferedFile() {
        clpIrFileAppender.close();
        compressedRolloverSize += clpIrFileAppender.getCompressedSize();
        uncompressedSizeSinceLastRollover += clpIrFileAppender.getUncompressedSize();
    }

    protected void updateLogFileName() {
        currentFileName = baseName + "." + lastRolloverTimestamp
                + CLP_COMPRESSED_IRSTREAM_FILE_EXTENSION;
    }

    @Override
    protected void updateLogFilePath() {
        updateLogFileName();
        currentLogPath = outputDir = "/" + currentFileName;
    }

    @Override
    protected void startNewBufferedFile() {
        try {
            clpIrFileAppender.startNewFile(currentLogPath);
        } catch (IOException e) {
            logError("Failed to start new buffered file", e);
        }
    }

    @Override
    public void flush() throws IOException {
        clpIrFileAppender.flush();
    }
}
