package com.onkiup.linker.parser.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that allows to override grammar tokens from one package with tokens from another package
 * Should be used on grammar's root token
 * @implNote override tokens MUST extend overridden tokens
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GrammarPackageOverride {
  String from();
  String to();
}
