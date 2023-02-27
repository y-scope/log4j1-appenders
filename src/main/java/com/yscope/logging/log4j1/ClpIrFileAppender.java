package com.yscope.logging.log4j1;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZonedDateTime;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdOutputStream;
import com.yscope.clp.irstream.AbstractClpIrOutputStream;
import com.yscope.clp.irstream.EightByteClpIrOutputStream;
import com.yscope.clp.irstream.FourByteClpIrOutputStream;
import org.apache.commons.io.output.CountingOutputStream;
import org.apache.log4j.EnhancedPatternLayout;
import org.apache.log4j.HTMLLayout;
import org.apache.log4j.Layout;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.SimpleLayout;
import org.apache.log4j.spi.ErrorCode;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.log4j.xml.XMLLayout;

/**
 * A Log4j appender that writes log events into a Zstandard-compressed CLP IR
 * stream file.
 * <p></p>
 * Since this appender buffers data in the process of compressing the output,
 * derived appenders should ensure the appender is closed even when the program
 * exits uncleanly. Otherwise, the compressed output may be truncated. When the
 * appender is used directly from Log4j, we install a shutdown hook for this
 * purpose.
 */
public class ClpIrFileAppender extends EnhancedAppenderSkeleton implements Flushable {
  private String timestampPattern = null;

  private int estimatedFormattedTimestampLength = 0;
  private long uncompressedSizeInBytes = 0;

  private final ByteArrayOutputStream messageWithThrowableBuffer = new ByteArrayOutputStream();

  private AbstractClpIrOutputStream clpIrOutputStream;
  private CountingOutputStream countingOutputStream;

  private boolean activated = false;

  // Appender settings
  // NOTE: It may appear that these settings are never set but Log4j sets these
  // through reflection
  // For descriptions of these settings, see the (non-default) constructor
  private int compressionLevel = 3;
  private boolean closeFrameOnFlush = true;
  private String file;
  private boolean useFourByteEncoding = false;

  /**
   * Default constructor (necessary for Log4j to instantiate the appender using
   * reflection).
   */
  public ClpIrFileAppender () {
  }

  /**
   * Constructs a ClpIrFileAppender
   * @param filePath Output file path
   * @param layout Log4j layout for formatting log events. Only
   * {@code org.apache.log4j.EnhancedPatternLayout},
   * {@code org.apache.log4j.PatternLayout}, and
   * {@code org.apache.log4j.SimpleLayout} are supported. For PatternLayouts,
   * callers should not add a date conversion pattern since this appender
   * stores timestamps and messages separately. Any date patterns found in the
   * conversion pattern will be removed.
   * @param useFourByteEncoding Whether to use CLP's four-byte encoding instead
   * of the default eight-byte encoding. The four-byte encoding has lower
   * memory usage but can also result in lower compression ratio.
   * @param closeFrameOnFlush Whether to close the Zstandard frame on flush
   * @param compressionLevel Compression level to use for Zstandard. Valid
   * levels are 1 to 19.
   * @throws IOException on I/O error
   */
  public ClpIrFileAppender (
      String filePath,
      Layout layout,
      boolean useFourByteEncoding,
      boolean closeFrameOnFlush,
      int compressionLevel
  ) throws IOException {
    super();

    setFile(filePath);
    setLayout(layout);
    setUseFourByteEncoding(useFourByteEncoding);
    setCompressionLevel(compressionLevel);
    setCloseFrameOnFlush(closeFrameOnFlush);

    // NOTE: We don't enable the shutdown hook since the caller should handle
    // closing the appender properly when shutting down (enabling the hook may
    // also be confusing).
    activateOptionsHelper(false);
  }

  // Public methods
  public void setCompressionLevel (int compressionLevel) {
    this.compressionLevel = compressionLevel;
  }

  public void setCloseFrameOnFlush (boolean closeFrameOnFlush) {
    this.closeFrameOnFlush = closeFrameOnFlush;
  }

  public void setUseFourByteEncoding (boolean useFourByteEncoding) {
    this.useFourByteEncoding = useFourByteEncoding;
  }

  public void setFile (String file) {
    this.file = file;
  }

  public synchronized String getFile () {
    return file;
  }

  /**
   * @return The amount of data written to this appender for the current output
   * file, in bytes.
   * <p></p>
   * NOTE:
   * <ul>
   *   <li>This may be slightly inaccurate since we use an estimate of the
   *   timestamp length for performance reasons.</li>
   *   <li>This will be reset when a new output file is opened.</li>
   * </ul>
   */
  public synchronized long getUncompressedSize () {
    return uncompressedSizeInBytes;
  }

  /**
   * @return The amount of data written by this appender to the current output
   * file, in bytes. This will be reset when a new output file is opened.
   */
  public synchronized long getCompressedSize () {
    return countingOutputStream.getByteCount();
  }

