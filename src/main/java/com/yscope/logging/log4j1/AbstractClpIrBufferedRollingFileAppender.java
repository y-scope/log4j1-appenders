package com.yscope.logging.log4j1;

import java.io.IOException;
import java.nio.file.Paths;

import org.apache.log4j.spi.LoggingEvent;

/**
 * This class extends {@link AbstractBufferedRollingFileAppender} to append to
 * CLP compressed IR-stream files and rollover based on the amount of
 * uncompressed and compressed data written to a file.
 * <p></p>
 * Rollover based on the amount of uncompressed data written to file allows us
 * to ensure that the file remains manageable when decompressed for viewing,
 * etc.
 * <p></p>
 * Rollover based on the amount of compressed data written to file allows us to
 * ensure the file is large enough to amortize filesystem overhead, and small
 * enough to be performant when uploading to remote storage as well as when
 * accessing and searching the compressed file.
 */
public abstract class AbstractClpIrBufferedRollingFileAppender
    extends AbstractBufferedRollingFileAppender
{
  public static final String CLP_COMPRESSED_IRSTREAM_FILE_EXTENSION = ".clp.zst";

  // Appender settings, some of which may be set by Log4j through reflection.
  // For descriptions of the properties, see their setters below.
  private String baseName;
  private String outputDir;
  // CLP streaming compression parameters
  private boolean closeFrameOnFlush = true;
  private boolean useFourByteEncoding = false;
  private long rolloverCompressedSizeThreshold = 16 * 1024 * 1024;  // Bytes
  private long rolloverUncompressedSizeThreshold = 2L * 1024 * 1024 * 1024;  // Bytes

  private long compressedSizeSinceLastRollover = 0L;  // Bytes
  private long uncompressedSizeSinceLastRollover = 0L;  // Bytes

  private ClpIrFileAppender clpIrFileAppender = null;

  /**
   * Sets the threshold for the file's compressed size at which rollover should
   * be triggered.
   * @param rolloverCompressedSizeThreshold The threshold size in bytes
   */
  public void setRolloverCompressedSizeThreshold (long rolloverCompressedSizeThreshold) {
    this.rolloverCompressedSizeThreshold = rolloverCompressedSizeThreshold;
  }

  /**
   * Sets the threshold for the file's uncompressed size at which rollover
   * should be triggered.
   * @param rolloverUncompressedSizeThreshold The threshold size in bytes
   */
  public void setRolloverUncompressedSizeThreshold (long rolloverUncompressedSizeThreshold) {
    this.rolloverUncompressedSizeThreshold = rolloverUncompressedSizeThreshold;
  }

  /**
   * @param useFourByteEncoding Whether to use CLP's four-byte encoding instead
   * of the default eight-byte encoding
   */
  public void setUseFourByteEncoding (boolean useFourByteEncoding) {
    this.useFourByteEncoding = useFourByteEncoding;
  }

  /**
   * @param outputDir The output directory path for log files
   */
  public void setOutputDir (String outputDir) {
    this.outputDir = outputDir;
  }

  /**
   * @param baseName The base filename for log files
   */
  public void setBaseName (String baseName) {
    this.baseName = baseName;
  }

  /**
   * @param closeFrameOnFlush Whether to close the compressor's frame on flush
   */
  public void setCloseFrameOnFlush (boolean closeFrameOnFlush) {
    this.closeFrameOnFlush = closeFrameOnFlush;
  }

  /**
   * @return The uncompressed size of all log events processed by this appender
   * in bytes.
   */
  public long getUncompressedSize () {
    return uncompressedSizeSinceLastRollover + clpIrFileAppender.getUncompressedSize();
  }

  /**
   * @return The compressed size of all log events processed by this appender in
   * bytes.
   */
  public long getCompressedSize () {
    return compressedSizeSinceLastRollover + clpIrFileAppender.getCompressedSize();
  }

  @Override
  public void activateOptionsHook () throws IOException {
    computeLogFilePath(System.currentTimeMillis());
    clpIrFileAppender = new ClpIrFileAppender(currentLogPath, layout, useFourByteEncoding,
                                              closeFrameOnFlush, 3);
  }

  @Override
  protected void closeHook () {
    clpIrFileAppender.close();
  }

  @Override
  protected boolean rolloverRequired () {
    return clpIrFileAppender.getCompressedSize() > rolloverCompressedSizeThreshold
        || clpIrFileAppender.getUncompressedSize() > rolloverUncompressedSizeThreshold;
  }

  @Override
  protected void startNewLogFile (long lastRolloverTimestamp) throws IOException {
    compressedSizeSinceLastRollover += clpIrFileAppender.getCompressedSize();
    uncompressedSizeSinceLastRollover += clpIrFileAppender.getUncompressedSize();
    computeLogFilePath(lastRolloverTimestamp);
    clpIrFileAppender.startNewFile(currentLogPath);
  }

  @Override
  public void appendHook (LoggingEvent event) {
    clpIrFileAppender.append(event);
  }

  @Override
  public void flush () throws IOException {
    clpIrFileAppender.flush();
  }

  /**
   * Computes the current log file path which includes the given rollover
   * timestamp
   * @param lastRolloverTimestamp The approximate timestamp of the last rollover
   */
  private void computeLogFilePath (long lastRolloverTimestamp) {
    currentLogPath = Paths.get(outputDir, baseName + "." + lastRolloverTimestamp
        + CLP_COMPRESSED_IRSTREAM_FILE_EXTENSION).toString();
  }
}
