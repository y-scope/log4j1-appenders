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
  private final int flushSoftTimeoutUnitInMilliseconds = 1000;
  private final int flushHardTimeoutUnitInMilliseconds = 60000;
  private final int timeoutCheckPeriod = 10;
  private final String outputDir = "testOutputDir";

  /**
   * Tests rollover based on the uncompressed size of the file.
   */
  @Test
  public void testRollingBasedOnUncompressedSize () {
    // Set the uncompressed rollover size to 1 so that every append triggers a
    // rollover
    RollingFileTestAppender appender = createTestAppender(Integer.MAX_VALUE, 1, true, true);

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
   */
  @Test
  public void testRollingBasedOnCompressedSize () {
    // Set the compressed rollover size to 1 so that a rollover is triggered
    // once data is output to the file
    RollingFileTestAppender appender = createTestAppender(1, Integer.MAX_VALUE, true, true);

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
    validateBasicFlushTimeoutSupport(false);

    RollingFileTestAppender appender = createTestAppender(Integer.MAX_VALUE, Integer.MAX_VALUE,
                                                          true, false);
    int expectedNumSyncs = 0;
    int expectedNumRollovers = 0;
    int currentTimestamp = 0;

    // Verify a sequence of two ERROR events triggers a sync due to the hard
    // timeout of the first ERROR event
    appendLogEvent(currentTimestamp, Level.ERROR, appender);
    // Move time forward to just before the timeout
    // NOTE: We use "- 2" here (instead of "- 1") so that in the next validation
    // step, validateSyncAfterTimeout still has room to move time forward before
    // triggering the timeout
    currentTimestamp += flushErrorLevelTimeout * flushHardTimeoutUnitInMilliseconds - 2;
    appender.setTime(currentTimestamp);
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    // Append the second ERROR event and validate a sync happens due to the
    // first
    appendLogEvent(currentTimestamp, Level.ERROR, appender);
    currentTimestamp += 2;
    ++expectedNumSyncs;
    validateSyncAfterTimeout(currentTimestamp, expectedNumSyncs, expectedNumRollovers, appender);
    // Validate no sync happens because of the second ERROR event
    currentTimestamp += flushErrorLevelTimeout * flushHardTimeoutUnitInMilliseconds;
    appender.setTime(currentTimestamp);
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
    validateBasicFlushTimeoutSupport(true);

    RollingFileTestAppender appender = createTestAppender(Integer.MAX_VALUE, Integer.MAX_VALUE,
                                                          false, true);
    int expectedNumSyncs = 0;
    int expectedNumRollovers = 0;
    int currentTimestamp = 0;

    // Append three events over some time period and verify a sync only happens
    // after the timeout triggered by the last event
    int iterations = 3;
    for (int i = 0; i < iterations; i++) {
      appendLogEvent(i, Level.INFO, appender);
      currentTimestamp += 1;
      appender.setTime(currentTimestamp);
    }
    // NOTE: The -1 is to account for the extra time unit we added after the
    // last log event
    currentTimestamp += flushInfoLevelTimeout * flushSoftTimeoutUnitInMilliseconds - 1;
    ++expectedNumSyncs;
    validateSyncAfterTimeout(currentTimestamp, expectedNumSyncs, expectedNumRollovers, appender);

    // Verify a sequence of two ERROR events triggers a sync due to the soft
    // timeout of the second ERROR event
    appendLogEvent(currentTimestamp, Level.ERROR, appender);
    // Move time forward to just before the timeout
    currentTimestamp += flushErrorLevelTimeout * flushSoftTimeoutUnitInMilliseconds - 1;
    appender.setTime(currentTimestamp);
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    // Append the second ERROR event and validate a sync happens only due to the
    // second
    appendLogEvent(currentTimestamp, Level.ERROR, appender);
    currentTimestamp += 1;
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    currentTimestamp += flushErrorLevelTimeout * flushSoftTimeoutUnitInMilliseconds - 1;
    ++expectedNumSyncs;
    validateSyncAfterTimeout(currentTimestamp, expectedNumSyncs, expectedNumRollovers, appender);

    // Verify a sequence of ERROR-INFO events triggers a sync due to the soft
    // timeout of the second log event as if it was an ERROR event rather than
    // an INFO event
    appendLogEvent(currentTimestamp, Level.ERROR, appender);
    // Move time forward to just before the timeout
    currentTimestamp += flushErrorLevelTimeout * flushSoftTimeoutUnitInMilliseconds - 1;
    appender.setTime(currentTimestamp);
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    // Append the INFO event and validate the timeout logic treats it as if it
    // was a second ERROR event
    appendLogEvent(currentTimestamp, Level.INFO, appender);
    currentTimestamp += 1;
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    currentTimestamp += flushErrorLevelTimeout * flushSoftTimeoutUnitInMilliseconds - 1;
    ++expectedNumSyncs;
    validateSyncAfterTimeout(currentTimestamp, expectedNumSyncs, expectedNumRollovers, appender);

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

  /**
   * Performs basic validation of flush timeout support (not specific to either
   * soft/hard) for the appender
   * @param testSoftTimeout Whether to test soft (true) or hard (false) timeout
   * support
   */
  private void validateBasicFlushTimeoutSupport (boolean testSoftTimeout) {
    int timeoutUnitInMilliseconds =
        testSoftTimeout ? flushSoftTimeoutUnitInMilliseconds : flushHardTimeoutUnitInMilliseconds;
    RollingFileTestAppender appender =
        createTestAppender(Integer.MAX_VALUE, Integer.MAX_VALUE, false == testSoftTimeout,
                           testSoftTimeout);
    int expectedNumSyncs = 0;
    int expectedNumRollovers = 0;
    int currentTimestamp = 0;

    // Verify a single INFO event triggers a sync after a timeout
    appendLogEvent(currentTimestamp, Level.INFO, appender);
    currentTimestamp = flushInfoLevelTimeout * timeoutUnitInMilliseconds;
    ++expectedNumSyncs;
    validateSyncAfterTimeout(currentTimestamp, expectedNumSyncs, expectedNumRollovers, appender);

    // Verify a sequence of INFO-ERROR events triggers a sync due to the ERROR
    // event sooner than the timeout for the INFO event
    appendLogEvent(currentTimestamp, Level.INFO, appender);
    appendLogEvent(currentTimestamp, Level.ERROR, appender);
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    currentTimestamp += flushErrorLevelTimeout * timeoutUnitInMilliseconds;
    ++expectedNumSyncs;
    validateSyncAfterTimeout(currentTimestamp, expectedNumSyncs, expectedNumRollovers, appender);
    // Validate no sync happens because of the INFO event
    currentTimestamp += flushInfoLevelTimeout * timeoutUnitInMilliseconds;
    appender.setTime(currentTimestamp);
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);

    // Verify a rollover after closing the appender
    appender.close();
    ++expectedNumRollovers;
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
  }

  private void appendLogEvent (long timestamp, Level level, RollingFileTestAppender appender) {
    String loggerName = TestFileAppender.class.getCanonicalName();
    String message = "Static text, dictVar1, 123, 456.7, dictVar2, 987, 654.3";
    appender.append(new LoggingEvent(loggerName, logger, timestamp, level, message, null));
  }

  /**
   * Validates that a sync only occurs after the specified timestamp and not a
   * time unit before
   * @param syncTimestamp Time when the sync should occur
   * @param expectedNumSyncs
   * @param expectedNumRollovers
   * @param appender
   */
  private void validateSyncAfterTimeout (long syncTimestamp, int expectedNumSyncs,
                                         int expectedNumRollovers,
                                         RollingFileTestAppender appender) {
    appender.setTime(syncTimestamp - 1);
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs - 1, expectedNumRollovers);
    appender.setTime(syncTimestamp);
    validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
  }

  /**
   * Validates that the appender has triggered the given number of sync and
   * sync-and-close events
   * @param appender
   * @param numSyncs
   * @param numRollovers
   */
  private void validateNumSyncAndCloseEvents (RollingFileTestAppender appender, int numSyncs,
                                              int numRollovers)
  {
    long sleepTime = timeoutCheckPeriod * 2;
    // Sleep so the background threads have a chance to process any syncs and
    // rollovers
    assertDoesNotThrow(() -> sleep(sleepTime));

    // Verify the expected num of syncs and rollovers
    long deadlineTimestamp = System.currentTimeMillis() + sleepTime;
    while (appender.getNumSyncs() != numSyncs) {
      if (System.currentTimeMillis() >= deadlineTimestamp) {
        assertEquals(numSyncs, appender.getNumSyncs());
      }
    }
    while (appender.getNumRollovers() != numRollovers) {
      if (System.currentTimeMillis() >= deadlineTimestamp) {
        assertEquals(numRollovers, appender.getNumRollovers());
      }
    }
  }

  /**
   * Creates and initializes a RollingFileTestAppender with the given
   * rollover sizes
   * @param compressedRolloverSize
   * @param uncompressedRolloverSize
   * @return The created appender
   */
  private RollingFileTestAppender createTestAppender (int compressedRolloverSize,
                                                      int uncompressedRolloverSize,
                                                      boolean disableSoftTimeout,
                                                      boolean disableHardTimeout)
  {
    RollingFileTestAppender appender = new RollingFileTestAppender();
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
    String disabledTimeoutCsv = "ERROR=" + Integer.MAX_VALUE + ",INFO=" + Integer.MAX_VALUE;
    String timeoutCsv = "ERROR=" + flushErrorLevelTimeout + ",INFO=" + flushInfoLevelTimeout;
    appender.setFlushHardTimeoutsInMinutes(disableHardTimeout ? disabledTimeoutCsv : timeoutCsv);
    appender.setFlushSoftTimeoutsInSeconds(disableSoftTimeout ? disabledTimeoutCsv : timeoutCsv);

    appender.activateOptions();
    return appender;
  }
}
