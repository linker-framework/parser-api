package com.onkiup.linker.parser;

public interface TokenMapper<O extends Rule> {
  O mapped();
}