  /**
   * Closes the previous file and starts a new file with the given path
   * @param path
   * @throws IOException on I/O error
   */
  public synchronized void startNewFile (String path) throws IOException {
    if (false == activated) {
      throw new IllegalStateException("Appender not activated.");
    }

    if (closed) {
      throw new IllegalStateException("Appender already closed.");
    }

    clpIrOutputStream.close();
    uncompressedSizeInBytes = 0;

    setFile(path);
    sanitizeFilePath();
    createOutputStream();
  }

  /**
   * Activates the appender's options. This should not be called when this
   * appender is instantiated manually.
   */
  @Override
  public void activateOptions () {
    if (closed) {
      logWarn("Already closed so cannot activate options.");
      return;
    }

    if (activated) {
      logWarn("Already activated.");
      return;
    }

    try {
      activateOptionsHelper(true);
    } catch (Exception ex) {
      logError("Failed to activate appender.", ex);
      closed = true;
    }
  }

  // Overridden methods
  /**
   * Flushes the appender. If closeFrameOnFlush was set, the Zstandard frame is
   * closed and all log events are written to the output stream. If not, then
   * some log events may still be buffered in memory.
   * @throws IOException on I/O error
   */
  @Override
  public synchronized void flush () throws IOException {
    clpIrOutputStream.flush();
  }

  /**
   * Appends the given log event to the IR stream
   * @param event The log event
   */
  @Override
  public synchronized void append (LoggingEvent event) {
    try {
      ByteBuffer message;
      String formattedEvent = layout.format(event);
      byte[] formattedEventBytes = formattedEvent.getBytes(StandardCharsets.ISO_8859_1);
      if (false == layout.ignoresThrowable()) {
        message = ByteBuffer.wrap(formattedEventBytes);
      } else {
        String[] s = event.getThrowableStrRep();
        if (null == s) {
          message = ByteBuffer.wrap(formattedEventBytes);
        } else {
          messageWithThrowableBuffer.write(formattedEventBytes);
          Utils.writeThrowableStrRepresentation(s, messageWithThrowableBuffer);
          message = ByteBuffer.wrap(messageWithThrowableBuffer.toByteArray());
          messageWithThrowableBuffer.reset();
        }
      }

      clpIrOutputStream.writeLogEvent(event.timeStamp, message);
      uncompressedSizeInBytes += estimatedFormattedTimestampLength + message.limit();
    } catch (IOException ex) {
      getErrorHandler().error("Failed to write log event.", ex, ErrorCode.WRITE_FAILURE);
    }
  }

  /**
   * Closes the appender. Once closed, the appender cannot be reopened.
   */
  @Override
  public synchronized void close () {
    if (closed) {
      return;
    }

    try {
      clpIrOutputStream.close();
    } catch (IOException ex) {
      logError("Failed to close output file.", ex);
    }

    closed = true;
  }

  /**
   * @return Whether the appender requires a layout
   */
  @Override
  public boolean requiresLayout () {
    return true;
  }

  // Private methods
  /**
   * Helper method to activate options.
   * @param enableShutdownHook Whether to enable a shutdown hook to close the
   * appender.
   * @throws IOException on I/O error
   */
  private void activateOptionsHelper (boolean enableShutdownHook) throws IOException {
    super.activateOptions();

    validateOptionsAndInit();

    activated = true;
    if (enableShutdownHook) {
      // Log4j may not attempt to close the appender when the JVM shuts down, so
      // this hook ensures we try to close the appender before shutdown.
      Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }
  }

  /**
   * Validates the appender's settings (e.g., compression level) and initializes
   * the appender with them.
   * @throws IOException on I/O error
   */
  private void validateOptionsAndInit () throws IOException {
    if (null == layout) {
      throw new IllegalArgumentException("layout not set.");
    }
    if (layout instanceof HTMLLayout || layout instanceof XMLLayout) {
      throw new IllegalArgumentException("layout type " + layout.getClass().getName()
                                             + " not supported.");
    } else if (layout instanceof EnhancedPatternLayout) {
      String conversionPattern = processConversionPattern(layout);
      layout = new EnhancedPatternLayout(conversionPattern);
    } else if (layout instanceof PatternLayout) {
      String conversionPattern = processConversionPattern(layout);
      layout = new PatternLayout(conversionPattern);
    } else if (layout instanceof SimpleLayout) {
      timestampPattern = "";
      estimatedFormattedTimestampLength = 0;
    } else {
      throw new UnsupportedEncodingException("Unsupported Log4j Layout");
    }

    if (compressionLevel < Zstd.minCompressionLevel()
        || Zstd.maxCompressionLevel() < compressionLevel)
    {
      throw new IllegalArgumentException("compressionLevel is outside of valid range: ["
                                             + Zstd.minCompressionLevel() + ", "
                                             + Zstd.maxCompressionLevel() + "]");
    }

    sanitizeFilePath();

    createOutputStream();
  }

