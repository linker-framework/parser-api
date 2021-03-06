package com.onkiup.linker.parser.token;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.onkiup.linker.parser.ParserLocation;
import com.onkiup.linker.parser.annotation.AdjustPriority;
import com.onkiup.linker.parser.annotation.MetaToken;
import com.onkiup.linker.parser.annotation.OptionalToken;
import com.onkiup.linker.parser.annotation.SkipIfFollowedBy;
import com.onkiup.linker.util.LoggerLayout;
import com.onkiup.linker.util.TextUtils;

import org.slf4j.Logger;

/**
 * Generic interface for structures used to populate tokens
 * @param <X>
 */
public interface PartialToken<X> extends Serializable {

  /**
   * @return Java representation of populated token
   */
  Optional<X> token();

  /**
   * @return the type of resulting java token
   */
  Class<X> tokenType();

  /**
   * Called by parser to detect if this token is populated
   * The result of this method should always be calculated
   */
  boolean isPopulated();

  /**
   * Resets population flag for this token
   * (Usually invoked on populated tokens with untested variants after one of the following tokens fails)
   */
  void dropPopulated();

  /**
   * @return true if this token failed to match parser input
   */
  boolean isFailed();

  /**
   * @return true if this token was marked as optional
   */
  boolean isOptional();

  /**
   * @return parent token or empty if this token is the root AST token
   */
  Optional<CompoundToken<?>> parent();

  /**
   * @return the field for which this PartialToken was created
   */
  Optional<Field> targetField();

  /**
   * @return Token's location in parser input
   */
  ParserLocation location();

  /**
   * @return the next position in parser input immediately after the last character that matched this token
   */
  ParserLocation end();

  /**
   * Marks this token as optional
   */
  void markOptional();

  /**
   * Callback method invoked upon token population
   * @param end the next position in parser input immediately after the last character that matched this token
   */
  void onPopulated(ParserLocation end);

  /**
   * @return String representation of the token used for logging
   */
  String tag();

  /**
   * A callback that is invoked when token matching hits end of parser input
   * An invocation should result in either token failure or population
   */
  void atEnd();

  /**
   * Using reversed breadth-first search algorithm, traces back from this token to the next token with untested alternatives
   */
  default void traceback() {
    onFail();
  }

  /**
   * @return the list of metatokens for this token
   */
  List<?> metaTokens();

  /**
   * Stores giben object as a metatoken to this token
   * @param metatoken
   */
  void addMetaToken(Object metatoken);

  /**
   * @return true if this token was marked as {@link MetaToken}
   */
  default boolean isMetaToken() {
    return tokenType().isAnnotationPresent(MetaToken.class);
  }

  /** 
   * @return all characters consumed by the token and its children
   */
  default CharSequence source() {
    PartialToken<?> root = root();
    return ConsumingToken.ConsumptionState.rootBuffer(root)
        .map(buffer -> buffer.subSequence(position(), end().position()))
        .orElse("?!");
  }

  /**
   * @return a logger associated with this token
   */
  Logger logger();

  /**
   * Logs a DEBUG-level message from this token
   * @see String#format(String, Object...)
   * @param message template for the message
   * @param arguments template arguments
   */
  default void log(CharSequence message, Object... arguments) {
    logger().debug(message.toString(), arguments);
  }
  /**
   * Logs an ERROR-level message from this token
   * @param message the message to log
   * @param error cause exception
   */
  default void error(CharSequence message, Throwable error) {
    logger().error(message.toString(), error);
  }


  /**
   * Called upon token failures
   */
  default void onFail() {
    log("!!! FAILED !!!");
    invalidate();
  }

  /**
   * Called on failed tokens. Looks ahead into parser buffer to determine whether this token and its compound parents should be considered optional
   */
  default void lookahead(CharSequence source, int from) {
    log("performing lookahead at position {}", from);
    targetField()
      .flatMap(PartialToken::getOptionalCondition)
      .ifPresent(condition -> {
        int start = TextUtils.firstNonIgnoredCharacter(ignoredCharacters(), source, from);
        CharSequence buffer = source.subSequence(start, start + condition.length());
        log("Loookahead '{}' on '{}'", LoggerLayout.sanitize(condition), LoggerLayout.sanitize(buffer));
        if (!isOptional() && Objects.equals(condition, buffer)) {
          log("Optional condition match: '{}' == '{}'", LoggerLayout.sanitize(condition), LoggerLayout.sanitize(buffer));
          markOptional();
        }
      });

    parent()
        .filter(CompoundToken::onlyOneUnfilledChildLeft)
        .filter(p -> p != this)
        .ifPresent(p -> {
          log("Delegating lookahead to parent {}", p.tag());
          p.lookahead(source, from);
        });
  }

  /**
   * Reads optionality condition for the field
   * @param field field to read optionality condition for
   * @return optionality condition or empty
   */
  static Optional<CharSequence> getOptionalCondition(Field field) {
    if (field == null) {
      return Optional.empty();
    }
    CharSequence result = null;
    if (!field.isAnnotationPresent(OptionalToken.class)) {
      result = field.getAnnotation(OptionalToken.class).whenFollowedBy();
    } else if (field.isAnnotationPresent(SkipIfFollowedBy.class)) {
      result = field.getAnnotation(SkipIfFollowedBy.class).value();
    }

    return Optional.ofNullable(result == null || result.length() == 0 ? null : result);
  }

