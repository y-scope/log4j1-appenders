package com.yscope.logging.log4j1;

import java.util.Objects;

/**
 * A simple class to contain the components of a Log4j Layout's date conversion
 * pattern.
 */
public class DateConversionPattern {
  public char specifier;
  public String format;
  public String timeZoneId;
  // The offset of this date conversion pattern in the conversion pattern that
  // it was extracted from (if at all)
  public int offsetInConversionPattern;

  public DateConversionPattern (char specifier, String format, String timeZoneId,
                                int offsetInConversionPattern)
  {
    this.specifier = specifier;
    this.format = format;
    this.timeZoneId = timeZoneId;
    this.offsetInConversionPattern = offsetInConversionPattern;
  }

  @Override
  public boolean equals (Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    DateConversionPattern rhs = (DateConversionPattern)o;
    return specifier == rhs.specifier
        && Objects.equals(format, rhs.format)
        && Objects.equals(timeZoneId, rhs.timeZoneId)
        && offsetInConversionPattern == rhs.offsetInConversionPattern;
  }

  @Override
  public int hashCode () {
    return Objects.hash(specifier, format, timeZoneId, offsetInConversionPattern);
  }

  @Override
  public String toString () {
    StringBuilder builder = new StringBuilder();
    builder.append("%");
    builder.append(specifier);
    if (null != format) {
      builder.append('{');
      builder.append(format);
      builder.append('}');
    }
    if (null != timeZoneId) {
      builder.append('{');
      builder.append(timeZoneId);
      builder.append('}');
    }
    return builder.toString();
  }
}
