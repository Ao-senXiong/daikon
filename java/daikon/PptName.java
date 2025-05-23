package daikon;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import org.checkerframework.checker.interning.qual.Interned;
import org.checkerframework.checker.lock.qual.GuardSatisfied;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.plumelib.reflection.ReflectionPlume;

/**
 * PptName is an immutable ADT that represents naming data associated with a given program point,
 * such as the class or method.
 *
 * <p>Examples below are as if the full value of this PptName were
 * "DataStructures.StackAr.pop()Ljava/lang/Object;:::EXIT84"
 *
 * <p>PptName is deprecated, because declaration file format 2 should not need it. Uses of PptName
 * should be eliminated.
 */
// No "@Deprecated" annotation yet, but we should add it once support for
// file format 1 is removed from Daikon.
public class PptName implements Serializable {
  static final long serialVersionUID = 20020122L;

  // These are never changed but cannot be declared "final", because they
  // must be re-interned upon deserialization.
  /** Full program point name. */
  private @Interned String fullname;

  // fn_name and point together comprise fullname
  /** The part of fullname before ":::" */
  private @Interned String fn_name;

  /** Post-separator (separator is ":::") */
  private @Interned String point;

  // cls and method together comprise fn_name
  /** Fully-qualified class name. */
  private @Nullable @Interned String cls;

  /** Method signature, including types. */
  private final @Nullable @Interned String method;

  // Representation invariant:
  //
  // fullname must contain ":::".
  // fn_name is the part of fullname before ::: and point is the part after.
  // If fn_name does contain a '(' and a '.' that comes before it, then
  // cls is the portion before the dot and method is the portion after.
  // If fn_name contains '(' but no dot, cls is null and method
  // is the same as fn_name.
  // If fn_name does not contain '(', then class is the same as fn_name and
  // method is null.

  // ==================== CONSTRUCTORS ====================

  /**
   * @param name non-null ppt name as given in the decls file
   */
  public PptName(String name) {
    // If the name is well-formed, like "mvspc.setupGUI()V:::EXIT75",
    // then this constructor will extract the class and method names.
    // If not (eg for lisp code), it's okay because only the GUI uses
    // this class/method information.

    fullname = name.intern();
    int separatorPosition = name.indexOf(FileIO.ppt_tag_separator);
    if (separatorPosition == -1) {
      throw new Daikon.UserError("no ppt_tag_separator in '" + name + "'");
      // // probably a lisp program, which was instrumented differently
      // cls = method = point = fn_name = null;
      // return;
    }
    fn_name = name.substring(0, separatorPosition).intern();
    point = name.substring(separatorPosition + FileIO.ppt_tag_separator.length()).intern();

    int lparen = fn_name.indexOf('(');
    if (lparen == -1) {
      cls = fn_name;
      method = null;
      return;
    }
    int dot = fn_name.lastIndexOf('.', lparen);
    if (dot == -1) {
      method = fn_name;
      cls = null;
      return;
    }
    // now 0 <= dot < lparen
    cls = fn_name.substring(0, dot).intern();
    method = fn_name.substring(dot + 1).intern();
  }

  /**
   * Creates a new PptName. {@code className} or {@code methodName} (or both) must be non-null.
   *
   * @param className fully-qualified class name
   * @param methodName method signature, including types
   * @param pointName post-separator (separator is ":::")
   */
  public PptName(@Nullable String className, @Nullable String methodName, String pointName) {
    if ((className == null) && (methodName == null)) {
      throw new UnsupportedOperationException("One of class or method must be non-null");
    }
    // First set class name
    if (className != null) {
      cls = className.intern();
      fn_name = cls;
    }
    // Then add method name
    if (methodName == null) {
      method = null;
      if (fn_name == null) {
        throw new RuntimeException("fn_name should not be null; probably bad arguments");
      }
    } else {
      method = methodName.intern();
      if (cls != null) {
        fn_name = (cls + "." + method).intern();
      } else {
        fn_name = method;
      }
    }
    assert fn_name != null;
    // Finally, add point
    point = pointName.intern();
    fullname = (fn_name + FileIO.ppt_tag_separator + point).intern();
  }

