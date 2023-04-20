package com.yscope.logging.log4j1;

import java.util.ArrayList;

import org.apache.log4j.EnhancedPatternLayout;
import org.apache.log4j.Layout;
import org.apache.log4j.PatternLayout;

/**
 * A class to extract all date conversion patterns from a Log4j conversion
 * pattern. This is useful for Log4j appenders that want to store a log event's
 * message separate from its timestamp.
 * <p>
 * For example, given the conversion pattern:
 * <pre>"%d%d %d{HH:mm:ss' on 'yy/MM/dd}{GMT-5:30}%d %r %p %c{1}: %m%n"</pre>
 * This class would extract 5 date conversion patterns:
 * <ol>
 *   <li>"%d"</li>
 *   <li>"%d"</li>
 *   <li>"%d{HH:mm:ss' on 'yy/MM/dd}{GMT-5:30}"</li>
 *   <li>"%d"</li>
 *   <li>"%r"</li>
 * </ol>
 *
 * And the conversion pattern without the dates would be:
 * <pre>"   %p %c{1}: %m%n"</pre>
 */
class DateConversionPatternExtractor {
  private enum STATES {
    LITERAL,
    PERCENT,
    DATE_SPECIFIER,
    DATE_SPECIFIER_OPEN_BRACE,
    DATE_SPECIFIER_CLOSE_BRACE,
    DATE_SPECIFIER_OPEN_QUOTE,
    TIME_ZONE_ID_OPEN_BRACE,
    TIME_ZONE_ID_CLOSE_BRACE,
  }

  private final ArrayList<DateConversionPattern> dateConversionPatterns = new ArrayList<>();
  private final String conversionPatternWithoutDates;

