package com.onkiup.linker.parser;

public interface Conversion<I extends Rule, O>  {
  O convert(I source);
}

