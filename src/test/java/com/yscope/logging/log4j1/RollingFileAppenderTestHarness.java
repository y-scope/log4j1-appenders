package com.yscope.logging.log4j1;

public class RollingFileAppenderTestHarness extends AbstractClpIrBufferedRollingFileAppender {
  private int numSyncEvent = 0;
  private int numSyncAndCloseEvent = 0;

  public RollingFileAppenderTestHarness () {
    super(new ManualTimeSource());
  }

  /**
   * Sets the current time visible to the appender
   * @param timestamp The current time
   */
  public void setTime (long timestamp) {
    timeSource.setCurrentTimeInMilliseconds(timestamp);
  }

  public synchronized int getNumSyncAndCloseEvent () {
    return numSyncAndCloseEvent;
  }

  public synchronized int getNumSyncEvent () {
    return numSyncEvent;
  }

  /**
   * We hook onto the sync function to record flush and close events
   * @param path of log file on the local file system
   * @param deleteFile whenever the implementation sees fit
   */
  @Override
  protected synchronized void sync (String path, boolean deleteFile) {
    if (deleteFile) {
      numSyncAndCloseEvent += 1;
    } else {
      numSyncEvent += 1;
    }
  }
}