  public DateConversionPatternExtractor (Layout layout) {
    // The parsing algorithm uses a state machine with the states and
    // transitions in the table below. States are named based on the character
    // that led to that state.
    //
    // | Current state              | Transition character  | Action                              | Next state                 |
    // |----------------------------|-----------------------|-------------------------------------|----------------------------|
    // | LITERAL                    | [^%]                  | -                                   | -                          |
    // | LITERAL                    | %                     | Start pattern                       | PERCENT                    |
    // | PERCENT                    | [^d]                  | if 'r' == c: Capture pattern        | LITERAL                    |
    // | PERCENT                    | d                     | -                                   | DATE_SPECIFIER             |
    // | DATE_SPECIFIER             | [^%{]                 | Capture pattern                     | LITERAL                    |
    // | DATE_SPECIFIER             | %                     | Capture pattern & start new pattern | PERCENT                    |
    // | DATE_SPECIFIER             | {                     | -                                   | DATE_SPECIFIER_OPEN_BRACE  |
    // | DATE_SPECIFIER_OPEN_BRACE  | [^'}]                 | -                                   | -                          |
    // | DATE_SPECIFIER_OPEN_BRACE  | '                     | -                                   | DATE_SPECIFIER_OPEN_QUOTE  |
    // | DATE_SPECIFIER_OPEN_BRACE  | }                     | -                                   | DATE_SPECIFIER_CLOSE_BRACE |
    // | DATE_SPECIFIER_CLOSE_BRACE | [^{%]                 | Capture pattern                     | LITERAL                    |
    // | DATE_SPECIFIER_CLOSE_BRACE | %                     | Capture pattern & start new pattern | PERCENT                    |
    // | DATE_SPECIFIER_CLOSE_BRACE | { && enhancedPattern  | -                                   | TIME_ZONE_ID_OPEN_BRACE    |
    // | DATE_SPECIFIER_CLOSE_BRACE | { && !enhancedPattern | Capture pattern                     | LITERAL                    |
    // | DATE_SPECIFIER_OPEN_QUOTE  | [^']                  | -                                   | -                          |
    // | DATE_SPECIFIER_OPEN_QUOTE  | '                     | -                                   | DATE_SPECIFIER_OPEN_BRACE  |
    // | TIME_ZONE_ID_OPEN_BRACE    | [^}]                  | -                                   | -                          |
    // | TIME_ZONE_ID_OPEN_BRACE    | }                     | Capture pattern                     | TIME_ZONE_ID_CLOSE_BRACE   |
    // | TIME_ZONE_ID_CLOSE_BRACE   | [^%]                  | -                                   | LITERAL                    |
    // | TIME_ZONE_ID_CLOSE_BRACE   | %                     | Record pattern begin                | PERCENT                    |

    boolean patternIsEnhanced = false;
    String conversionPattern;
    if (layout instanceof EnhancedPatternLayout) {
      EnhancedPatternLayout patternLayout = (EnhancedPatternLayout)layout;
      conversionPattern = patternLayout.getConversionPattern();
      patternIsEnhanced = true;
    } else if (layout instanceof PatternLayout) {
      PatternLayout patternLayout = (PatternLayout)layout;
      conversionPattern = patternLayout.getConversionPattern();
    } else {
      throw new IllegalArgumentException("Layout type " + layout.getClass().getName()
                                             + " doesn't contain a conversion pattern.");
    }

    STATES currentState = STATES.LITERAL;

    int conversionPatternBeginOffset = 0;
    int conversionPatternEndOffset = 0;
    int formatBeginOffset = 0;
    String format = null;
    int timeZoneIdBeginOffset = 0;
    StringBuilder newPatternBuilder = new StringBuilder();
    for (int i = 0; i < conversionPattern.length(); ++i) {
      char c = conversionPattern.charAt(i);

      switch (currentState) {
        case LITERAL:
        case TIME_ZONE_ID_CLOSE_BRACE:
          if ('%' == c) {
            conversionPatternBeginOffset = i;
            currentState = STATES.PERCENT;
          }
          break;
        case PERCENT:
          if ('d' == c) {
            currentState = STATES.DATE_SPECIFIER;
          } else {
            if ('r' == c) {
              newPatternBuilder.append(conversionPattern, conversionPatternEndOffset,
                                       conversionPatternBeginOffset);
              conversionPatternEndOffset = i + 1;
              dateConversionPatterns.add(
                  new DateConversionPattern('r', null, null, conversionPatternBeginOffset));
            }
            currentState = STATES.LITERAL;
          }
          break;
        case DATE_SPECIFIER:
          if ('{' == c) {
            formatBeginOffset = i + 1;
            currentState = STATES.DATE_SPECIFIER_OPEN_BRACE;
          } else {
            // End of date conversion pattern
            newPatternBuilder.append(conversionPattern, conversionPatternEndOffset,
                                     conversionPatternBeginOffset);
            conversionPatternEndOffset = i;

            dateConversionPatterns.add(
                new DateConversionPattern('d', null, null, conversionPatternBeginOffset));

            if ('%' == c) {
              conversionPatternBeginOffset = i;
              currentState = STATES.PERCENT;
            } else {
              currentState = STATES.LITERAL;
            }
          }
          break;
        case DATE_SPECIFIER_OPEN_BRACE:
          if ('\'' == c) {
            currentState = STATES.DATE_SPECIFIER_OPEN_QUOTE;
          } else if ('}' == c) {
            format = conversionPattern.substring(formatBeginOffset, i);
            currentState = STATES.DATE_SPECIFIER_CLOSE_BRACE;
          }
          break;
        case DATE_SPECIFIER_CLOSE_BRACE:
          if ('{' == c && patternIsEnhanced) {
            timeZoneIdBeginOffset = i + 1;
            currentState = STATES.TIME_ZONE_ID_OPEN_BRACE;
          } else {
            // End of date conversion pattern
            newPatternBuilder.append(conversionPattern, conversionPatternEndOffset,
                                     conversionPatternBeginOffset);
            conversionPatternEndOffset = i;

            dateConversionPatterns.add(
                new DateConversionPattern('d', format, null, conversionPatternBeginOffset));

            if ('%' == c) {
              conversionPatternBeginOffset = i;
              currentState = STATES.PERCENT;
            } else {
              currentState = STATES.LITERAL;
            }
          }
          break;
        case DATE_SPECIFIER_OPEN_QUOTE:
          if ('\'' == c) {
            currentState = STATES.DATE_SPECIFIER_OPEN_BRACE;
          }
          break;
        case TIME_ZONE_ID_OPEN_BRACE:
          if ('}' == c) {
            // End of date conversion pattern
            newPatternBuilder.append(conversionPattern, conversionPatternEndOffset,
                                     conversionPatternBeginOffset);
            conversionPatternEndOffset = i + 1;

            dateConversionPatterns.add(new DateConversionPattern(
                'd',
                format,
                conversionPattern.substring(timeZoneIdBeginOffset, i),
                conversionPatternBeginOffset
            ));

            currentState = STATES.TIME_ZONE_ID_CLOSE_BRACE;
          }
          break;
      }
    }
    // Handle the conversion pattern ending with an unprocessed date conversion
    // pattern. This could happen with "%d" or "%d{...}" because we can't know
    // they're complete unless we read the character following the date pattern
    // or the pattern ends.
    if (STATES.DATE_SPECIFIER == currentState || STATES.DATE_SPECIFIER_CLOSE_BRACE == currentState)
    {
      // End of date conversion pattern
      newPatternBuilder.append(conversionPattern, conversionPatternEndOffset,
                               conversionPatternBeginOffset);
      conversionPatternEndOffset = conversionPattern.length();

      dateConversionPatterns.add(
          new DateConversionPattern('d', format, null, conversionPatternBeginOffset));
    }

    // Append any remaining text after the last date conversion pattern
    if (conversionPatternEndOffset < conversionPattern.length()) {
      newPatternBuilder.append(conversionPattern, conversionPatternEndOffset,
                               conversionPattern.length());
    }

    conversionPatternWithoutDates = newPatternBuilder.toString();
  }

  /**
   * @return All date conversion patterns extracted from the conversion pattern.
   */
  public ArrayList<DateConversionPattern> getDateConversionPatterns () {
    return dateConversionPatterns;
  }

  /**
   * @return The conversion pattern with all date conversion patterns extracted.
   */
  public String getConversionPatternWithoutDates () {
    return conversionPatternWithoutDates;
  }
}
