package com.yscope.logging.log4j1;

import org.apache.log4j.Level;
import org.apache.log4j.spi.LoggingEvent;

import java.io.Flushable;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * This abstract class enforces an opinionated workflow, skeleton interfaces and
 * hooks optimized towards buffered rolling file appender implementations with
 * remote persistent storage. For example, an appender which emits logs events
 * to S3 object store while also utilizing streaming compression to compress the
 * log events as they are generated. To keep the logs on remote persistent
 * storage relatively in sync with logs produced by the appender without the
 * need to frequently flush the buffer, an implementation of verbosity-aware
 * hard+soft timeout based log freshness policy is provided. With this
 * mechanism, derived classes can enjoy the benefit of keeping remote log file
 * synchronized efficiently.
 */
public abstract class AbstractBufferedRollingFileAppender extends EnhancedAppenderSkeleton
    implements Flushable
{
  protected long hardFlushTimeoutEpoch;
  protected long softFlushTimeoutCap;
  protected long softFlushTimeoutEpoch;

  // Background flush thread is used to enforce log freshness policy even if
  // no new log events are observed. Background sync thread is used to
  // asynchronously push changes
  protected BackgroundFlushThread backgroundFlushThread = new BackgroundFlushThread();
  protected BackgroundSyncThread backgroundSyncThread = new BackgroundSyncThread();
  protected int backgroundSyncSleepTimeMs = 1000;
  protected boolean closeFileOnShutdown = true;

  protected String currentLogPath = null;
  protected long lastRolloverTimestamp = System.currentTimeMillis();

  // Hard and soft timeouts are leveraged to opportunistically flush the
  // output buffer. Hard timeout triggers a delayed buffer flush after a log
  // event has been observed. Soft timeout also triggers a delayed buffer
  // flush. However, before the soft timeout is triggered, it is extended if
  // a new log event is observed. After either hard or soft deadline is
  // triggered, the timeouts are reset until it is triggered again.
  private final HashMap<Level, Long> hardFlushTimeout = new HashMap<>();
  private final HashMap<Level, Long> softFlushTimeout = new HashMap<>();

  public AbstractBufferedRollingFileAppender () {
    // Default flush timeout values optimized high latency remote
    // persistent storage such as object store or HDFS are provided
    hardFlushTimeout.put(Level.OFF, Long.MAX_VALUE);
    hardFlushTimeout.put(Level.FATAL, 5L * 60 * 1000 /* 5 min */);
    hardFlushTimeout.put(Level.ERROR, 5L * 60 * 1000 /* 5 min */);
    hardFlushTimeout.put(Level.WARN, 10L * 60 * 1000 /* 10 min */);
    hardFlushTimeout.put(Level.INFO, 30L * 60 * 1000 /* 30 min */);
    hardFlushTimeout.put(Level.DEBUG, 30L * 60 * 1000 /* 30 min */);
    hardFlushTimeout.put(Level.TRACE, 30L * 60 * 1000 /* 30 min */);

    softFlushTimeout.put(Level.FATAL, 5L * 1000 /* 5 sec */);
    softFlushTimeout.put(Level.ERROR, 10L * 1000 /* 10 sec */);
    softFlushTimeout.put(Level.WARN, 15L * 1000 /* 15 sec */);
    softFlushTimeout.put(Level.INFO, 3L * 60 * 1000 /* 3 min */);
    softFlushTimeout.put(Level.DEBUG, 3L * 60 * 1000 /* 3 min */);
    softFlushTimeout.put(Level.TRACE, 3L * 60 * 1000 /* 3 min */);
  }

  /**
   * Closing the appender involves closing the buffered log file, submit sync
   * request as well as a shutdown request to terminate background executor
   * thread responsible for flushing and synchronization. Note that similar to
   * {@code append()}, this method is also intentionally made to be final to
   * ensure it is not overridden by derived class. To allow for user-defined
   * behaviors, user should override the following hook method:
   * {@code closeBufferedAppender()}, {@code sync()}, etc
   */
  @Override
  public final void close () {
    if (closeFileOnShutdown) {
      closeBufferedAppender();
      backgroundSyncThread.submitSyncRequest(currentLogPath, true);
      backgroundSyncThread.submitShutdownRequest();
    } else {
      try {
        // If closeFileOnShutdown, we flush + sync instead.
        // Since log appender is still running, further log append is
        // possible and tighter freshness policy is used to increase
        // log upload reliability after close.
        flush();
      } catch (IOException e) {
        logError("Failed to flush", e);
      }
      backgroundSyncThread.submitSyncRequest(currentLogPath, false);
    }
  }

  /**
   * Allows log4j or derived classes to activate the options in this class. In
   * addition, this method also starts two background threads used to
   * asynchronously flush output buffer to file and synchronize them to remote
   * persistent storage. A shutdown hook is installed to gracefully shut down
   * the appender if {@code closeFileOnShutdown} is {@code true}. Otherwise, the
   * appender will keep on appending logs until JVM exit and make the best
   * effort to upload logs. User will accept the risk higher chance of
   * data-loss, but gains the chance to capture logs after shutdown hook is
   * invoked.
   */
  @Override
  public void activateOptions () {
    resetFreshnessParameters();
    updateLogFilePath();
    if (closeFileOnShutdown) {
      Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }
    backgroundFlushThread.start();
    backgroundSyncThread.start();
  }

  /**
   * Append method is an opinionated sequence of steps involved in a file
   * rollover operation. This method is intentionally made to be final to ensure
   * it is not overridden by derived class. To allow for user-defined
   * behaviours, user should override the following hook methods:
   * {@code appendBufferedFile}, {@code rollOverRequired},
   * {@code resetFreshnessParameters()}, {@code updateLogFilePath()},
   * {@code startNewBufferedFile()}, etc
   * @param loggingEvent
   */
  @Override
  public final synchronized void append (LoggingEvent loggingEvent) {
    appendBufferedFile(loggingEvent);

    if (rolloverRequired()) {
      resetRolloverParameters(loggingEvent);
      resetFreshnessParameters();
      backgroundSyncThread.submitSyncRequest(currentLogPath, true);
      updateLogFilePath();
      startNewBufferedFile();
    } else {
      updateFreshnessParameters(loggingEvent);
    }
  }

  @Override
  public boolean requiresLayout () {
    return true;
  }

  /**
   * Method invoked by log4j library via reflection or manually by the user to
   * set the hard flush timeout of various log verbosities via a csv string
   * with the key being VERBOSITY string and value being minutes:
   * @param parameters i.e,. "INFO=30,WARN=10,ERROR=5"
   */
  public void setHardFlushTimeoutMinutes (String parameters) {
    for (String token : parameters.split(",")) {
      String[] kv = token.split("=");
      hardFlushTimeout.put(Level.toLevel(kv[0]), Long.parseLong(kv[1]) * 60 * 1000);
    }
  }

  /**
   * Method invoked by log4j library via reflection or manually by the user to
   * set the soft flush timeout of variouss log verbosities via a csv string
   * with the key being VERBOSITY string and value being seconds:
   * @param parameters i.e. "INFO=180,WARN=15,ERROR=10"
   */
  public void setSoftFlushTimeoutSeconds (String parameters) {
    for (String token : parameters.split(",")) {
      String[] kv = token.split("=");
      softFlushTimeout.put(Level.toLevel(kv[0]), Long.parseLong(kv[1]) * 1000);
    }
  }

  /**
   * Method invoked by log4j library via reflection or manually by the user to
   * disable (default enable) the closing of files upon receiving a shutdown
   * signal prior to JVM exit. This is a rarely used but useful functionality
   * when user wants to capture as much log events as possible after a shutdown
   * signal is received at the risk of data loss.
   * @param closeFileOnShutdown
   */
  public void setCloseFileOnShutdown (boolean closeFileOnShutdown) {
    this.closeFileOnShutdown = closeFileOnShutdown;
  }

  /**
   * Method invoked by log4j library via reflection or manually by the user to
   * adjust the amount of time which the background sync thread sleeps between
   * work iterations
   * @param backgroundSyncSleepTimeMs
   */
  public void setBackgroundSyncSleepTimeMs (int backgroundSyncSleepTimeMs) {
    this.backgroundSyncSleepTimeMs = backgroundSyncSleepTimeMs;
  }

  /**
   * In some cases, such as unit testing, we want to explicitly set the deadline
   * parameters to precisely control flushing behavior
   * @param epochMs
   */
  public void setHardFlushTimeoutEpoch (long epochMs) {
    hardFlushTimeoutEpoch = epochMs;
  }

  /**
   * In some cases, such as unit testing, we want to explicitly set the deadline
   * parameters to precisely control flushing behavior
   * @param epochMs
   */
  public void setSoftFlushTimeoutEpoch (long epochMs) {
    softFlushTimeoutEpoch = epochMs;
  }

  /**
   * The implementation shall determine the conditions to trigger rollover
   * @return Whether to trigger rollover
   */
  protected abstract boolean rolloverRequired ();

  /**
   * The implementation shall update the {@code currentLogPath} class member
   * variable. This method is triggered if rollover is required but prior to
   * {@code startNewBufferedFile()}
   */
  protected abstract void updateLogFilePath ();

  /**
   * The implementation shall set up a new buffered file using
   * {@code logfilePath} class member variable updated prior by the
   * {@code updateLogFilePath} method.
   */
  protected abstract void startNewBufferedFile ();

  /**
   * The implementation shall append the log event into derived class's buffered
   * file implementation.
   * @param loggingEvent
   */
  protected abstract void appendBufferedFile (LoggingEvent loggingEvent);

  /**
   * The implementation shall close the underlying buffered appender
   */
  protected abstract void closeBufferedAppender ();

  /**
   * The implementation of this abstract sync method shall fulfill the duty of
   * managing on-disk and/or remote log file life-cycles. For example: upload
   * files to remote storage while retaining 3 most recently used log files on
   * local on-disk storage.
   * @param path of log file on the local file system
   * @param deleteFile whenever the implementation sees fit
   */
  protected abstract void sync (String path, boolean deleteFile);

  /**
   * Reset freshness parameter means increasing the deadline to infinity. Only
   * when a new log message is appended will the deadline be updated.
   */
  protected void resetFreshnessParameters () {
    hardFlushTimeoutEpoch = Long.MAX_VALUE;
    softFlushTimeoutEpoch = Long.MAX_VALUE;
    if (Thread.currentThread().isInterrupted()) {
      // Soft cap is lowered to minimum after thread is interrupted to
      // increase reliability of log upload
      softFlushTimeoutCap = softFlushTimeout.get(Level.FATAL);
    } else {
      softFlushTimeoutCap = softFlushTimeout.get(Level.INFO);
    }
  }

  /**
   * Based on the log event's verbosity, update the freshness parameters
   * accordingly to the freshness policy configurations.
   * @param loggingEvent
   */
  protected void updateFreshnessParameters (LoggingEvent loggingEvent) {
    Level level = loggingEvent.getLevel();
    hardFlushTimeoutEpoch = Math.min(hardFlushTimeoutEpoch,
                                     loggingEvent.timeStamp + hardFlushTimeout.get(level));
    softFlushTimeoutCap = Math.min(softFlushTimeoutCap, softFlushTimeout.get(level));
    softFlushTimeoutEpoch = Math.min(softFlushTimeoutEpoch, softFlushTimeoutCap);
  }

  /**
   * Ensure the on-disk buffer of the buffered appender is synchronized with
   * remote persistent storage according to a set of soft/hard timeout. Note
   * that this method is marked as synchronized because file flushing could be
   * called in the background in the {@code BackgroundFlushThread}. We must
   * ensure flush does not have data race with append operations.
   * Synchronization after the flush is also processed in the background as to
   * ensure actual log append operation is unblocked as soon as possible.
   * @throws IOException on I/O error
   */
  protected synchronized void ensureLogFreshness () throws IOException {
    long ts = System.currentTimeMillis();
    if (ts > softFlushTimeoutEpoch || ts > hardFlushTimeoutEpoch) {
      flush();
      backgroundSyncThread.submitSyncRequest(currentLogPath, false);
      resetFreshnessParameters();
    }
  }

  /**
   * Resets statistical counters after a file rollover. User can extend this
   * method as necessary to reset additional statistical counters.
   * @param loggingEvent
   */
  protected void resetRolloverParameters (LoggingEvent loggingEvent) {
    lastRolloverTimestamp = loggingEvent.timeStamp;
  }

  /**
   * Periodically flushes buffered log appender when timeout-based freshness
   * guarantee policy is met
   */
  private class BackgroundFlushThread extends Thread {
    @Override
    public void run () {
      while (true) {
        try {
          ensureLogFreshness();
          sleep(backgroundSyncSleepTimeMs);
        } catch (IOException e) {
          logError("Failed to flush buffered appender in the background", e);
        } catch (InterruptedException e) {
          if (closeFileOnShutdown) {
            logDebug("Received interrupt message for graceful shutdown of "
                         + "BackgroundFlushThread");
            break;
          }
        }
      }
    }
  }

  /**
   * The background synchronization thread performs background buffer
   * synchronization. To customize the execution thread's behavior, override
   * {@code AbstractBufferedRollingFileAppender.sync()}.
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
            sync(syncRequest.path, syncRequest.deleteFile);
          } else if (request instanceof ShutdownRequest) {
            // Gracefully shutdown after receiving a shutdown poison request
            logDebug("Received shutdown request");
            break;
          }
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }

    public void submitShutdownRequest () {
      logDebug("Submitting shutdown request");
      Request shutdownRequest = new ShutdownRequest();
      while (true) {
        if (requests.offer(shutdownRequest)) {
          break;
        }
      }
    }

    public void submitSyncRequest (String path, boolean deleteFile) {
      Request syncRequest = new SyncRequest(path, deleteFile);
      while (true) {
        if (requests.offer(syncRequest)) {
          break;
        }
      }
    }

    private class Request {}

    private class ShutdownRequest extends Request {}

    private class SyncRequest extends Request {
      public String path;
      public boolean deleteFile;

      public SyncRequest (String path, boolean deleteFile) {
        this.path = path;
        this.deleteFile = deleteFile;
      }
    }
  }
}