  // ==================== OBSERVERS ====================

  /**
   * Returns getName() [convenience accessor].
   *
   * @return getName() [convenience accessor]
   * @see #getName()
   */
  @Pure
  public String name() {
    return getName();
  }

  /**
   * Returns the complete program point name, e.g.,
   * "DataStructures.StackAr.pop()Ljava/lang/Object;:::EXIT84".
   *
   * @return the complete program point name
   */
  @Pure
  public String getName() {
    return fullname;
  }

  /**
   * Returns the fully-qualified class name, which uniquely identifies a given class. May be null.
   * e.g. "DataStructures.StackAr".
   *
   * @return the fully-qualified class name
   */
  public @Nullable String getFullClassName() {
    return cls;
  }

  /**
   * Returns the short name of the class, not including any additional context, such as the package
   * it is in. May be null. e.g. "StackAr".
   *
   * @return the short name of the class, or null
   */
  public @Nullable String getShortClassName() {
    if (cls == null) {
      return null;
    }
    int pt = cls.lastIndexOf('.');
    if (pt == -1) {
      return cls;
    } else {
      return cls.substring(pt + 1);
    }
  }

  /**
   * Returns a guess at the package name. May be null.
   *
   * @return a guess at the package name. May be null
   */
  public @Nullable String getPackageName() {
    if (cls == null) {
      return null;
    }
    int pt = cls.lastIndexOf('.');
    if (pt == -1) {
      return null;
    } else {
      return cls.substring(0, pt);
    }
  }

  /**
   * Returns the full name which can uniquely identify a method within a class. The name includes
   * symbols for the argument types and return type. May be null. e.g. "pop()Ljava/lang/Object;".
   *
   * @return the full name which can uniquely identify a method within a class
   */
  public @Nullable String getSignature() {
    return method;
  }

  /**
   * Returns the name (identifier) of the method, not taking into account any arguments, return
   * values, etc. May be null. e.g. "pop".
   *
   * @return the name (identifier) of the method, or null
   */
  public @Nullable String getMethodName() {
    if (method == null) {
      return null;
    }
    int lparen = method.indexOf('(');
    assert lparen >= 0;
    return method.substring(0, lparen);
  }

  /**
   * Returns the fully-qualified class and method name (and signature). Does not include any point
   * information (such as ENTER or EXIT). May be null. e.g.
   * "DataStructures.StackAr.pop()Ljava/lang/Object;"
   *
   * @return the fully-qualified class and method name (and signature)
   */
  public @Nullable @Interned String getNameWithoutPoint() {
    return fn_name;
    // if (cls == null && method == null) {
    //   return null;
    // }
    // if (cls == null) { return method; }
    // if (method == null) { return cls; }
    // return (cls + "." + method).intern();
  }

  /**
   * Returns something interesting and descriptive about this point, along the lines of "ENTER" or
   * "EXIT" or some such. The semantics of this method are not yet decided, so don't try to do
   * anything useful with this result. May be null. e.g. "EXIT84".
   *
   * @return something interesting and descriptive about this
   */
  public @Nullable String getPoint() {
    return point;
  }

  /**
   * Returns a numerical subscript of the given point, or Integer.MIN_VALUE if none exists. e.g.
   * "84".
   *
   * @return a numerical subscript of the given point, or Integer.MIN_VALUE if none exists
   * @see #exitLine()
   */
  public int getPointSubscript() {
    int result = Integer.MIN_VALUE;
    if (point != null) {
      // returns the largest substring [i..] which parses to an integer
      for (int i = 0; i < point.length(); i++) {
        char c = point.charAt(i);
        if (('0' <= c) && (c <= '9')) {
          try {
            result = Integer.parseInt(point.substring(i));
            break;
          } catch (NumberFormatException e) {
            // continue;
          }
        }
      }
    }
    return result;
  }