  /**
   * Gets the timestamp pattern from the given Log4j Layout's conversion pattern
   * and creates a conversion pattern that doesn't contain any date conversion
   * patterns.
   * <p></p>
   * E.g., if the conversion pattern is
   * "%d{yyyy-MM-dd HH:mm:ss.SSSZ} %p [%c{1}] %m%n", this method will set the
   * timestamp pattern to "yyyy-MM-dd HH:mm:ss.SSSZ" and create the conversion
   * pattern, " %p [%c{1}] %m%n".
   * @param layout Log4j layout for formatting log events
   * @return The conversion pattern without date conversion patterns
   */
  private String processConversionPattern (Layout layout) {
    DateConversionPatternExtractor datePatternExtractor =
        new DateConversionPatternExtractor(layout);

    for (DateConversionPattern datePattern : datePatternExtractor.getDateConversionPatterns()) {
      if (null != timestampPattern) {
        logError("Found multiple date conversion specifiers in pattern. Only the first will "
                     + "be preserved.");
        continue;
      }

      // + 1 is the character after the '%'
      if ('r' == datePattern.specifier) {
        logError("%r is unsupported and will be ignored.");
      } else if ('d' == datePattern.specifier) {
        if (datePattern.offsetInConversionPattern > 0) {
          logError("Position of date conversion specifier (%d) will not be preserved.");
        }

        if (null == datePattern.format) {
          // Pattern is "%d" which implies ISO8601 ("yyyy-MM-dd HH:mm:ss,SSS")
          timestampPattern = "yyyy-MM-dd HH:mm:ss,SSS";
          estimatedFormattedTimestampLength = timestampPattern.length();
        } else {
          // Pattern is "%d{...}"
          switch (datePattern.format) {
            case "ABSOLUTE":
              timestampPattern = "HH:mm:ss,SSS";
              estimatedFormattedTimestampLength = timestampPattern.length();
              break;
            case "DATE":
              timestampPattern = "dd MMM yyyy HH:mm:ss,SSS";
              estimatedFormattedTimestampLength = timestampPattern.length();
              break;
            case "ISO8601":
              timestampPattern = "yyyy-MM-dd HH:mm:ss,SSS";
              estimatedFormattedTimestampLength = timestampPattern.length();
              break;
            default:
              timestampPattern = datePattern.format;
              // NOTE: We use getBytes(ISO_8859_1) since the user's dateFormat
              // may contain Unicode characters
              estimatedFormattedTimestampLength =
                  timestampPattern.getBytes(StandardCharsets.ISO_8859_1).length;
              break;
          }
        }
      }
    }

    return datePatternExtractor.getConversionPatternWithoutDates();
  }

  private void sanitizeFilePath () {
    if (null == file) {
      throw new IllegalArgumentException("file option not set.");
    }
    // Trim surrounding spaces
    file = file.trim();
  }

  /**
   * Creates the CLP IR output stream, the file output stream, and any necessary
   * streams in between.
   * @throws IOException on I/O error
   */
  private void createOutputStream () throws IOException {
    FileOutputStream fileOutputStream = createOutputFile(file);
    countingOutputStream = new CountingOutputStream(fileOutputStream);
    ZstdOutputStream zstdOutputStream =
        new ZstdOutputStream(countingOutputStream, compressionLevel);
    zstdOutputStream.setCloseFrameOnFlush(closeFrameOnFlush);
    // Get the local time zone in case we need to determine the time zone
    // of timestamps printed in the content of log messages. This is not the
    // time zone used to display log messages to the user (that will be
    // determined by the user's locale, etc.).
    String timeZoneId = ZonedDateTime.now().getZone().toString();
    if (useFourByteEncoding) {
      clpIrOutputStream =
          new FourByteClpIrOutputStream(timestampPattern, timeZoneId, zstdOutputStream);
    } else {
      clpIrOutputStream =
          new EightByteClpIrOutputStream(timestampPattern, timeZoneId, zstdOutputStream);
    }

    uncompressedSizeInBytes += timestampPattern.getBytes(StandardCharsets.ISO_8859_1).length;
    uncompressedSizeInBytes += timeZoneId.length();
  }

  /**
   * Creates and opens the output file and file output stream.
   * @param filePath
   * @return The file output stream
   * @throws IOException on I/O error
   */
  private FileOutputStream createOutputFile (String filePath) throws IOException {
    FileOutputStream fileOutputStream;
    try {
      // append = false since we don't support appending to an existing file
      fileOutputStream = new FileOutputStream(filePath, false);
    } catch (FileNotFoundException ex) {
      // Create the parent directory if necessary
      String parentPath = new File(filePath).getParent();
      if (null == parentPath) {
        throw ex;
      }
      Files.createDirectories(Paths.get(parentPath));

      fileOutputStream = new FileOutputStream(filePath, false);
    }
    return fileOutputStream;
  }
}
