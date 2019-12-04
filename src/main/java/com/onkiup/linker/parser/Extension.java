package com.onkiup.linker.parser;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import org.slf4j.Logger;

import com.onkiup.linker.parser.token.PartialToken;

/**
 * An interface used to mark rule extensions
 */
public interface Extension<X extends Rule> {

  class Metadata {
    private static final Map<Extension, Rule> bases = Collections.synchronizedMap(new WeakHashMap<>());

    private static <R extends Rule> R base(Extension<R> of) {
      return (R)bases.get(of);
    }

    protected static <R extends Rule> void base(Extension<R> extension, R base) {
      bases.put(extension, base);
    }
  }

  default X base() {
    return Metadata.base(this);
  }

  default Optional<PartialToken<?>> metadata() {
    return base().metadata();
  }

  default <E extends Extension<X>> E as(Class<E> type) {
    return base().as(type);
  }

  default <E> E to(Class<E> type) {
    return base().to(type);
  }

  default Object previousToken() {
    return base().previousToken();
  }

  default Object nextToken() {
    return base().nextToken();
  }

  default Logger logger() {
    return base().logger();
  }

  default ParserLocation location() {
    return base().location();
  }
}
