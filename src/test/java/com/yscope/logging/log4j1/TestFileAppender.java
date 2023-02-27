package com.yscope.logging.log4j1;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.EnhancedPatternLayout;
import org.apache.log4j.HTMLLayout;
import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.SimpleLayout;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.log4j.xml.XMLLayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class TestFileAppender {
  private final String patternLayoutString = "%d{yy/MM/dd HH:mm:ss} %p %c{1}: %m%n";
  private final PatternLayout patternLayout = new PatternLayout(patternLayoutString);
  private final int compressionLevel = 3;

  @Test
  public void testFourByteIrAppender() {
    testAppender(true);
  }

  @Test
  public void testEightByteIrAppender () {
    testAppender(false);
  }

  private void testAppender (boolean useFourByteEncoding) {
    String fileName = useFourByteEncoding ? "four-byte.clp.zst" : "eight-byte.clp.zst";

    // Validate invalid arguments are detected
    assertThrowsExactly(IllegalArgumentException.class,
                        () -> new ClpIrFileAppender(null, patternLayout, useFourByteEncoding,
                                                    false, compressionLevel));
    assertThrowsExactly(FileNotFoundException.class,
                        () -> new ClpIrFileAppender("", patternLayout, useFourByteEncoding, false,
                                                    compressionLevel));
    assertThrowsExactly(IllegalArgumentException.class,
                        () -> new ClpIrFileAppender(fileName, null, useFourByteEncoding, false,
                                                    compressionLevel));
    assertThrowsExactly(IllegalArgumentException.class,
                        () -> new ClpIrFileAppender(fileName, patternLayout, useFourByteEncoding,
                                                    false, Integer.MIN_VALUE));
    assertThrowsExactly(IllegalArgumentException.class,
                        () -> new ClpIrFileAppender(fileName, patternLayout, useFourByteEncoding,
                                                    false, Integer.MAX_VALUE));

    // Validate different file paths
    try {
      testEmptyCreation(Paths.get(fileName), patternLayout, useFourByteEncoding);
      testEmptyCreation(Paths.get("a", "b", fileName), patternLayout, useFourByteEncoding);
    } catch (Exception ex) {
      fail(ex);
    }

    // Validate types of layouts
    try {
      testLayouts(fileName, useFourByteEncoding);
    } catch (Exception ex) {
      fail(ex);
    }

    // Test writing
    try {
      testWriting(fileName, false, false, compressionLevel);
      testWriting(fileName, false, true, compressionLevel);
      testWriting(fileName, false, false, compressionLevel + 1);
      testWriting(fileName, true, false, compressionLevel);
      testWriting(fileName, true, true, compressionLevel);
      testWriting(fileName, true, false, compressionLevel + 1);
    } catch (Exception ex) {
      fail(ex);
    }
  }

  /**
   * Tests creating an empty CLP IR stream log with the given path.
   * @param filePath Path to create. Note that after the test, the entire
   * directory tree specified by the path will be deleted.
   * @param useFourByteEncoding
   * @throws IOException on I/O error
   */
  private void testEmptyCreation (
      Path filePath,
      Layout layout,
      boolean useFourByteEncoding
  ) throws IOException {
    String filePathString = filePath.toString();
    ClpIrFileAppender clpIrFileAppender = new ClpIrFileAppender(filePathString, layout,
                                                                useFourByteEncoding,
                                                                false, compressionLevel);
    clpIrFileAppender.close();
    assertTrue(Files.exists(filePath));

    Path parent = filePath.getParent();
    if (null == parent) {
      Files.delete(filePath);
    } else {
      // Get top-level parent
      while (true) {
        Path p = parent.getParent();
        if (null == p) {
          break;
        }
        parent = p;
      }
      FileUtils.deleteDirectory(parent.toFile());
    }
  }

  /**
   * Test all possible Log4j layouts
   * @param filePathString
   * @param useFourByteEncoding
   * @throws IOException on I/O error
   */
  private void testLayouts (String filePathString, boolean useFourByteEncoding) throws IOException
  {
    Path filePath = Paths.get(filePathString);

    Layout layout;
    layout = new EnhancedPatternLayout(patternLayoutString);
    testEmptyCreation(filePath, layout, useFourByteEncoding);
    layout = new PatternLayout(patternLayoutString);
    testEmptyCreation(filePath, layout, useFourByteEncoding);
    layout = new SimpleLayout();
    testEmptyCreation(filePath, layout, useFourByteEncoding);

    assertThrowsExactly(IllegalArgumentException.class,
                        () -> new ClpIrFileAppender(filePathString, new HTMLLayout(),
                                                    useFourByteEncoding, false, compressionLevel));
    assertThrowsExactly(IllegalArgumentException.class,
                        () -> new ClpIrFileAppender(filePathString, new XMLLayout(),
                                                    useFourByteEncoding, false, compressionLevel));
  }

  /**
   * Test writing log files
   * @param fileName
   * @param useFourByteEncoding
   * @param closeFrameOnFlush
   * @param compressionLevel
   * @throws IOException on I/O error
   */
  private void testWriting (
      String fileName,
      boolean useFourByteEncoding,
      boolean closeFrameOnFlush,
      int compressionLevel
  ) throws IOException {
    // TODO Once decoding support has been added to clp-ffi-java, these tests
    //  should all be verified by a decoding the stream and comparing it with
    //  the output of an uncompressed file appender.

    Logger logger = Logger.getLogger(TestFileAppender.class);
    String message = "Static text, dictVar1, 123, 456.7, dictVar2, 987, 654.3";

    ClpIrFileAppender clpIrFileAppender = new ClpIrFileAppender(fileName, patternLayout,
                                                                useFourByteEncoding,
                                                                closeFrameOnFlush,
                                                                compressionLevel);

    // Log some normal logs
    for (int i = 0; i < 100; ++i) {
      clpIrFileAppender.append(new LoggingEvent("com.yscope.logging.log4j", logger, Level.INFO,
                                                message, null));
    }

    // Log with an exception
    clpIrFileAppender.append(new LoggingEvent("com.yscope.logging.log4j", logger, Level.INFO,
                                              message, new FileNotFoundException()));

    clpIrFileAppender.flush();

    // Split into a new file
    String fileName2 = fileName + ".2";
    clpIrFileAppender.startNewFile(fileName2);

    // Add some more logs
    for (int i = 0; i < 100; ++i) {
      clpIrFileAppender.append(new LoggingEvent("com.yscope.logging.log4j", logger, Level.INFO,
                                                message, null));
    }

    clpIrFileAppender.close();

    // Verify file existence
    Path filePath = Paths.get(fileName);
    assertTrue(Files.exists(filePath));
    Files.delete(filePath);
    Path filePath2 = Paths.get(fileName2);
    assertTrue(Files.exists(filePath2));
    Files.delete(filePath2);
  }
}
