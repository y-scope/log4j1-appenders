package com.yscope.logging.log4j1;

import java.io.Flushable;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Level;
import org.apache.log4j.spi.ErrorCode;
import org.apache.log4j.spi.LoggingEvent;

/**
 * Base class for Log4j file appenders with specific design characteristics;
 * namely, the appenders:
 * <ol>
 *   <li>Buffer logs, e.g. for streaming compression.</li>
 *   <li>Rollover log files based on some policy, e.g. exceeding a threshold
 *   size.</li>
 *   <li>Flush and synchronize log files (e.g. to remote storage) based on how
 *   fresh they are.</li>
 * </ol>
 * For instance, such an appender might compress log events as they are
 * generated, while still flushing and uploading them to remote storage a few
 * seconds after an error log event.
 * <p></p>
 * This class handles keeping track of how fresh the logs are and the high-level
 * logic to trigger flushing, syncing, and rollover at the appropriate times.
 * Derived classes must implement methods to do the actual flushing, syncing,
 * and rollover as well as indicate whether rollover is necessary.
 * <p></p>
 * The freshness property maintained by this class allows users to specify the
 * delay between a log event being generated and the log file being flushed and
 * synchronized. Specifically, this class maintains a soft and hard timeout per
 * log level:
 * <ul>
 *   <li><b>hard timeout</b> - the maximum delay between when a log event is
 *   generated and the file should be flushed and synchronized.</li>
 *   <li><b>soft timeout</b> - same as the hard timeout except it gets reset
 *   every time a new log event with the same log level is generated.</li>
 * </ul>
 * The shortest timeout in each log level determines when a log file will be
 * flushed and synchronized.
 * <p></p>
 * For instance, let's assume the soft and hard timeouts for ERROR logs are set
 * to 5 seconds and 5 minutes respectively. Now imagine an ERROR log event is
 * generated at t = 0s. This class will trigger a flush at t = 5s unless another
 * ERROR log event is generated before then. If one is generated at t = 4s, then
 * this class will omit the flush at t = 5s and trigger a flush at t = 9s. If
 * ERROR log events keep being generated before a flush occurs, then this class
 * will definitely trigger a flush at t = 5min based on the hard timeout.
 * <p></p>
 * Maintaining these timeouts per log level allows us to flush logs sooner if
 * more important log levels occur. For instance, we can set smaller timeouts
 * for ERROR log events compared to DEBUG log events.
 */
