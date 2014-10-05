package com.apriori.apt.trureflect;

import java.util.ArrayDeque;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.SimpleTypeVisitor8;
import javax.lang.model.util.TypeKindVisitor8;
import javax.lang.model.util.Types;

import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;

// TODO: javadoc, tests
public class Signatures {
   
   private final TypeMirror javaLangObject;
   private final Types typeUtils;
   private final TypeNames typeNameUtils;
   
   Signatures(TypeMirror javaLangObject, Types typeUtils, TypeNames typeNameUtils) {
      this.javaLangObject = javaLangObject;
      this.typeUtils = typeUtils;
      this.typeNameUtils = typeNameUtils;
   }

   /**
    * Creates a generic type signature string for the specified type.
    *
    * @param type the type
    * @return a type generic signature string
    * @see #recordTypeSignature(TypeMirror, SignatureVisitor)
    */
   public String getTypeSignature(TypeMirror type) {
      SignatureWriter writer = new SignatureWriter();
      recordTypeSignature(type, writer);
      return writer.toString();
   }

   /**
    * Creates a generic class signature string for the specified type.
    *
    * @param type the type
    * @return a class generic signature string
    * @see #recordClassSignature(TypeElement, SignatureVisitor)
    */
   public String getClassSignature(TypeElement type) {
      SignatureWriter writer = new SignatureWriter();
      recordClassSignature(type, writer);
      return writer.toString();
   }

   /**
    * Creates a generic method signature string for the specified method.
    *
    * @param method the method
    * @return a method generic signature string
    * @see #recordMethodSignature(ExecutableElement, SignatureVisitor)
    */
   public String getMethodSignature(ExecutableElement method) {
      SignatureWriter writer = new SignatureWriter();
      recordMethodSignature(method, writer);
      return writer.toString();
   }

   /**
    * Records the signature for the specified class using the specified visitor. A class signature
    * includes generic signatures for the class's type parameters, superclass, and interfaces,
    *
    * @param clazz a class
    * @param visitor a signature visitor
    */
   public void recordClassSignature(TypeElement type, SignatureVisitor visitor) {
      // first: type parameters
      for (TypeParameterElement typeParam : type.getTypeParameters()) {
         recordTypeParameter(typeParam, visitor);
      }
      // then super class
      TypeMirror superType = type.getKind().isInterface() ? javaLangObject : type.getSuperclass();
      recordTypeSignature(superType, visitor.visitSuperclass());
      // finally, interface types
      for (TypeMirror iface : type.getInterfaces()) {
         recordTypeSignature(iface, visitor.visitInterface());
      }
   }

   /**
    * Records the signature for the specified method using the specified visitor. A method signature
    * includes generic signatures for the method's type parameters, parameter types, return types,
    * and thrown exception types.
    *
    * @param method a method
    * @param visitor a signature visitor
    */
   public void recordMethodSignature(ExecutableElement method, SignatureVisitor visitor) {
      // first: type parameters
      for (TypeParameterElement typeParam : method.getTypeParameters()) {
         recordTypeParameter(typeParam, visitor);
      }
      // then parameter types
      for (VariableElement param : method.getParameters()) {
         recordTypeSignature(param.asType(), visitor.visitParameterType());
      }
      // return type
      recordTypeSignature(method.getReturnType(), visitor.visitReturnType());
      // finally, exception types
      for (TypeMirror exception : method.getThrownTypes()) {
         recordTypeSignature(exception, visitor.visitExceptionType());
      }
   }

   /**
    * Records the signature for the specified type using the specified visitor.
    *
    * @param type a type
    * @param visitor a signature visitor
    */
   public void recordTypeSignature(TypeMirror type, SignatureVisitor visitor) {
      type.accept(new TypeKindVisitor8<Void, Void>() {
         @Override
         public Void defaultAction(TypeMirror t, Void p) {
            throw new IllegalArgumentException(
                  "Cannot create type signature for type mirror " + t.getKind());
         }

         @Override
         public Void visitNoTypeAsVoid(NoType t, Void p) {
            visitor.visitBaseType(typeNameUtils.getDescriptor(t).charAt(0));
            return null;
         }

         @Override
         public Void visitPrimitive(PrimitiveType t, Void p) {
            visitor.visitBaseType(typeNameUtils.getDescriptor(t).charAt(0));
            return null;
         }

         @Override
         public Void visitArray(ArrayType t, Void p) {
            recordTypeSignature(t.getComponentType(), visitor.visitArrayType());
            return null;
         }

         @Override
         public Void visitDeclared(DeclaredType t, Void p) {
            recordDeclaredType(t, visitor);
            return null;
         }

         @Override
         public Void visitTypeVariable(javax.lang.model.type.TypeVariable t, Void p) {
            visitor.visitTypeVariable(t.asElement().getSimpleName().toString());
            return null;
         }
      }, null);
   }

