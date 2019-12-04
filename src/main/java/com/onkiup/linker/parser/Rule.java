package com.onkiup.linker.parser;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;

import com.onkiup.linker.parser.token.CompoundToken;
import com.onkiup.linker.parser.token.PartialToken;
import com.onkiup.linker.parser.token.RuleToken;
import com.onkiup.linker.util.TypeUtils;

// in 0.4:
// - changed Metadata to hold PartialTokens instead of ParserLocations
// in 0.2.2:
// - added "C" type parameter
// - made it implement Consumer
/**
 * Main interface for all grammar definitions
 */
public interface Rule {

  class Metadata {
    private static Map<Rule, PartialToken> metadata = Collections.synchronizedMap(new WeakHashMap<>());
    private static Map<Rule, Map<Class<? extends Extension>, Extension<?>>> extensions = Collections.synchronizedMap(new WeakHashMap<>());
    private static Map<Class<? extends Rule>, Map<Class<?>, Conversion<?, ?>>> conversions = Collections.synchronizedMap(new HashMap<>());

    public static Optional<PartialToken<?>> metadata(Rule rule) {
      return Optional.ofNullable(metadata.get(rule));
    }

    public static void metadata(Rule rule, PartialToken token) {
      metadata.put(rule, token);
    }

    static void remove(Rule rule) {
      metadata.remove(rule);
    }
  }

  static <X extends Rule> X load(InputStream is) throws IOException, ClassNotFoundException {
    ObjectInputStream ois = new ObjectInputStream(is);
    return load(ois);
  }

  static <X extends Rule> X load(ObjectInputStream ois) throws IOException, ClassNotFoundException {
    Object result = ois.readObject();
    if (result instanceof Rule) {
      return (X)result;
    }
    String resultType = result == null ? "null" : result.getClass().getName();
    throw new IllegalArgumentException(resultType + " is not a Rule");
  }

  /**
   * @return parent token or null if this token is root token
   */
  default <R extends Rule> Optional<R> parent() {
    return Metadata.metadata(this)
      .map(meta -> {
        do {
          meta = (PartialToken) meta.parent().orElse(null);
        } while (!(meta instanceof RuleToken));
        return (CompoundToken) meta;
      })
      .flatMap(PartialToken::token);
  }

  /**
   * @return true if this token was successfully populated; false if parser is still working on some of the token's fields
   */
  default boolean populated() {
    return Metadata.metadata(this)
      .map(PartialToken::isPopulated)
      .orElse(false);
  }

  default void onPopulated() {

  }

  default Optional<PartialToken<?>> metadata() {
    return Metadata.metadata(this);
  }

  default ParserLocation location() {
    return metadata().map(PartialToken::location).orElse(null);
  }

  /**
   * Reevaluation callback.
   * Called by parser every time it updates the token
   */
  default void reevaluate() {
    
  }

  /**
   * Invalidation callback
   * called by arser every time it detaches the token from the tree
   */
  default void invalidate() {
  
  }

  default CharSequence source() {
    return metadata().map(PartialToken::source).orElse(null);
  }

  default void store(OutputStream os) throws IOException {
    ObjectOutputStream oos = new ObjectOutputStream(os);
    store(oos);
  }

  default void store(ObjectOutputStream oos) throws IOException {
    oos.writeObject(this);
  }

