package com.yscope.logging.log4j1;

import java.util.ArrayList;
import java.util.List;
import org.apache.log4j.PatternLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class TestDateConversionPatternExtractor {
  @Test
  public void testExtraction () {
    // Validate extracting %d at different positions in the conversion pattern
    testExtractingSingleDatePattern("%d %p %c{1} %% %m%n", new SubstringBounds(0, 2));
    testExtractingSingleDatePattern("%p %d %c{1} %% %m%n", new SubstringBounds(3, 5));
    testExtractingSingleDatePattern("%p %c{1} %% %m%n %d", new SubstringBounds(17, 19));

    // Validate extracting %d{...} at different positions in the conversion
    // pattern
    testExtractingSingleDatePattern("%d{HH:mm:ss' on 'yy/MM/dd} %p %c{1} %% %m%n",
                                    new SubstringBounds(0, 26));
    testExtractingSingleDatePattern("%p %d{HH:mm:ss' on 'yy/MM/dd} %c{1} %% %m%n",
                                    new SubstringBounds(3, 29));
    testExtractingSingleDatePattern("%p %c{1} %% %m%n %d{HH:mm:ss' on 'yy/MM/dd}",
                                    new SubstringBounds(17, 43));

    // Validate extracting %r at different positions in the conversion pattern
    testExtractingSingleDatePattern("%r %p %c{1} %% %m%n", new SubstringBounds(0, 2));
    testExtractingSingleDatePattern("%p %r %c{1} %% %m%n", new SubstringBounds(3, 5));
    testExtractingSingleDatePattern("%p %c{1} %% %m%n %r", new SubstringBounds(17, 19));

    // Validate extracting multiple date conversion patterns
    ArrayList<SubstringBounds> datePatterns = new ArrayList<>();
    datePatterns.add(new SubstringBounds(0, 2));
    datePatterns.add(new SubstringBounds(3, 29));
    datePatterns.add(new SubstringBounds(30, 32));
    testExtractingMultipleDatePatterns("%d %d{HH:mm:ss' on 'yy/MM/dd} %r %p %c{1}: %m%n",
                                       datePatterns);
  }

  private void testExtractingSingleDatePattern (String pattern, SubstringBounds dateBounds) {
    // Sanity-check that the pattern is valid
    try {
      new PatternLayout(pattern);
    } catch (Exception ex) {
      fail(ex);
    }

    DateConversionPatternExtractor parser = new DateConversionPatternExtractor(pattern);
    List<SubstringBounds> dateConversionPatterns = parser.getDateConversionPatterns();
    assertEquals(1, dateConversionPatterns.size());
    SubstringBounds bounds = dateConversionPatterns.get(0);
    assertEquals(dateBounds, bounds);
    String patternWithoutDate =
        pattern.substring(0, dateBounds.beginOffset) + pattern.substring(dateBounds.endOffset);
    assertEquals(patternWithoutDate, parser.getConversionPatternWithoutDates());
  }

  private void testExtractingMultipleDatePatterns (String pattern,
                                                   List<SubstringBounds> expectedDatePatterns)
  {
    // Sanity-check that the pattern is valid
    try {
      new PatternLayout(pattern);
    } catch (Exception ex) {
      fail(ex);
    }

    DateConversionPatternExtractor parser = new DateConversionPatternExtractor(pattern);
    List<SubstringBounds> foundDatePatterns = parser.getDateConversionPatterns();
    assertEquals(expectedDatePatterns.size(), foundDatePatterns.size());

    StringBuilder patternWithoutDateBuilder = new StringBuilder();
    int lastDatePatternEndOffset = 0;
    for (int i = 0; i < expectedDatePatterns.size(); ++i) {
      SubstringBounds foundDatePattern = foundDatePatterns.get(i);
      assertEquals(expectedDatePatterns.get(i), foundDatePattern);
      patternWithoutDateBuilder.append(pattern, lastDatePatternEndOffset,
                                       foundDatePattern.beginOffset);
      lastDatePatternEndOffset = foundDatePattern.endOffset;
    }
    patternWithoutDateBuilder.append(pattern, lastDatePatternEndOffset, pattern.length());
    assertEquals(patternWithoutDateBuilder.toString(), parser.getConversionPatternWithoutDates());
  }
}
