package com.yscope.logging.log4j1;

import java.util.ArrayList;

/**
 * A class to extract all date conversion patterns from a Log4j conversion
 * pattern. This is useful for Log4j appenders that want to store a log event's
 * message separate from its timestamp.
 */
class DateConversionPatternExtractor {
  private enum STATES {
    LITERAL, PERCENT, DATE_SPECIFIER, DATE_SPECIFIER_OPEN_BRACE, DATE_SPECIFIER_OPEN_QUOTE,
  }

  private final ArrayList<SubstringBounds> dateConversionPatterns = new ArrayList<>();
  private final String conversionPatternWithoutDates;

  public DateConversionPatternExtractor (String conversionPattern) {
    // The parsing algorithm uses a state machine with the states and
    // transitions in the table below. States are named based on the character
    // that led to that state.
    //
    // | Current state             | Transition character | Next state                |
    // |---------------------------|----------------------|---------------------------|
    // | LITERAL                   | [^%]                 | LITERAL                   |
    // | LITERAL                   | %                    | PERCENT                   |
    // | PERCENT                   | [^d]                 | LITERAL                   |
    // | PERCENT                   | d                    | DATE_SPECIFIER            |
    // | DATE_SPECIFIER            | [^{]                 | LITERAL                   |
    // | DATE_SPECIFIER            | {                    | DATE_SPECIFIER_OPEN_BRACE |
    // | DATE_SPECIFIER_OPEN_BRACE | [^'}]                | DATE_SPECIFIER_OPEN_BRACE |
    // | DATE_SPECIFIER_OPEN_BRACE | '                    | DATE_SPECIFIER_OPEN_QUOTE |
    // | DATE_SPECIFIER_OPEN_BRACE | }                    | LITERAL                   |
    // | DATE_SPECIFIER_OPEN_QUOTE | [^']                 | DATE_SPECIFIER_OPEN_QUOTE |
    // | DATE_SPECIFIER_OPEN_QUOTE | '                    | DATE_SPECIFIER_OPEN_BRACE |

    STATES currentState = STATES.LITERAL;

    int conversionPatternBeginOffset = 0;
    int conversionPatternEndOffset = 0;
    StringBuilder newPatternBuilder = new StringBuilder();
    for (int i = 0; i < conversionPattern.length(); ++i) {
      char c = conversionPattern.charAt(i);

      switch (currentState) {
        case LITERAL:
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
              dateConversionPatterns.add(new SubstringBounds(conversionPatternBeginOffset,
                                                             conversionPatternEndOffset));
            }
            currentState = STATES.LITERAL;
          }
          break;
        case DATE_SPECIFIER:
          if ('{' == c) {
            currentState = STATES.DATE_SPECIFIER_OPEN_BRACE;
          } else {
            // End of date conversion pattern
            newPatternBuilder.append(conversionPattern, conversionPatternEndOffset,
                                     conversionPatternBeginOffset);
            conversionPatternEndOffset = i;

            dateConversionPatterns.add(new SubstringBounds(conversionPatternBeginOffset,
                                                           conversionPatternEndOffset));

            currentState = STATES.LITERAL;
          }
          break;
        case DATE_SPECIFIER_OPEN_BRACE:
          if ('\'' == c) {
            currentState = STATES.DATE_SPECIFIER_OPEN_QUOTE;
          } else if ('}' == c) {
            // End of date conversion pattern
            newPatternBuilder.append(conversionPattern, conversionPatternEndOffset,
                                     conversionPatternBeginOffset);
            conversionPatternEndOffset = i + 1;

            dateConversionPatterns.add(new SubstringBounds(conversionPatternBeginOffset,
                                                           conversionPatternEndOffset));

            currentState = STATES.LITERAL;
          }
          break;
        case DATE_SPECIFIER_OPEN_QUOTE:
          if ('\'' == c) {
            currentState = STATES.DATE_SPECIFIER_OPEN_BRACE;
          }
          break;
      }
    }
    if (STATES.DATE_SPECIFIER == currentState) {
      // End of date conversion pattern
      newPatternBuilder.append(conversionPattern, conversionPatternEndOffset,
                               conversionPatternBeginOffset);
      conversionPatternEndOffset = conversionPattern.length();

      dateConversionPatterns.add(new SubstringBounds(conversionPatternBeginOffset,
                                                     conversionPatternEndOffset));
    }
    if (conversionPatternEndOffset < conversionPattern.length()) {
      newPatternBuilder.append(conversionPattern, conversionPatternEndOffset,
                               conversionPattern.length());
    }

    conversionPatternWithoutDates = newPatternBuilder.toString();
  }

  /**
   * @return All date conversion patterns (e.g. '%d{ISO8601}') extracted from
   * the conversion pattern.
   */
  public ArrayList<SubstringBounds> getDateConversionPatterns () {
    return dateConversionPatterns;
  }

  /**
   * @return The conversion pattern with all date conversion patterns extracted.
   */
  public String getConversionPatternWithoutDates () {
    return conversionPatternWithoutDates;
  }
}
