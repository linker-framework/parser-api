package com.onkiup.linker.parser;

import java.io.Reader;

public interface LinkerParser<X extends Rule> {

  static <XX extends Rule> LinkerParser<XX> forType(Class<XX> type) {
 //    return new LinkerParserImpl(type);
    return null;
  }

  LinkerParser<X> target(Class<X> type);

  /**
   *
   * @param source
   * @return
   */
  X parse(String sourceName, Reader source);
}
