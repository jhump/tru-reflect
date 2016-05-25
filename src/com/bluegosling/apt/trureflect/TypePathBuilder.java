package com.bluegosling.apt.trureflect;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.objectweb.asm.TypePath;

/**
 * A mutable builder of {@link TypePath} objects. This allows code to traverse a type, pushing
 * elements as it recurses into a type and popping them off as it returns. As types of interest are
 * encountered during the traversal, a {@link TypePath} can easily be built representing the current
 * traversal path.
 * 
 * @author Joshua Humphries (jhumphries131@gmail.com)
 */
public class TypePathBuilder {

   /**
    * A single element in a type path.
    * 
    * @author Joshua Humphries (jhumphries131@gmail.com)
    */
   public static class PathElement {
      private static final PathElement ARRAY_ELEMENT = new PathElement(TypePath.ARRAY_ELEMENT);
      private static final PathElement NESTED_ELEMENT = new PathElement(TypePath.INNER_TYPE);
      private static final PathElement WILDCARD_ELEMENT = new PathElement(TypePath.WILDCARD_BOUND);
      
      private final byte type;
      private final byte index;
      
      private PathElement(int type) {
         this(type, 0);
      }
      
      private PathElement(int type, int index) {
         this.type = (byte) type;
         this.index = (byte) index;
      }
      
      public byte getType() {
         return type;
      }
      
      public byte getIndex() {
         return index;
      }
      
      @Override public boolean equals(Object o) {
         if (o == this) return true;
         if (!(o instanceof PathElement)) return false;
         PathElement other = (PathElement) o;
         return type == other.type && index == other.index;
      }
      
      @Override public int hashCode() {
         return type ^ index;
      }
      
      @Override public String toString() {
         switch (type) {
            case TypePath.ARRAY_ELEMENT:
               return "[";
            case TypePath.INNER_TYPE:
               return ".";
            case TypePath.WILDCARD_BOUND:
               return "*";
            case TypePath.TYPE_ARGUMENT:
               return "" + (index & 0xff) + ";";
            default:
               throw new AssertionError("Unknown PathElement type: " + type);
         }
      }
      
      /**
       * Returns a path element that indicates traversal into an array type.
       * 
       * @return a path element that indicates traversal into an array type
       */
      public static PathElement arrayPathElement() {
         return ARRAY_ELEMENT;
      }

      /**
       * Returns a path element that indicates traversal into a nested type.
       * 
       * @return a path element that indicates traversal into a nested type
       */
      public static PathElement nestedPathElement() {
         return NESTED_ELEMENT;
      }

      /**
       * Returns a path element that indicates traversal into a wildcard type's bound.
       * 
       * @return a path element that indicates traversal into a wildcard type's bound
       */
      public static PathElement wildcardPathElement() {
         return WILDCARD_ELEMENT;
      }

      /**
       * Returns a path element that indicates traversal into the given type argument. An index of
       * zero indicates the first type argument of a parameterized type. A value of one is the
       * second type argument, and so on.
       * 
       * @param argIndex the index of the type argument into which the path traverses
       * @return a path element that indicates traversal into the given type argument
       */
      public static PathElement typeArgPathElement(int argIndex) {
         if (argIndex < 0 || argIndex > 255) {
            throw new IndexOutOfBoundsException("0 <= " + argIndex + " <= 255");
         }
         return new PathElement(TypePath.TYPE_ARGUMENT, argIndex);
      }
   }
   
   private final List<PathElement> pathElements = new ArrayList<>();
   
   /**
    * Pushes a new element onto the end of the path.
    * 
    * @param e the path element
    * @return {@code this}
    */
   public TypePathBuilder push(PathElement e) {
      pathElements.add(e);
      return this;
   }
   
   /**
    * Pops the last element from the end of the path.
    * 
    * @return {@code this}
    * @throws NoSuchElementException if the current path is empty
    */
   public TypePathBuilder pop() {
      if (pathElements.isEmpty()) {
         throw new NoSuchElementException();
      }
      pathElements.remove(pathElements.size() - 1);
      return this;
   }
   
   /**
    * Builds a {@link TypePath} from the current state of path elements.
    * 
    * @return a {@link TypePath} that represents the current state of path elements
    */
   public TypePath build() {
      StringBuilder sb = new StringBuilder();
      for (PathElement e : pathElements) {
         sb.append(e.toString());
      }
      return TypePath.fromString(sb.toString());
   }
}
