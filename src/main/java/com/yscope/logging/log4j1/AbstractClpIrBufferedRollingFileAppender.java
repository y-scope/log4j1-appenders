package com.yscope.logging.log4j1;

import java.io.IOException;
import java.nio.file.Paths;

import org.apache.log4j.spi.LoggingEvent;

/**
 * This abstract class extends {@code AbstractBufferedRollingFileAppender} with
 * buffered CLP Intermediate Representation streaming log compression
 * functionality
 */
public abstract class AbstractClpIrBufferedRollingFileAppender
    extends AbstractBufferedRollingFileAppender
{
  public static final String CLP_COMPRESSED_IRSTREAM_FILE_EXTENSION = ".clp.zst";

  protected String baseName;
  protected long lastRolloverTimestamp = System.currentTimeMillis();
  protected ClpIrFileAppender clpIrFileAppender = null;
  protected String outputDir;

  // File size based rollover strategy for streaming compressed logging is
  // governed by both the compressed on-disk size and the size of raw
  // uncompressed content. The former is to ensure a reasonable local and/or
  // remote file size to reduce both the file system overhead and cost during
  // log generation, synchronization with remote log store, accessing and
  // searching the compressed log file at a later time. The uncompressed size
  // is also used to ensure that compressed log files when decompressed back
  // to its original content be opened efficiently by file editors.
  private long rolloverCompressedSizeThreshold = 16 * 1024 * 1024;  // Bytes
  private long compressedSizeSinceLastRollover = 0L;

  private long rolloverUncompressedSizeThreshold = 2L * 1024 * 1024 * 1024;  // Bytes
  private long uncompressedSizeSinceLastRollover = 0L;

  // CLP streaming compression parameters
  private boolean closeFrameOnFlush = true;
  private boolean useFourByteEncoding = false;

  @Override
  public void activateOptionsHook () {
    updateLogFilePath();
    try {
      clpIrFileAppender = new ClpIrFileAppender(currentLogPath, layout, useFourByteEncoding,
                                                closeFrameOnFlush, 3);
    } catch (IOException e) {
      logError("Failed to activate appender", e);
    }
  }

  @Override
  public void appendHook (LoggingEvent event) {
    clpIrFileAppender.append(event);
  }

  @Override
  public void flush () throws IOException {
    clpIrFileAppender.flush();
  }

  public void setRolloverCompressedSizeThreshold (long rolloverCompressedSizeThreshold) {
    this.rolloverCompressedSizeThreshold = rolloverCompressedSizeThreshold;
  }

  public void setRolloverUncompressedSizeThreshold (long rolloverUncompressedSizeThreshold) {
    this.rolloverUncompressedSizeThreshold = rolloverUncompressedSizeThreshold;
  }

  public void setUseFourByteEncoding (boolean useFourByteEncoding) {
    this.useFourByteEncoding = useFourByteEncoding;
  }

  public void setOutputDir (String outputDir) {
    this.outputDir = outputDir;
  }

  public void setBaseName (String baseName) {
    this.baseName = baseName;
  }

  public void setCloseFrameOnFlush (boolean closeFrameOnFlush) {
    this.closeFrameOnFlush = closeFrameOnFlush;
  }

  public long getUncompressedSize () {
    return uncompressedSizeSinceLastRollover + clpIrFileAppender.getUncompressedSize();
  }

  public long getCompressedSize () {
    return compressedSizeSinceLastRollover + clpIrFileAppender.getCompressedSize();
  }

  @Override
  protected boolean rolloverRequired () {
    return getCompressedSize() > rolloverCompressedSizeThreshold
        || getUncompressedSize() > rolloverUncompressedSizeThreshold;
  }

  @Override
  protected void closeHook () {
    clpIrFileAppender.close();
  }

  @Override
  protected void startNewLogFile (long lastRolloverTimestamp) {
    this.lastRolloverTimestamp = lastRolloverTimestamp;
    updateLogFilePath();
    try {
      compressedSizeSinceLastRollover += clpIrFileAppender.getCompressedSize();
      uncompressedSizeSinceLastRollover += clpIrFileAppender.getUncompressedSize();
      clpIrFileAppender.startNewFile(currentLogPath);
    } catch (IOException e) {
      logError("Failed to start new buffered file", e);
    }
  }

  private void updateLogFilePath () {
    currentLogPath = Paths.get(outputDir, baseName + "." + lastRolloverTimestamp
        + CLP_COMPRESSED_IRSTREAM_FILE_EXTENSION).toString();
  }
}