  /**
   * Creates or returns previously created extension object associated with this Rule
   *
   * @param type the type of extension object to create or return
   * @param <E> the type of extension object
   * @return extension object
   */
  default <T extends Rule, E extends Extension<T>> E as(Class<E> type) {
    synchronized (this) {
      if (!Metadata.extensions.containsKey(this)) {
        Metadata.extensions.put(this, Collections.synchronizedMap(new HashMap<>()));
      }

      Map<Class<? extends Extension>, Extension<?>> extensions = Metadata.extensions.get(this);
      if (!extensions.containsKey(type)) {
        try {
         Class<? extends E>[] candidates = (Class<? extends E>[])ParserContext.get().subClasses(type)
              .filter(TokenGrammar::isConcrete)
              .filter(Extension.class::isAssignableFrom)
              .filter(impl -> {
                Class<?> extended = TypeUtils.typeParameter(impl, Extension.class, 0);
                return extended.isAssignableFrom(getClass());
              })
              .toArray();

          if (candidates.length == 1) {
            if (extensions.containsKey(candidates[0])) {
              extensions.put(type, extensions.get(candidates[0]));
            } else {
              E extension = null;
              if (getClass().isEnum() && candidates[0].isEnum()) {
                // creating extension enum with same ordinal as base enum
                int ordinal = ((Enum)this).ordinal();
                extension = candidates[0].getEnumConstants()[ordinal];
              } else {
                extension = candidates[0].newInstance();
              }
              extensions.put(type, extension);
              extensions.put(candidates[0], extension);
              Extension.Metadata.base(extension, (T)this);
            }
          } else if (candidates.length > 1){
            throw new IllegalStateException("Too many extension candidates: \n" +
                Arrays.stream(candidates).map(Class::getName).collect(Collectors.joining("\n\t", "\t", "\n"))
            );
          } else {
            throw new ClassNotFoundException("Failed to find extension class for '" + type.getName() + "'");
          }
        } catch (Exception e) {
          throw new RuntimeException("Failed to extend '" + getClass().getName() + "' into '" + type.getName() + "'", e);
        }
      }
      return (E)extensions.get(type);
    }
  }

  default <E> E to(Class<E> target) {
    synchronized (this) {
      try {
        if(!Metadata.conversions.containsKey(getClass())) {
          Metadata.conversions.put(getClass(), Collections.synchronizedMap(new HashMap<>()));
        }

        Map<Class<?>, Conversion<?, ?>> myConversions = Metadata.conversions.get(getClass());

        if (!myConversions.containsKey(target)) {
          Class<Conversion<?, ?>>[] candidates = (Class<Conversion<?, ?>>[]) ParserContext.get().subClasses(Conversion.class)
            .filter(TokenGrammar::isConcrete)
            .filter(impl -> {
              Class<?> from = TypeUtils.typeParameter(impl, Conversion.class, 0);
              Class<?> to = TypeUtils.typeParameter(impl, Conversion.class, 1);
              return from.isAssignableFrom(getClass()) && target.isAssignableFrom(to);
            })
            .toArray();

          Class<? extends Conversion<?, ?>> converterClass = null;
          if (candidates.length == 1) {
            converterClass = candidates[0];
          } else if (candidates.length > 1) {
            throw new IllegalStateException("Too many converters from '" + getClass().getCanonicalName() + "' to class '" + target.getCanonicalName() + "':\n" + 
              Arrays.stream(candidates).map(Class::getName).collect(Collectors.joining("\n\t", "\t", "\n"))
            );
          } else {
            throw new ClassNotFoundException("Failed to find converter from '" + getClass().getCanonicalName() + "' to class '" + target.getCanonicalName() + "'");
          }

          try {
            myConversions.put(target, converterClass.newInstance());
          } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate converter '" + converterClass.getCanonicalName() + "'", e);
          }
        }

        Conversion converter = myConversions.get(target);
        return (E) converter.convert(this);
      } catch (Exception e) {
        throw new RuntimeException(String.format("Failed to convert '%s' to '%s'", getClass().getCanonicalName(), target.getCanonicalName()), e);
      }
    }
  }

  default int position() {
    return metadata().get().position();
  }

  default Optional<PartialToken<?>> offsetToken(int offset) {
    int position = position();
    int target = position + offset;

    if (target < 0) {
      return Optional.empty();
    }
    return metadata().flatMap(PartialToken::parent)
        .filter(parent -> target < parent.childCount())
        .flatMap(parent -> parent.child(target));
  }

  default Optional<PartialToken<?>> previousToken() {
    return offsetToken(-1);
  }

  default Optional<PartialToken<?>> nextToken() {
    return offsetToken(1);
  }

  default Logger logger() {
    return metadata().map(PartialToken::logger).orElse(null);
  }
}

