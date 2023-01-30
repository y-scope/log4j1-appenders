package com.yscope.logging.log4j1;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.helpers.LogLog;

/**
 * {@code org.apache.log4j.AppenderSkeleton} enhanced with logging methods that
 * prepend the name of the logger.
 */
public abstract class EnhancedAppenderSkeleton extends AppenderSkeleton {
  protected void logDebug (String msg) {
    LogLog.debug("[" + name + "] " + msg);
  }

  protected void logDebug (String msg, Throwable t) {
    LogLog.debug("[" + name + "] " + msg, t);
  }

  protected void logWarn (String msg) {
    LogLog.warn("[" + name + "] " + msg);
  }

  protected void logWarn (String msg, Throwable t) {
    LogLog.warn("[" + name + "] " + msg, t);
  }

  protected void logError (String msg) {
    LogLog.error("[" + name + "] " + msg);
  }

  protected void logError (String msg, Throwable t) {
    LogLog.error("[" + name + "] " + msg, t);
  }
}