   /**
    * Records a declared type using the specified visitor. A declared type is a class reference,
    * either "raw" (i.e. {@code Class}) or parameterized (i.e. {@code ParameterizedType}). If the
    * specified type is a member type then this will traverse its enclosing types and record a
    * parameterized type for each one.
    *
    * @param type the type
    * @param visitor a signature visitor
    */
   private void recordDeclaredType(DeclaredType type, SignatureVisitor visitor) {
      ArrayDeque<DeclaredType> outerClasses = new ArrayDeque<>();
      while (type != null) {
         outerClasses.push(type);
         type = type.getEnclosingType().accept(new SimpleTypeVisitor8<DeclaredType, Void>() {
            @Override
            public DeclaredType visitDeclared(DeclaredType t, Void p) {
               return t;
            }

            @Override
            public DeclaredType visitNoType(NoType t, Void p) {
               return null;
            }

            @Override
            public DeclaredType defaultAction(TypeMirror t, Void p) {
               throw new IllegalStateException("Unexpected type encountered: " + t.getKind());
            }
         }, null);
      }
      boolean outerMost = true;
      while (!outerClasses.isEmpty()) {
         DeclaredType current = outerClasses.pop();
         // TODO: is this the right way to handle this?
         if (!outerMost || !current.getTypeArguments().isEmpty() || outerClasses.isEmpty()) {
            recordParameterizedType(current, visitor, outerMost);
            outerMost = false;
         }
      }
      visitor.visitEnd();
   }

   /**
    * Records a parameterized type using the specified visitor. This represents a single type and
    * its type arguments. The outer-most class is not necessarily a top-level class but rather the
    * highest level class that has no type arguments. Every type there-under is then an inner class.
    *
    * @param type the type
    * @param visitor a signature visitor
    * @param outerMost true if this is the outer-most class; false if it is an inner class
    */
   private void recordParameterizedType(DeclaredType type, SignatureVisitor visitor,
         boolean outerMost) {
      if (outerMost) {
         String internalName = typeNameUtils.getInternalName(type);
         visitor.visitClassType(internalName);
      } else {
         visitor.visitInnerClassType(type.asElement().getSimpleName().toString());
      }
      for (TypeMirror typeArg : type.getTypeArguments()) {
         if (typeArg.getKind() == TypeKind.WILDCARD) {
            WildcardType wildcardType = (WildcardType) typeArg;
            TypeMirror superBound = wildcardType.getSuperBound();
            if (superBound != null) {
               recordTypeSignature(superBound,
                     visitor.visitTypeArgument(SignatureVisitor.SUPER));
            } else {
               TypeMirror extendsBound = wildcardType.getExtendsBound();
               if (extendsBound != null) {
                  recordTypeSignature(extendsBound,
                        visitor.visitTypeArgument(SignatureVisitor.EXTENDS));
               } else {
                  visitor.visitTypeArgument();
               }
            }
         } else {
            recordTypeSignature(typeArg, visitor.visitTypeArgument(SignatureVisitor.INSTANCEOF));
         }
      }
   }

   /**
    * Records the specified type variable using the specified visitor as a formal type parameter.
    *
    * @param typeVar a type variable
    * @param visitor a signature visitor
    */
   public void recordTypeParameter(TypeParameterElement typeParam, SignatureVisitor visitor) {
      visitor.visitFormalTypeParameter(typeParam.getSimpleName().toString());
      for (TypeMirror bound : typeParam.getBounds()) {
         SignatureVisitor typeVisitor =
               isInterface(bound) ? visitor.visitInterfaceBound() : visitor.visitClassBound();
         recordTypeSignature(bound, typeVisitor);
      }
   }

   /**
    * Determines if the specified type is an interface or a class. This can be used for defining
    * class vs. interface bounds in a generic signature string.
    *
    * @param type the type
    * @return true if the type is an interface; false otherwise
    */
   private boolean isInterface(TypeMirror type) {
      TypeMirror erased = typeUtils.erasure(type);
      if (erased.getKind() != TypeKind.DECLARED) return false;
      return typeUtils.asElement(erased).getKind().isInterface();
   }
}