  /**
   * Returns true iff this name refers to a synthetic object instance program point.
   *
   * @return true iff this name refers to a synthetic object instance program point
   */
  @Pure
  public boolean isObjectInstanceSynthetic() {
    return FileIO.object_suffix.equals(point);
  }

  /**
   * Returns true iff this name refers to a synthetic class instance program point.
   *
   * @return true iff this name refers to a synthetic class instance program point
   */
  @Pure
  public boolean isClassStaticSynthetic() {
    return FileIO.class_static_suffix.equals(point);
  }

  /**
   * Returns true iff this name refers to program globals.
   *
   * @return true iff this name refers to program globals
   */
  @Pure
  public boolean isGlobalPoint() {
    return FileIO.global_suffix.equals(point);
  }

  /**
   * Returns true iff this name refers to a procedure exit point.
   *
   * @return true iff this name refers to a procedure exit point
   */
  @EnsuresNonNullIf(result = true, expression = "point")
  @Pure
  public boolean isExitPoint() {
    return (point != null) && point.startsWith(FileIO.exit_suffix);
  }

  /**
   * Returns true iff this name refers to an abrupt completion point.
   *
   * @return true iff this name refers to an abrupt completion point
   */
  @EnsuresNonNullIf(result = true, expression = "point")
  @Pure
  public boolean isThrowsPoint() {
    return (point != null) && point.startsWith(FileIO.throws_suffix);
  }

  /**
   * Returns true iff this name refers to a combined (synthetic) procedure exit point.
   *
   * @return true iff this name refers to a combined (synthetic) procedure exit point
   */
  @EnsuresNonNullIf(result = true, expression = "point")
  @Pure
  public boolean isCombinedExitPoint() {
    return (point != null) && point.equals(FileIO.exit_suffix);
  }

  /**
   * Returns true iff this name refers to an actual (not combined) procedure exit point (eg,
   * EXIT22).
   *
   * @return true iff this name refers to an actual (not combined) procedure exit point
   */
  @EnsuresNonNullIf(result = true, expression = "point")
  @Pure
  public boolean isNumberedExitPoint() {
    return (point != null) && (isExitPoint() && !isCombinedExitPoint());
  }

  /**
   * Returns true iff this name refers to a procedure exit point.
   *
   * @return true iff this name refers to a procedure exit point
   */
  @EnsuresNonNullIf(result = true, expression = "point")
  @Pure
  public boolean isEnterPoint() {
    return (point != null) && point.startsWith(FileIO.enter_suffix);
  }

  /**
   * Returns a string containing the line number, if this is an exit point; otherwise, returns an
   * empty string.
   *
   * @return a string containing the line number, if this is an exit point; otherwise, return an
   *     empty string
   * @see #getPointSubscript()
   */
  public String exitLine() {
    if (!isExitPoint()) {
      return "";
    }
    int non_digit;
    for (non_digit = FileIO.exit_suffix.length(); non_digit < point.length(); non_digit++) {
      if (!Character.isDigit(point.charAt(non_digit))) {
        break;
      }
    }
    return point.substring(FileIO.exit_suffix.length(), non_digit);
  }

