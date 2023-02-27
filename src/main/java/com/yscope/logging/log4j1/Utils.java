package com.yscope.logging.log4j1;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.log4j.Layout;

public class Utils {
  /**
   * Writes the given throwable's string representation to the given output
   * stream
   * @param throwableStrRepresentation
   * @param outputStream
   * @throws IOException on I/O error
   */
  public static void writeThrowableStrRepresentation (
      String[] throwableStrRepresentation,
      OutputStream outputStream
  ) throws IOException {
    byte[] lineSeparatorBytes = Layout.LINE_SEP.getBytes(StandardCharsets.UTF_8);
    for (String value : throwableStrRepresentation) {
      outputStream.write(value.getBytes(StandardCharsets.UTF_8));
      outputStream.write(lineSeparatorBytes);
    }
  }
}