  /**
   * Recursively passes this token and its parent tokens to provided predicate until the AST root and returnes the first token that matched the predicate
   * @param comparator the predicate to use on path tokens
   * @return first matched token or empty
   */
  default Optional<PartialToken<?>> findInPath(Predicate<PartialToken> comparator) {
    if (comparator.test(this)) {
      return Optional.of(this);
    }

    return parent()
      .flatMap(parent -> parent.findInPath(comparator));
  }

  /**
   * @return Token offset relative to the start of parser input
   */
  default int position() {
    ParserLocation location = location();
    if (location == null) {
      return 0;
    }
    return location.position();
  }

  /**
   * @return base priority for this token to be used by {@link VariantToken}
   */
  default int basePriority() {
    int result = 0;
    Class<X> tokenType = tokenType();
    if (tokenType.isAnnotationPresent(AdjustPriority.class)) {
      AdjustPriority adjustment = tokenType.getAnnotation(AdjustPriority.class);
      result += adjustment.value();
    }
    return result;
  }

  /**
   * @return true if this token's priority should be added to parent token's priority
   */
  default boolean propagatePriority() {
    Class<X> tokenType = tokenType();
    if (tokenType.isAnnotationPresent(AdjustPriority.class)) {
      return tokenType.getAnnotation(AdjustPriority.class).propagate();
    }

    return false;
  }

  /**
   * A callback invoked on rotatable tokens after token population so that algebraic and similar tokens reorder themselves according to their priorities
   */
  default void sortPriorities() {

  }

  /**
   * Forcefully sets java representation for this token
   * @param token new java representation for this token
   */
  default void token(X token) {
    throw new RuntimeException("Unsupported");
  }

  /**
   * A callback invoked every time this token is detached from its AST
   * This primarily intended for asynchronous token evaluation algoritms
   */
  default void invalidate() {
  }

  /**
   * Using BFS algorithm, passes this token and its sub-tree tokens to the visitor
   * @param visitor
   */
  default void visit(Consumer<PartialToken<?>> visitor) {
    visitor.accept(this);
  }

  /**
   * @return String containing all characters to ignore for this token
   */
  default String ignoredCharacters() {
    return parent().map(CompoundToken::ignoredCharacters).orElse("");
  }

  /**
   * @return true if this token has untested alternatives
   */
  default boolean alternativesLeft() {
    return false;
  }

  /**
   * @return root token of the AST to which this token belongs to
   */
  default PartialToken<?> root() {
    PartialToken<?> current = this;
    while(true) {
      PartialToken<?> parent = current.parent().orElse(null);
      if (parent == null) {
        return current;
      }
      current = parent;
    }
  }

  /**
   * @param length the number of characters to return
   * @return last X characters matched this token
   */
  default CharSequence tail(int length) {
    return LoggerLayout.ralign(LoggerLayout.sanitize(source().toString()), length);
  }

  /**
   * @param length the number of characters to return
   * @return first X characters matched this token
   */
  default CharSequence head(int length) {
    return LoggerLayout.head(LoggerLayout.sanitize(source()), 50);
  }

  /**
   * @return a list of tokens including this token and its parents up to the root token of the AST
   */
  default LinkedList<PartialToken<?>> path() {
    LinkedList path = parent()
      .map(PartialToken::path)
      .orElseGet(LinkedList::new);
    path.add(this);
    return path;
  }

  /**
   * @return String representation of the AST with this token as the AST root
   */
  default CharSequence dumpTree() {
    return dumpTree(PartialToken::tag);
  }

  /**
   * @param formatter formatter function to use on tree nodes
   * @return String representation of the AST with this token as AST root
   */
  default CharSequence dumpTree(Function<PartialToken<?>, CharSequence> formatter) {
    return dumpTree(0, "", "", formatter);
  }

  /**
   * Dumps AST represented by this token into a String
   * @param offset tabulation offset in the tree
   * @param prefix prefix to use when rendering this token
   * @param childPrefix prefix to use when rendering this token's children
   * @param formatter formatter function
   * @return String representation of the AST
   */
  default CharSequence dumpTree(int offset, CharSequence prefix, CharSequence childPrefix, Function<PartialToken<?>, CharSequence> formatter) {
    return String.format("%s%s\n", prefix, formatter.apply(this));
  }

  /**
   * Serializes this token into an output stream
   * @param os OuputStream to write this token into
   * @throws IOException
   */
  default void store(OutputStream os) throws IOException {
    ObjectOutputStream oos = new ObjectOutputStream(os);
    oos.writeObject(this);
  }

  default Optional<PartialToken<?>> offsetToken(int offset) {
    int position = position();
    int target = position + offset;

    if (target < 0) {
      return Optional.empty();
    }
    return parent().filter(parent -> target < parent.childCount())
        .flatMap(parent -> parent.child(target));
  }

  /**
   * returns the next token in the AST
   */
  default Optional<PartialToken<?>> nextToken() {
    return offsetToken(1);
  }

  /**
   * returns the previous token in the AST
   */
  default Optional<PartialToken<?>> previousToken() {
    return offsetToken(-1);
  }
}

