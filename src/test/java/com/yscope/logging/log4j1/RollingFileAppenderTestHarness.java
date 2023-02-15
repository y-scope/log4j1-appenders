package com.yscope.logging.log4j1;

public class RollingFileAppenderTestHarness extends AbstractClpIrBufferedRollingFileAppender {
  private int numSyncEvent = 0;
  private int numSyncAndCloseEvent = 0;

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

  public synchronized int getNumSyncAndCloseEvent () {
    return numSyncAndCloseEvent;
  }

  public synchronized int getNumSyncEvent () {
    return numSyncEvent;
  }
}
