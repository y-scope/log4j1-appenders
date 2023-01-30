package com.yscope.logging.log4j1;

import java.util.Objects;

/**
 * Simple class to hold the bounds of a substring in a string
 */
public class SubstringBounds {
  public int beginOffset;
  public int endOffset;

  public SubstringBounds (int beginOffset, int endOffset) {
    this.beginOffset = beginOffset;
    this.endOffset = endOffset;
  }

  @Override
  public boolean equals (Object rhs) {
    if (rhs == this) {
      return true;
    }

    if (false == (rhs instanceof SubstringBounds)) {
      return false;
    }
    SubstringBounds rhsBounds = (SubstringBounds)rhs;

    return rhsBounds.beginOffset == beginOffset && rhsBounds.endOffset == endOffset;
  }

  @Override
  public int hashCode () {
    return Objects.hash(beginOffset, endOffset);
  }
}
