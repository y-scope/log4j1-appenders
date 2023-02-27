package com.yscope.logging.log4j1;

import java.util.Date;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.helpers.ISO8601DateFormat;
import org.apache.log4j.helpers.LogLog;

/**
 * {@code org.apache.log4j.AppenderSkeleton} enhanced with logging methods that
 * prepend the name of the logger.
 */
public abstract class EnhancedAppenderSkeleton extends AppenderSkeleton {
  private static final ISO8601DateFormat timestampFormatter = new ISO8601DateFormat();

  protected void logDebug (String msg) {
    LogLog.debug(getCurrentTimestampString() + " [" + name + "] " + msg);
  }

  protected void logDebug (String msg, Throwable t) {
    LogLog.debug(getCurrentTimestampString() + " [" + name + "] " + msg, t);
  }

  protected void logWarn (String msg) {
    LogLog.warn(getCurrentTimestampString() + " [" + name + "] " + msg);
  }

  protected void logWarn (String msg, Throwable t) {
    LogLog.warn(getCurrentTimestampString() + " [" + name + "] " + msg, t);
  }

  protected void logError (String msg) {
    LogLog.error(getCurrentTimestampString() + " [" + name + "] " + msg);
  }

  protected void logError (String msg, Throwable t) {
    LogLog.error(getCurrentTimestampString() + " [" + name + "] " + msg, t);
  }

  private static String getCurrentTimestampString () {
    return timestampFormatter.format(new Date());
  }
}