public abstract class AbstractBufferedRollingFileAppender extends EnhancedAppenderSkeleton
    implements Flushable
{
  protected String currentLogPath = null;

  // Appender settings, some of which may be set by Log4j through reflection.
  // For descriptions of the properties, see their setters below.
  private boolean closeFileOnShutdown = true;
  private final HashMap<Level, Long> flushHardTimeoutPerLevel = new HashMap<>();
  private final HashMap<Level, Long> flushSoftTimeoutPerLevel = new HashMap<>();
  private int timeoutCheckPeriod = 1000;

  private long flushHardTimeoutTimestamp;
  private long flushSoftTimeoutTimestamp;
  // The maximum soft timeout allowed. This is used while the app is shutting
  // down to increase the likelihood of flushing before the app finishes
  // shutting down.
  private long flushMaximumSoftTimeout;

  private final BackgroundFlushThread backgroundFlushThread = new BackgroundFlushThread();
  private final BackgroundSyncThread backgroundSyncThread = new BackgroundSyncThread();

  private boolean activated = false;

  public AbstractBufferedRollingFileAppender () {
    // The default flush timeout values below are optimized for high latency
    // remote persistent storage such as object stores or HDFS
    flushHardTimeoutPerLevel.put(Level.FATAL, 5L * 60 * 1000 /* 5 min */);
    flushHardTimeoutPerLevel.put(Level.ERROR, 5L * 60 * 1000 /* 5 min */);
    flushHardTimeoutPerLevel.put(Level.WARN, 10L * 60 * 1000 /* 10 min */);
    flushHardTimeoutPerLevel.put(Level.INFO, 30L * 60 * 1000 /* 30 min */);
    flushHardTimeoutPerLevel.put(Level.DEBUG, 30L * 60 * 1000 /* 30 min */);
    flushHardTimeoutPerLevel.put(Level.TRACE, 30L * 60 * 1000 /* 30 min */);

    flushSoftTimeoutPerLevel.put(Level.FATAL, 5L * 1000 /* 5 sec */);
    flushSoftTimeoutPerLevel.put(Level.ERROR, 10L * 1000 /* 10 sec */);
    flushSoftTimeoutPerLevel.put(Level.WARN, 15L * 1000 /* 15 sec */);
    flushSoftTimeoutPerLevel.put(Level.INFO, 3L * 60 * 1000 /* 3 min */);
    flushSoftTimeoutPerLevel.put(Level.DEBUG, 3L * 60 * 1000 /* 3 min */);
    flushSoftTimeoutPerLevel.put(Level.TRACE, 3L * 60 * 1000 /* 3 min */);
  }

  /**
   * Sets whether to close the log file upon receiving a shutdown signal before
   * the JVM exits. If set to false, the appender will continue appending logs
   * even while the JVM is shutting down and the appender will do its best to
   * sync those logs before the JVM shuts down. This presents a tradeoff
   * between capturing more log events and potential data loss if the log events
   * cannot be flushed and synced before the JVM shuts down.
   * @param closeFileOnShutdown Whether to close the log file on shutdown
   */
  public void setCloseFileOnShutdown (boolean closeFileOnShutdown) {
    this.closeFileOnShutdown = closeFileOnShutdown;
  }

  /**
   * Sets the per-log-level hard timeouts for flushing.
   * @param csvTimeouts A CSV string of kv-pairs. The key being the log-level in
   * all caps and the value being the hard timeout for flushing in minutes. E.g.
   * "INFO=30,WARN=10,ERROR=5"
   */
  public void setFlushHardTimeoutsInMinutes (String csvTimeouts) {
    for (String token : csvTimeouts.split(",")) {
      String[] kv = token.split("=");
      flushHardTimeoutPerLevel.put(Level.toLevel(kv[0]), Long.parseLong(kv[1]) * 60 * 1000);
    }
  }

  /**
   * Sets the per-log-level soft timeouts for flushing.
   * @param csvTimeouts A CSV string of kv-pairs. The key being the log-level in
   * all caps and the value being the soft timeout for flushing in seconds. E.g.
   * "INFO=180,WARN=15,ERROR=10"
   */
  public void setFlushSoftTimeoutsInSeconds (String csvTimeouts) {
    for (String token : csvTimeouts.split(",")) {
      String[] kv = token.split("=");
      flushSoftTimeoutPerLevel.put(Level.toLevel(kv[0]), Long.parseLong(kv[1]) * 1000);
    }
  }

  /**
   * Sets the period between checking for soft/hard timeouts (and then
   * triggering a flush and sync). Care should be taken to ensure this period
   * does not significantly differ from the lowest timeout since that will
   * cause undue delay from when a timeout expires and when a flush occurs.
   * @param milliseconds The period in milliseconds
   */
  public void setTimeoutCheckPeriod (int milliseconds) {
    timeoutCheckPeriod = milliseconds;
  }

  /**
   * Sets the hard timeout timestamp for the next flush. This method is
   * primarily used for unit testing.
   * @param timestamp Timestamp as milliseconds since the UNIX epoch
   */
  public void setFlushHardTimeoutTimestamp (long timestamp) {
    flushHardTimeoutTimestamp = timestamp;
  }

  /**
   * Sets the soft timeout timestamp for the next flush. This method is
   * primarily used for unit testing.
   * @param timestamp Timestamp as milliseconds since the UNIX epoch
   */
  public void setFlushSoftTimeoutTimestamp (long timestamp) {
    flushSoftTimeoutTimestamp = timestamp;
  }

  /**
   * Activates the appender's options.
   * <p></p>
   * This method is {@code final} to ensure it is not overridden by derived
   * classes since this base class needs to perform actions before/after the
   * derived class' {@link #activateOptionsHook()} method.
   */
  @Override
  public final void activateOptions () {
    if (closed) {
      logWarn("Already closed so cannot activate options.");
      return;
    }

    if (activated) {
      logWarn("Already activated.");
      return;
    }

    resetFreshnessTimeouts();
    try {
      activateOptionsHook();
      if (closeFileOnShutdown) {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
      }
      backgroundFlushThread.start();
      backgroundSyncThread.start();

      activated = true;
    } catch (Exception ex) {
      logError("Failed to activate appender.", ex);
      closed = true;
    }
  }

  /**
   * Closes the appender.
   * <p></p>
   * This method is {@code final} to ensure it is not overridden by derived
   * classes since this base class needs to perform actions before/after the
   * derived class' {@link #closeHook()} method.
   */
  @Override
  public final synchronized void close () {
    if (closed) {
      return;
    }

    if (closeFileOnShutdown) {
      try {
        closeHook();
      } catch (Exception ex) {
        // Just log the failure but continue the close process
        logError("closeHook failed.", ex);
      }
      backgroundSyncThread.addSyncRequest(currentLogPath, true);
      backgroundSyncThread.addShutdownRequest();
    } else {
      try {
        // Flush now just in case we shut down before a timeout expires (and
        // triggers a flush)
        flush();
      } catch (IOException e) {
        logError("Failed to flush", e);
      }
      backgroundSyncThread.addSyncRequest(currentLogPath, false);
    }

    closed = true;
  }

  /**
   * Appends the given log event to the file (subject to any buffering by the
   * derived class). This method may also trigger a rollover and sync if the
   * derived class' {@link #rolloverRequired()} method returns true.
   * <p></p>
   * This method is {@code final} to ensure it is not overridden by derived
   * classes since this base class needs to perform actions before/after the
   * derived class' {@link #appendHook(LoggingEvent)} method. This method is
   * also marked {@code synchronized} since it can be called from multiple
   * logging threads.
   * @param loggingEvent The log event
   */
  @Override
  public final synchronized void append (LoggingEvent loggingEvent) {
    try {
      appendHook(loggingEvent);

      if (false == rolloverRequired()) {
        updateFreshnessTimeouts(loggingEvent);
      } else {
        backgroundSyncThread.addSyncRequest(currentLogPath, true);
        resetFreshnessTimeouts();
        startNewLogFile(loggingEvent.getTimeStamp());
      }
    } catch (Exception ex) {
      getErrorHandler().error("Failed to write log event.", ex, ErrorCode.WRITE_FAILURE);
    }
  }

  /**
   * @return Whether the appender requires a layout
   */
  @Override
  public boolean requiresLayout () {
    return true;
  }

  /**
   * Activates appender options for derived appenders.
   */
  protected abstract void activateOptionsHook () throws Exception;

  /**
   * Closes the derived appender. Once closed, the appender cannot be reopened.
   */
  protected abstract void closeHook () throws Exception;

  /**
   * @return Whether to trigger a rollover
   */
  protected abstract boolean rolloverRequired () throws Exception;

  /**
   * Starts a new log file.
   * @param lastRolloverTimestamp Timestamp of the last event that was logged
   * before calling this method (useful for naming the new log file).
   */
  protected abstract void startNewLogFile (long lastRolloverTimestamp) throws Exception;

  /**
   * Synchronizes the log file (e.g. by uploading it to remote storage).
   * @param path Path of the log file to sync
   * @param deleteFile Whether the log file can be deleted after syncing.
   */
  protected abstract void sync (String path, boolean deleteFile) throws Exception;

  /**
   * Appends a log event to the file.
   * @param event The log event
   */
  protected abstract void appendHook (LoggingEvent event) throws Exception;

  /**
   * Resets the soft/hard freshness timeouts.
   */
  private void resetFreshnessTimeouts () {
    flushHardTimeoutTimestamp = Long.MAX_VALUE;
    flushSoftTimeoutTimestamp = Long.MAX_VALUE;
    if (Thread.currentThread().isInterrupted()) {
      // Since the thread has been interrupted (presumably because the app is
      // being shut down), lower the maximum soft timeout to increase the
      // likelihood that the log will be synced before the app shuts down.
      flushMaximumSoftTimeout = flushSoftTimeoutPerLevel.get(Level.FATAL);
    } else {
      flushMaximumSoftTimeout = flushSoftTimeoutPerLevel.get(Level.TRACE);
    }
  }

  /**
   * Updates the soft/hard freshness timeouts based on the given log event's log
   * level and timestamp.
   * @param loggingEvent The log event
   */
  private void updateFreshnessTimeouts (LoggingEvent loggingEvent) {
    Level level = loggingEvent.getLevel();
    long timeoutTimestamp = loggingEvent.timeStamp + flushHardTimeoutPerLevel.get(level);
    flushHardTimeoutTimestamp = Math.min(flushHardTimeoutTimestamp, timeoutTimestamp);

    flushMaximumSoftTimeout = Math.min(flushMaximumSoftTimeout,
                                       flushSoftTimeoutPerLevel.get(level));
    timeoutTimestamp = loggingEvent.timeStamp + flushMaximumSoftTimeout;
    flushSoftTimeoutTimestamp = Math.min(flushSoftTimeoutTimestamp, timeoutTimestamp);
  }

  /**
   * Flushes and synchronizes the log file if one of the freshness timeouts has
   * been reached.
   * <p></p>
   * This method is marked {@code synchronized} since it can be called from
   * logging threads and the background thread that monitors the freshness
   * timeouts.
   * @throws IOException on I/O error
   */
  private synchronized void flushAndSyncIfNecessary () throws IOException {
    long ts = System.currentTimeMillis();
    if (ts > flushSoftTimeoutTimestamp || ts > flushHardTimeoutTimestamp) {
      flush();
      backgroundSyncThread.addSyncRequest(currentLogPath, false);
      resetFreshnessTimeouts();
    }
  }

  /**
   * Periodically flushes and syncs the current log file if we've exceeded one
   * of the freshness timeouts.
   */
  private class BackgroundFlushThread extends Thread {
    @Override
    public void run () {
      while (true) {
        try {
          flushAndSyncIfNecessary();
          sleep(timeoutCheckPeriod);
        } catch (IOException e) {
          logError("Failed to flush buffered appender in the background", e);
        } catch (InterruptedException e) {
          if (closeFileOnShutdown) {
            logDebug("Received interrupt message for graceful shutdown of BackgroundFlushThread");
            break;
          }
        }
      }
    }
  }

  /**
   * Thread to synchronize log files in the background (by calling
   * {@link #sync(String, boolean) sync}). The thread maintains a request queue
   * that callers should populate.
   */
  private class BackgroundSyncThread extends Thread {
    private final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();

    @Override
    public void run () {
      while (true) {
        try {
          Request request = requests.take();
          if (request instanceof SyncRequest) {
            SyncRequest syncRequest = (SyncRequest)request;
            try {
              sync(syncRequest.logFilePath, syncRequest.deleteFile);
            } catch (Exception ex) {
              logError("Failed to sync '" + syncRequest.logFilePath + "'", ex);
            }
          } else if (request instanceof ShutdownRequest) {
            logDebug("Received shutdown request");
            break;
          }
        } catch (InterruptedException e) {
          // Ignore the exception since we want to continue syncing logs even
          // in case of exceptions (the thread only shuts down when a shutdown
          // request is made)
        }
      }
    }

    /**
     * Adds a shutdown request to the request queue
     */
    public void addShutdownRequest () {
      logDebug("Adding shutdown request");
      Request shutdownRequest = new ShutdownRequest();
      while (false == requests.offer(shutdownRequest)) {}
    }

    /**
     * Adds a sync request to the request queue
     * @param logFilePath Path of the log file to sync
     * @param deleteFile Whether the log file can be deleted after syncing.
     */
    public void addSyncRequest (String logFilePath, boolean deleteFile) {
      Request syncRequest = new SyncRequest(logFilePath, deleteFile);
      while (false == requests.offer(syncRequest)) {}
    }

    private class Request {}

    private class ShutdownRequest extends Request {}

    private class SyncRequest extends Request {
      public final String logFilePath;
      public final boolean deleteFile;

      public SyncRequest (String logFilePath, boolean deleteFile) {
        this.logFilePath = logFilePath;
        this.deleteFile = deleteFile;
      }
    }
  }
}
