package com.yscope.logging.log4j1;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static java.lang.Thread.sleep;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class TestRollingFileLogAppender {
  private static final Logger logger = Logger.getLogger(TestFileAppender.class);

  private final String patternLayoutString = "%d{yy/MM/dd HH:mm:ss} %p %c{1}: %m%n";
  private final PatternLayout patternLayout = new PatternLayout(patternLayoutString);
  private final int flushErrorLevelTimeout = 1;
  private final int flushInfoLevelTimeout = 2;
  private final int timeoutCheckPeriod = 10;
  private final String outputDir = "testOutputDir";

  /**
   * Tests rollover based on the uncompressed size of the file.
   */
  @Test
  public void testRollingBasedOnUncompressedSize () {
    // Set the uncompressed rollover size to 1 so that every append triggers a
    // rollover
    RollingFileAppenderTestHarness appender = createTestAppender(Integer.MAX_VALUE, 1);

    // Verify rollover after appending every event
    int expectedNumRollovers = 0;
    appendLogEvent(0, Level.INFO, appender);
    ++expectedNumRollovers;
    validateNumSyncAndCloseEvents(appender, 0, expectedNumRollovers);
    appendLogEvent(0, Level.INFO, appender);
    ++expectedNumRollovers;
    validateNumSyncAndCloseEvents(appender, 0, expectedNumRollovers);

    // Verify a rollover after closing the appender
    appender.close();
    ++expectedNumRollovers;
    validateNumSyncAndCloseEvents(appender, 0, expectedNumRollovers);
  }

  /**
   * Tests rollover based on the compressed size of the file.
   * @throws IOException on I/O error
   */
  @Test
  public void testRollingBasedOnCompressedSize () throws IOException {
    // Set the compressed rollover size to 1 so that a rollover is triggered
    // once data is output to the file
    RollingFileAppenderTestHarness appender = createTestAppender(1, Integer.MAX_VALUE);

    // Verify that an append-flush-append sequence triggers a rollover. We need
    // the first append and flush to force the compressor to flush the buffered
    // log event to the output file. The final append is to trigger the
    // rollover.
    int expectedNumRollovers = 0;
    appendLogEvent(0, Level.INFO, appender);
    assertDoesNotThrow(appender::flush);
    validateNumSyncAndCloseEvents(appender, 0, expectedNumRollovers);
    appendLogEvent(0, Level.INFO, appender);
    ++expectedNumRollovers;
    validateNumSyncAndCloseEvents(appender, 0, expectedNumRollovers);

    // Verify a rollover after closing the appender
    appender.close();
    ++expectedNumRollovers;
    validateNumSyncAndCloseEvents(appender, 0, expectedNumRollovers);
  }

  /**
   * Tests the hard timeout
   */
  @Test
  public void testHardTimeout () {
    RollingFileAppenderTestHarness appender = createTestAppender(Integer.MAX_VALUE,
                                                                 Integer.MAX_VALUE);

    // Verify no syncs occur after appending an event
    int expectedNumSyncs = 0;
    int expectedNumRollovers = 0;
    int currentTimestamp = 0;
    appendLogEvent(currentTimestamp, Level.INFO, appender);
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);

    // Move time forward and verify that a sync happens
    currentTimestamp = flushInfoLevelTimeout * 60000;
    appender.setTime(currentTimestamp);
    ++expectedNumSyncs;
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);

    // Verify a sequence of INFO-ERROR events triggers a sync due to the ERROR
    // event sooner than the timeout for the INFO event
    appendLogEvent(currentTimestamp, Level.INFO, appender);
    appendLogEvent(currentTimestamp, Level.ERROR, appender);
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    currentTimestamp += flushErrorLevelTimeout * 60000;
    appender.setTime(currentTimestamp);
    ++expectedNumSyncs;
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);

    // Verify a rollover after closing the appender
    appender.close();
    ++expectedNumRollovers;
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
  }

  /**
   * Tests the soft timeout
   */
  @Test
  public void testSoftTimeout () {
    RollingFileAppenderTestHarness appender = createTestAppender(Integer.MAX_VALUE,
                                                                 Integer.MAX_VALUE);

    // Append three events and verify syncs only happen after the timeout
    // triggered by the last event
    int expectedNumSyncs = 0;
    int expectedNumRollovers = 0;
    int currentTimestamp = 0;
    for (int i = 0; i < 3; i++) {
      for (int j = 0; j < 3; j++) {
        appendLogEvent(i + j, Level.INFO, appender);
      }

      // Verify no syncs happen up to the point just before the soft timeout
      appender.setTime(flushInfoLevelTimeout * 1000 - 1 + i);
      validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);

      // Verify a sync happens when the soft timeout is reached
      appender.setTime(flushInfoLevelTimeout * 1000 + i);
      currentTimestamp = flushInfoLevelTimeout * 1000 + i;
      ++expectedNumSyncs;
      validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    }

    // Verify a sequence of INFO-ERROR events triggers a sync due to the ERROR
    // event sooner than the timeout for the INFO event
    appendLogEvent(currentTimestamp, Level.INFO, appender);
    appendLogEvent(currentTimestamp, Level.ERROR, appender);
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    currentTimestamp += flushErrorLevelTimeout * 1000;
    appender.setTime(currentTimestamp);
    ++expectedNumSyncs;
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);

    // Verify a rollover after closing the appender
    appender.close();
    ++expectedNumRollovers;
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
  }

  @AfterEach
  public void cleanUpFiles () {
    // Delete the output directory tree
    try (Stream<Path> s = Files.walk(Paths.get(outputDir))) {
      s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    } catch (IOException ex) {
      fail(ex);
    }
  }

  private void appendLogEvent (long timestamp, Level level,
                               RollingFileAppenderTestHarness appender)
  {
    String loggerName = TestFileAppender.class.getCanonicalName();
    String message = "Static text, dictVar1, 123, 456.7, dictVar2, 987, 654.3";
    appender.append(new LoggingEvent(loggerName, logger, timestamp, level, message, null));
  }

  /**
   * Validates that the appender has triggered the given number of sync and
   * sync-and-close events
   * @param appender
   * @param numSyncs
   * @param numRollovers
   */
  private void validateNumSyncAndCloseEvents (RollingFileAppenderTestHarness appender,
                                              int numSyncs, int numRollovers)
  {
    long sleepTime = timeoutCheckPeriod * 2;
    // Sleep so the background threads have a chance to process any syncs and
    // rollovers
    assertDoesNotThrow(() -> sleep(sleepTime));

    // Verify the expected num of syncs and rollovers
    long deadlineTimestamp = System.currentTimeMillis() + sleepTime;
    while (appender.getNumSyncEvent() != numSyncs) {
      if (System.currentTimeMillis() >= deadlineTimestamp) {
        assertEquals(numSyncs, appender.getNumSyncEvent());
      }
    }
    while (appender.getNumSyncAndCloseEvent() != numRollovers) {
      if (System.currentTimeMillis() >= deadlineTimestamp) {
        assertEquals(numRollovers, appender.getNumSyncAndCloseEvent());
      }
    }
  }

  /**
   * Creates and initializes a RollingFileAppenderTestHarness with the given
   * rollover sizes
   * @param compressedRolloverSize
   * @param uncompressedRolloverSize
   * @return The created appender
   */
  private RollingFileAppenderTestHarness createTestAppender (int compressedRolloverSize,
                                                             int uncompressedRolloverSize)
  {
    RollingFileAppenderTestHarness appender = new RollingFileAppenderTestHarness();
    // Parameters from AbstractClpIrBufferedRollingFileAppender
    appender.setRolloverCompressedSizeThreshold(compressedRolloverSize);
    appender.setRolloverUncompressedSizeThreshold(uncompressedRolloverSize);
    appender.setOutputDir(outputDir);
    appender.setBaseName("test-file");
    appender.setCloseFrameOnFlush(true);
    // Parameters from AbstractBufferedRollingFileAppender
    appender.setCloseFileOnShutdown(true);
    appender.setLayout(patternLayout);
    appender.setTimeoutCheckPeriod(timeoutCheckPeriod);
    String timeoutCsv = "ERROR=" + flushErrorLevelTimeout + ",INFO=" + flushInfoLevelTimeout;
    appender.setFlushHardTimeoutsInMinutes(timeoutCsv);
    appender.setFlushSoftTimeoutsInSeconds(timeoutCsv);

    appender.activateOptions();
    return appender;
  }
}
