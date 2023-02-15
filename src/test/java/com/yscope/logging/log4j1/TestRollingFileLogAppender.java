package com.yscope.logging.log4j1;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.fail;

public class TestRollingFileLogAppender {
  private Logger logger = Logger.getLogger(TestFileAppender.class);

  private final String patternLayoutString = "%d{yy/MM/dd HH:mm:ss} %p %c{1}: %m%n";
  private final PatternLayout patternLayout = new PatternLayout(patternLayoutString);
  private final String outputDir = "testOutputDir";
  private final String baseName = "test-file";

  /**
   * Testing uncompressed size rollover is very simple. We shrink the
   * uncompressed roll-over size to 1 byte will shall trigger one sync and close
   * event for every new log event appended by the appender.
   */
  @Test
  public void testRollingBasedOnUncompressedSize () {
    RollingFileAppenderTestHarness clpIrRollingLocalFileAppender =
        generateTestAppender(99999999, 1);

    // Append the 1st message and expect rollover to a new file
    appendLogEvent(clpIrRollingLocalFileAppender);
    checkNumSyncAndCloseEvent(clpIrRollingLocalFileAppender, 1, 1000);

    // Append the 2nd message and expect rollover to a new file
    appendLogEvent(clpIrRollingLocalFileAppender);
    checkNumSyncAndCloseEvent(clpIrRollingLocalFileAppender, 2, 1000);

    // Close the appender should sync and close the current opened file
    clpIrRollingLocalFileAppender.close();
    checkNumSyncAndCloseEvent(clpIrRollingLocalFileAppender, 3, 1000);

    teardown();
  }

  /**
   * Testing compressed size rollover is harder than uncompressed size because
   * we do not know the compressed file size prior to flushing the compression
   * buffer. Rollover also occurs synchronously within the append method of the
   * appender, thus we need to first append something prior triggering the
   * rollover on the next append operation.
   */
  @Test
  public void testRollingBasedOnCompressedSize () throws InterruptedException, IOException {
    RollingFileAppenderTestHarness clpIrRollingLocalFileAppender =
        generateTestAppender(1, 9999999);

    // Append the first message, and force manual flush so the next append
    // shall trigger a rollover event
    appendLogEvent(clpIrRollingLocalFileAppender);
    clpIrRollingLocalFileAppender.flush();   // Trigger flush
    checkNumSyncAndCloseEvent(clpIrRollingLocalFileAppender, 0, 1000);
    appendLogEvent(clpIrRollingLocalFileAppender);
    checkNumSyncAndCloseEvent(clpIrRollingLocalFileAppender, 1, 1000);

    // Since a file rollover event should have occurred, closing the
    // appender shall close the current empty file
    clpIrRollingLocalFileAppender.close();
    checkNumSyncAndCloseEvent(clpIrRollingLocalFileAppender, 2, 1000);

    teardown();
  }

  /**
   * Testing hard timeout is fairly simple by explicitly setting the hard
   * timeout epoch value to the past.
   */
  @Test
  public void testHardTimeout () {
    RollingFileAppenderTestHarness clpIrRollingLocalFileAppender =
        generateTestAppender(99999999, 99999999);

    // Append a log event then explicitly set hard deadline epoch to the past.
    // The background flusher shall flush the log event asychronously shortly
    appendLogEvent(clpIrRollingLocalFileAppender);
    clpIrRollingLocalFileAppender.setHardFlushTimeoutEpoch(System.currentTimeMillis() - 999);
    checkNumSyncEvent(clpIrRollingLocalFileAppender, 1, 1000);
    checkNumSyncAndCloseEvent(clpIrRollingLocalFileAppender, 0, 1000);

    // After closing the file appender, we should have 1 close/delete event
    clpIrRollingLocalFileAppender.close();
    checkNumSyncAndCloseEvent(clpIrRollingLocalFileAppender, 1, 1000);

    teardown();
  }

  /**
   * Testing soft timeout requires us to append multiple log messages in quick
   * succession and wait until the soft timeout is triggered
   * @throws InterruptedException
   */
  @Test
  public void testSoftTimeout () throws InterruptedException {
    RollingFileAppenderTestHarness clpIrRollingLocalFileAppender =
        generateTestAppender(99999999, 99999999);

    // Hard deadline should be in distant future
    clpIrRollingLocalFileAppender.setHardFlushTimeoutEpoch(System.currentTimeMillis() + 99999999);

    // We should observe 3 flush events
    for (int i = 0; i < 3; i++) {
      for (int j = 0; j < 3; j++) {
        appendLogEvent(clpIrRollingLocalFileAppender);
      }
      clpIrRollingLocalFileAppender.setSoftFlushTimeoutEpoch(System.currentTimeMillis() - 999);
      checkNumSyncEvent(clpIrRollingLocalFileAppender, i + 1, 1000);
    }

    // After closing the file appender, we should have 1 flush + close event
    clpIrRollingLocalFileAppender.close();
    checkNumSyncAndCloseEvent(clpIrRollingLocalFileAppender, 1, 1000);

    teardown();
  }

  private void appendLogEvent (RollingFileAppenderTestHarness appender) {
    String loggerName = TestFileAppender.class.getCanonicalName();
    String message = "Static text, dictVar1, 123, 456.7, dictVar2, 987, 654.3";
    appender.append(new LoggingEvent(loggerName, logger, Level.INFO, message, null));
  }

  private void checkNumSyncAndCloseEvent (RollingFileAppenderTestHarness appender,
                                          int numSyncAndCloseEvent, int timeoutMs)
  {
    long timeout = System.currentTimeMillis() + timeoutMs;
    while (appender.getNumSyncAndCloseEvent() != numSyncAndCloseEvent) {
      if (System.currentTimeMillis() > timeout) {
        fail();
      }
    }
  }

  private void checkNumSyncEvent (RollingFileAppenderTestHarness appender, int numSyncEvent,
                                  int timeoutMs)
  {
    long timeout = System.currentTimeMillis() + timeoutMs;
    while (appender.getNumSyncEvent() != numSyncEvent) {
      if (System.currentTimeMillis() > timeout) {
        fail();
      }
    }
  }

  private RollingFileAppenderTestHarness generateTestAppender (int compressedRolloverSize,
                                                               int uncompressedRolloverSize)
  {
    RollingFileAppenderTestHarness clpIrRollingLocalFileAppender =
        new RollingFileAppenderTestHarness();
    // Parameters from {@code AbstractClpIrBufferedRollingFileAppender}
    clpIrRollingLocalFileAppender.setCompressedRolloverSize(compressedRolloverSize);
    clpIrRollingLocalFileAppender.setUncompressedRolloverSize(uncompressedRolloverSize);
    clpIrRollingLocalFileAppender.setOutputDir(outputDir);
    clpIrRollingLocalFileAppender.setBaseName(baseName);
    clpIrRollingLocalFileAppender.setCloseFrameOnFlush(true);
    clpIrRollingLocalFileAppender.setUseCompactEncoding(true);
    // Parameters from {@code AbstractBufferedRollingFileAppender}
    clpIrRollingLocalFileAppender.setCloseFileOnShutdown(true);
    clpIrRollingLocalFileAppender.setLayout(patternLayout);
    clpIrRollingLocalFileAppender.setBackgroundSyncSleepTimeMs(10);

    clpIrRollingLocalFileAppender.activateOptions();
    return clpIrRollingLocalFileAppender;
  }

  private void teardown () {
    Arrays.stream(new File(outputDir).listFiles()).forEach(File::delete);
  }
}
