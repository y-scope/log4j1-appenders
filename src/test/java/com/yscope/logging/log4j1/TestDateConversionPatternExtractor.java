package com.yscope.logging.log4j1;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.EnhancedPatternLayout;
import org.apache.log4j.Layout;
import org.apache.log4j.PatternLayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class TestDateConversionPatternExtractor {
  @Test
  public void testExtraction () {
    DateConversionPattern datePattern;

    // Validate extracting %d at different positions in the conversion pattern
    datePattern = new DateConversionPattern('d', null, null, 0);
    testExtractingASingleSimpleDatePattern(
        "%d %p %c{1} %% %m%n",
        " %p %c{1} %% %m%n",
        datePattern
    );
    datePattern.offsetInConversionPattern = 3;
    testExtractingASingleSimpleDatePattern(
        "%p %d %c{1} %% %m%n",
        "%p  %c{1} %% %m%n",
        datePattern
    );
    datePattern.offsetInConversionPattern = 17;
    testExtractingASingleSimpleDatePattern(
        "%p %c{1} %% %m%n %d",
        "%p %c{1} %% %m%n ",
        datePattern
    );

    // Validate extracting %d{...} at different positions in the conversion
    // pattern
    datePattern = new DateConversionPattern('d', "HH:mm:ss' on 'yy/MM/dd", null, 0);
    testExtractingASingleSimpleDatePattern(
        "%d{HH:mm:ss' on 'yy/MM/dd} %p %c{1} %% %m%n",
        " %p %c{1} %% %m%n",
        datePattern
    );
    datePattern.offsetInConversionPattern = 3;
    testExtractingASingleSimpleDatePattern(
        "%p %d{HH:mm:ss' on 'yy/MM/dd} %c{1} %% %m%n",
        "%p  %c{1} %% %m%n",
        datePattern
    );
    datePattern.offsetInConversionPattern = 17;
    testExtractingASingleSimpleDatePattern(
        "%p %c{1} %% %m%n %d{HH:mm:ss' on 'yy/MM/dd}",
        "%p %c{1} %% %m%n ",
        datePattern
    );

    datePattern = new DateConversionPattern('d', "HH:mm:ss' on 'yy/MM/dd", "GMT-5:30", 0);
    testExtractingASingleDatePattern(
        "%d{HH:mm:ss' on 'yy/MM/dd}{GMT-5:30} %p %c{1} %% %m%n",
        true,
        " %p %c{1} %% %m%n",
        datePattern
    );
    datePattern.offsetInConversionPattern = 3;
    testExtractingASingleDatePattern(
        "%p %d{HH:mm:ss' on 'yy/MM/dd}{GMT-5:30} %c{1} %% %m%n",
        true,
        "%p  %c{1} %% %m%n",
        datePattern
    );
    datePattern.offsetInConversionPattern = 17;
    testExtractingASingleDatePattern(
        "%p %c{1} %% %m%n %d{HH:mm:ss' on 'yy/MM/dd}{GMT-5:30}",
        true,
        "%p %c{1} %% %m%n ",
        datePattern
    );

    // Validate extracting %r at different positions in the conversion pattern
    datePattern = new DateConversionPattern('r', null, null, 0);
    testExtractingASingleSimpleDatePattern(
        "%r %p %c{1} %% %m%n",
        " %p %c{1} %% %m%n",
        datePattern
    );
    datePattern.offsetInConversionPattern = 3;
    testExtractingASingleSimpleDatePattern(
        "%p %r %c{1} %% %m%n",
        "%p  %c{1} %% %m%n",
        datePattern
    );
    datePattern.offsetInConversionPattern = 17;
    testExtractingASingleSimpleDatePattern(
        "%p %c{1} %% %m%n %r",
        "%p %c{1} %% %m%n ",
        datePattern
    );

    ArrayList<DateConversionPattern> datePatterns = new ArrayList<>();

    // Validate extracting multiple simple date conversion patterns
    datePatterns.add(new DateConversionPattern('d', null, null, 0));
    datePatterns.add(new DateConversionPattern('d', null, null, 2));
    datePatterns.add(new DateConversionPattern('d', "HH:mm:ss' on 'yy/MM/dd", null, 5));
    datePatterns.add(new DateConversionPattern('d', null, null, 31));
    datePatterns.add(new DateConversionPattern('r', null, null, 34));
    testExtractingMultipleDatePatterns(
        "%d%d %d{HH:mm:ss' on 'yy/MM/dd}%d %r %p %c{1}: %m%n",
        false,
        "   %p %c{1}: %m%n",
        datePatterns
    );

    // Validate extracting multiple simple date conversion patterns
    datePatterns.clear();
    datePatterns.add(new DateConversionPattern('d', null, null, 0));
    datePatterns.add(new DateConversionPattern('d', null, null, 2));
    datePatterns.add(new DateConversionPattern('d', "HH:mm:ss' on 'yy/MM/dd", "GMT-5:30", 5));
    datePatterns.add(new DateConversionPattern('d', null, null, 41));
    datePatterns.add(new DateConversionPattern('r', null, null, 44));
    testExtractingMultipleDatePatterns(
        "%d%d %d{HH:mm:ss' on 'yy/MM/dd}{GMT-5:30}%d %r %p %c{1}: %m%n",
        true,
        "   %p %c{1}: %m%n",
        datePatterns
    );
  }

  /**
   * Tests extracting a date pattern from the given conversion pattern, both
   * when used with a Log4j PatternLayout or an EnhancedPatternLayout
   * @param pattern
   * @param expectedPatternWithoutDates
   * @param expectedDatePattern
   */
  private void testExtractingASingleSimpleDatePattern (
      String pattern,
      String expectedPatternWithoutDates,
      DateConversionPattern expectedDatePattern
  ) {
    testExtractingASingleDatePattern(pattern, false, expectedPatternWithoutDates,
                                     expectedDatePattern);
    testExtractingASingleDatePattern(pattern, true, expectedPatternWithoutDates,
                                     expectedDatePattern);
  }

  private void testExtractingASingleDatePattern (
      String pattern,
      boolean isEnhancedPattern,
      String expectedPatternWithoutDates,
      DateConversionPattern expectedDatePattern
  ) {
    Layout layout = null;
    try {
      if (isEnhancedPattern) {
        layout = new EnhancedPatternLayout(pattern);
      } else {
        layout = new PatternLayout(pattern);
      }
    } catch (Exception ex) {
      fail(ex);
    }

    DateConversionPatternExtractor parser = new DateConversionPatternExtractor(layout);
    List<DateConversionPattern> parsedDatePatterns = parser.getDateConversionPatterns();
    assertEquals(1, parsedDatePatterns.size());
    assertEquals(expectedDatePattern, parsedDatePatterns.get(0));
    assertEquals(expectedPatternWithoutDates, parser.getConversionPatternWithoutDates());
  }

  private void testExtractingMultipleDatePatterns (
      String pattern,
      boolean isEnhancedPattern,
      String expectedPattern,
      List<DateConversionPattern> expectedDatePatterns
  ) {
    Layout layout = null;
    try {
      if (isEnhancedPattern) {
        layout = new EnhancedPatternLayout(pattern);
      } else {
        layout = new PatternLayout(pattern);
      }
    } catch (Exception ex) {
      fail(ex);
    }

    DateConversionPatternExtractor parser = new DateConversionPatternExtractor(layout);
    List<DateConversionPattern> parsedDatePatterns = parser.getDateConversionPatterns();
    assertEquals(expectedDatePatterns.size(), parsedDatePatterns.size());

    for (int i = 0; i < expectedDatePatterns.size(); ++i) {
      assertEquals(expectedDatePatterns.get(i), parsedDatePatterns.get(i));
    }
    assertEquals(expectedPattern, parser.getConversionPatternWithoutDates());
  }
}
