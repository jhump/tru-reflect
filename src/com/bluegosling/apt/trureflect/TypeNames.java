package com.bluegosling.apt.trureflect;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.TypeKindVisitor8;
import javax.lang.model.util.Types;

/**
 * Computes type names and descriptors for elements and mirrors.
 * 
 * @see Environment
 * 
 * @author Joshua Humphries (jhumphries131@gmail.com)
 */
public class TypeNames {
   private final Elements elementUtils;
   private final Types typeUtils;
   
   TypeNames(Elements elementUtils, Types typeUtils) {
      this.elementUtils = elementUtils;
      this.typeUtils = typeUtils;
   }

   /**
    * Computes the internal form of the binary name for the given type element. The internal form
    * used in class files is described in JLS section 4.2.1, <em><a
    * href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.2.1">Binary Class
    * and Interface Names</a></em>.
    * 
    * @param element a type element
    * @return the internal form of binary name for the given element
    */
   public String getInternalName(TypeElement element) {
      return elementUtils.getBinaryName(element).toString().replace('.', '/');
   }

   /**
    * Computes the internal form of the binary name for the given declared type mirror. If the
    * given mirror is a parameterized type, the parameters are discarded as internal names only
    * identify the type and do not represent a parameterization of that type.
    * 
    * @param type a declared type mirror
    * @return the internal form binary name for the given type
    * @see #getInternalName(TypeElement)
    */
   public String getInternalName(DeclaredType type) {
      return getInternalName((TypeElement) type.asElement());
   }

   /**
    * Computes the internal form of the binary name for the given type mirror. If the given mirror
    * is a parameterized type, the parameters are discarded as internal names only identify the type
    * and do not represent a parameterization of that type.
    * 
    * @param type a type mirror
    * @return the internal form binary name for the given type
    * @throws IllegalArgumentException if the given type mirror is not a declared type (other types
    *       do not have internal names)
    * @see #getInternalName(TypeElement)
    */
   public String getInternalName(TypeMirror type) {
      if (type.getKind() != TypeKind.DECLARED) {
         throw new IllegalArgumentException("Given type mirror is not a declared type");
      }
      return getInternalName((TypeElement) typeUtils.asElement(type));
   }
   
   private final TypeKindVisitor8<Void, StringBuilder> descriptorVisitor =
         new TypeKindVisitor8<Void, StringBuilder>() {
            @Override
            protected Void defaultAction(TypeMirror e, StringBuilder sb) {
               throw new IllegalArgumentException(
                     "Cannot create type descriptor for type mirror " + e.getKind());
            }
      
            @Override
            public Void visitTypeVariable(TypeVariable t, StringBuilder sb) {
               return typeUtils.erasure(t).accept(this, sb);
            }
      
            @Override
            public Void visitPrimitiveAsBoolean(PrimitiveType t, StringBuilder sb) {
               sb.append('Z');
               return null;
            }
      
            @Override
            public Void visitPrimitiveAsByte(PrimitiveType t, StringBuilder sb) {
               sb.append('B');
               return null;
            }
      
            @Override
            public Void visitPrimitiveAsShort(PrimitiveType t, StringBuilder sb) {
               sb.append('S');
               return null;
            }
      
            @Override
            public Void visitPrimitiveAsInt(PrimitiveType t, StringBuilder sb) {
               sb.append('I');
               return null;
            }
      
            @Override
            public Void visitPrimitiveAsLong(PrimitiveType t, StringBuilder sb) {
               sb.append('J');
               return null;
            }
      
            @Override
            public Void visitPrimitiveAsChar(PrimitiveType t, StringBuilder sb) {
               sb.append('C');
               return null;
            }
      
            @Override
            public Void visitPrimitiveAsFloat(PrimitiveType t, StringBuilder sb) {
               sb.append('F');
               return null;
            }
      
            @Override
            public Void visitPrimitiveAsDouble(PrimitiveType t, StringBuilder sb) {
               sb.append('D');
               return null;
            }
      
            @Override
            public Void visitNoTypeAsVoid(NoType t, StringBuilder sb) {
               sb.append('V');
               return null;
            }
      
            @Override
            public Void visitArray(ArrayType t, StringBuilder sb) {
               sb.append('[');
               getDescriptor(t.getComponentType(), sb);
               return null;
            }
      
            @Override
            public Void visitDeclared(DeclaredType t, StringBuilder sb) {
               sb.append('L');
               sb.append(getInternalName(t));
               sb.append(';');
               return null;
            }
         };
   
   private void getDescriptor(TypeMirror type, StringBuilder sb) {
      type.accept(descriptorVisitor, sb);
   }
   
   /**
    * Computes the type descriptor for the given type mirror. A descriptor represents the mirror's
    * erased type. To get the generic equivalent of a descriptor, see
    * {@link Signatures#getTypeSignature(TypeMirror)}.
    * 
    * <p>The type descriptor is used to describe the type of a field or embedded in a method
    * descriptor to describe the return type of the method or the type of one of its parameters.
    * Descriptors are described in JLS section 4.3, <em><a
    * href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.3">
    * Descriptors</a></em>.
    * 
    * @param type a type mirror
    * @return a descriptor that represents the given type
    */
   public String getDescriptor(TypeMirror type) {
      StringBuilder sb = new StringBuilder();
      type.accept(descriptorVisitor, sb);
      return sb.toString();
   }
   
   /**
    * Computes the descriptor for the given method or constructor. The descriptor represents the
    * method's erased parameter and return type signature. To get the generic equivalent of a
    * descriptor, see {@link Signatures#getMethodSignature(ExecutableElement)}.
    * 
    * <p>Method descriptors are described in JLS section 4.3.3, <em><a
    * href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.3.3">Method
    * Descriptors</a></em>.
    * 
    * @param element a method or constructor
    * @return a descriptor that represents the given method
    */
   public String getDescriptor(ExecutableElement element) {
      StringBuilder sb = new StringBuilder();
      sb.append("(");
      for (VariableElement e : element.getParameters()) {
         getDescriptor(e.asType(), sb);
      }
      sb.append(")");
      getDescriptor(element.getReturnType(), sb);
      return sb.toString();
   }

   /**
    * Computes the descriptor for the given field. The descriptor represents the field's
    * erased type. To get the generic equivalent of a descriptor, see
    * {@link Signatures#getTypeSignature(TypeMirror)}.
    * 
    * <p>Field descriptors are described in JLS section 4.3.2, <em><a
    * href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.3.2">Field
    * Descriptors</a></em>.
    * 
    * @param element a field
    * @return a descriptor that represents the given field
    */
   public String getDescriptor(VariableElement element) {
      if (!element.getKind().isField()) {
         throw new IllegalArgumentException(
               "Given element is a " + element.getKind() + ", not a field");
      }
      return getDescriptor(element.asType());
   }
}