  /**
   * Returns true iff this program point is a constructor entry or exit. There are two ways in which
   * this works. With the older declaration format, the method name starts with {@code <init>}. The
   * newer declaration format does not have {@code <init>} but their method name includes the class
   * name. For compatibility both mechanisms are checked.
   *
   * @return true iff this program point is a constructor entry or exit
   */
  @Pure
  public boolean isConstructor() {

    if (method != null) {

      if (method.startsWith("<init>")) {
        return true;
      }

      if (cls == null) {
        return false;
      }

      @SuppressWarnings("signature") // cls is allowed to be arbitrary, especially for non-Java code
      String class_name = ReflectionPlume.fullyQualifiedNameToSimpleName(cls);
      assert method != null; // for nullness checker
      int arg_start = method.indexOf('(');
      String method_name = method;
      if (arg_start != -1) {
        method_name = method.substring(0, arg_start);
      }

      // System.out.println ("fullname = " + fullname);
      // System.out.println ("fn_name = " + fn_name);
      // System.out.println ("method = " + method);
      // System.out.println ("cls = " + cls);
      // System.out.println ("class_name = " + class_name);
      // System.out.println ("method_name = " + method_name);

      if (class_name.equals(method_name)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Debugging output.
   *
   * @return a string representation of this
   */
  public String repr() {
    return "PptName: fullname="
        + fullname
        + "; fn_name="
        + fn_name
        + "; point="
        + point
        + "; cls="
        + cls
        + "; method="
        + method;
  }

  // ==================== PRODUCERS ====================

  /**
   * Requires: this.isExitPoint()
   *
   * @return a name for the corresponding enter point
   */
  public PptName makeEnter() {
    // This associates throw points with the main entry point.
    // We may wish to have a different exceptional than non-exceptional
    // entry point; in particular, if there was an exception, then perhaps
    // the precondition or object invariant was not met.
    assert isExitPoint() : fullname;

    assert isExitPoint() || isThrowsPoint();
    return new PptName(cls, method, FileIO.enter_suffix);
  }

  /**
   * Requires: this.isExitPoint() || this.isEnterPoint()
   *
   * @return a name for the combined exit point
   */
  public PptName makeExit() {
    assert isExitPoint() || isEnterPoint() : fullname;
    return new PptName(cls, method, FileIO.exit_suffix);
  }

  /**
   * Requires: this.isExitPoint() || this.isEnterPoint()
   *
   * @return a name for the corresponding object invariant
   */
  public PptName makeObject() {
    assert isExitPoint() || isEnterPoint() : fullname;
    return new PptName(cls, null, FileIO.object_suffix);
  }

  /**
   * Requires: this.isExitPoint() || this.isEnterPoint() || this.isObjectInstanceSynthetic()
   *
   * @return a name for the corresponding class-static invariant
   */
  public PptName makeClassStatic() {
    assert isExitPoint() || isEnterPoint() || isObjectInstanceSynthetic() : fullname;
    return new PptName(cls, null, FileIO.class_static_suffix);
  }

  // ==================== OBJECT METHODS ====================

  /* @return interned string such that this.equals(new PptName(this.toString())) */
  @SideEffectFree
  @Override
  public String toString(@GuardSatisfied PptName this) {
    return fullname;
  }

  @EnsuresNonNullIf(result = true, expression = "#1")
  @Pure
  @Override
  public boolean equals(@GuardSatisfied PptName this, @GuardSatisfied @Nullable Object o) {
    return (o instanceof PptName) && equalsPptName((PptName) o);
  }

  @EnsuresNonNullIf(result = true, expression = "#1")
  @Pure
  public boolean equalsPptName(@GuardSatisfied PptName this, @GuardSatisfied PptName o) {
    return (o != null) && (o.fullname == fullname);
  }

  @Pure
  @Override
  public int hashCode(@GuardSatisfied PptName this) {
    return fullname.hashCode();
  }

  // Interning is lost when an object is serialized and deserialized.
  // Manually re-intern any interned fields upon deserialization.
  private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    try {
      in.defaultReadObject();
      if (fullname != null) {
        fullname = fullname.intern();
      }
      if (fn_name != null) {
        fn_name = fn_name.intern();
      }
      if (cls != null) {
        cls = cls.intern();
      }
      if (method != null) {
        // method = method.intern();
        ReflectionPlume.setFinalField(this, "method", method.intern());
      }
      if (point != null) {
        point = point.intern();
      }
    } catch (NoSuchFieldException e) {
      throw new Error(e);
    }
  }
}
