package com.bluegosling.apt.trureflect;

import java.util.ArrayDeque;
import java.util.function.Consumer;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleTypeVisitor8;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.TypeReference;

import com.bluegosling.apt.trureflect.TypePathBuilder.PathElement;

public class TypeAnnotations {
   
   @FunctionalInterface
   private interface TypeAnnotationVisitor {
      AnnotationVisitor visit(int typeRef, TypePath path, String descriptor, boolean visible);
   }
   
   private final Elements elementUtils;
   private final TypeNames typeNameUtils;
   private final Annotations annotationUtils;
   
   TypeAnnotations(Elements elementUtils, TypeNames typeNameUtils, Annotations annotationUtils) {
      this.elementUtils = elementUtils;
      this.typeNameUtils = typeNameUtils;
      this.annotationUtils = annotationUtils;
   }
   
   public void recordSuperTypeAnnotations(ClassVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeMirror superclass, int i) {
      recordTypeAnnotations(visitor::visitTypeAnnotation, forEachAnnotationType,
            TypeReference.newSuperTypeReference(i), new TypePathBuilder(), superclass);
   }

   public void recordClassTypeParameterAnnotations(ClassVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeParameterElement typeVar, int index) {
      recordTypeParameterAnnotations(visitor::visitTypeAnnotation, forEachAnnotationType, typeVar,
            index, TypeReference.CLASS_TYPE_PARAMETER, TypeReference.CLASS_TYPE_PARAMETER_BOUND);
   }

   public void recordMethodTypeParameterAnnotations(MethodVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeParameterElement typeVar, int index) {
      recordTypeParameterAnnotations(visitor::visitTypeAnnotation, forEachAnnotationType, typeVar,
            index, TypeReference.METHOD_TYPE_PARAMETER, TypeReference.METHOD_TYPE_PARAMETER_BOUND);
   }

   public void recordReceiverTypeAnnotations(MethodVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeMirror receiver) {
      recordTypeAnnotations(visitor::visitTypeAnnotation, forEachAnnotationType,
            TypeReference.newTypeReference(TypeReference.METHOD_RECEIVER), new TypePathBuilder(),
            receiver);
   }

   public void recordReturnTypeAnnotations(MethodVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeMirror returnType) {
      recordTypeAnnotations(visitor::visitTypeAnnotation, forEachAnnotationType,
            TypeReference.newTypeReference(TypeReference.METHOD_RETURN), new TypePathBuilder(),
            returnType);
   }

   public void recordParameterTypeAnnotations(MethodVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeMirror paramType, int index) {
      recordTypeAnnotations(visitor::visitTypeAnnotation, forEachAnnotationType,
            TypeReference.newFormalParameterReference(index), new TypePathBuilder(), paramType);
   }

   public void recordExceptionTypeAnnotations(MethodVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeMirror exceptionType, int index) {
      recordTypeAnnotations(visitor::visitTypeAnnotation, forEachAnnotationType,
            TypeReference.newExceptionReference(index), new TypePathBuilder(), exceptionType);
   }

   public void recordFieldTypeAnnotations(FieldVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeMirror fieldType) {
      recordTypeAnnotations(visitor::visitTypeAnnotation, forEachAnnotationType,
            TypeReference.newTypeReference(TypeReference.FIELD), new TypePathBuilder(), fieldType);
   }
   
   private void recordTypeParameterAnnotations(TypeAnnotationVisitor tv,
         Consumer<TypeMirror> forEachAnnotationType, TypeParameterElement typeVar, int index,
         int typeVarRef, int typeBoundRef) {
      for (AnnotationMirror typeAnnotation : typeVar.getAnnotationMirrors()) {
         AnnotationVisitor av = tv.visit(
               TypeReference.newTypeParameterReference(typeVarRef, index).getValue(),
               null, typeNameUtils.getDescriptor(typeAnnotation.getAnnotationType()), true);
         annotationUtils.recordAnnotationValues(av,
               elementUtils.getElementValuesWithDefaults(typeAnnotation));
      }
      int boundIndex = 0;
      for (TypeMirror bound : typeVar.getBounds()) {
         recordTypeAnnotations(tv, forEachAnnotationType,
               TypeReference.newTypeParameterBoundReference(typeBoundRef, index, boundIndex++),
               new TypePathBuilder(), bound);
      }
   }
   
   private void recordTypeAnnotations(TypeAnnotationVisitor tv,
         Consumer<TypeMirror> forEachAnnotationType, TypeReference typeRef, TypePathBuilder path,
         TypeMirror mirror) {
      mirror.accept(new SimpleTypeVisitor8<Void, Void>() {
         @Override
         public Void visitArray(ArrayType t, Void p) {
            recordDirectTypeAnnotations(tv, forEachAnnotationType, typeRef, path, t);
            path.push(PathElement.arrayPathElement());
            t.getComponentType().accept(this, null);
            path.pop();
            return null;
         }

         @Override
         public Void visitDeclared(DeclaredType t, Void p) {
            // doing this recursively would generate the type paths in reverse order
            ArrayDeque<DeclaredType> types = new ArrayDeque<>();
            do {
               types.push(t);
               t = t.getEnclosingType().accept(new SimpleTypeVisitor8<DeclaredType, Void>() {
                  @Override
                  public DeclaredType visitDeclared(DeclaredType t, Void p) {
                     return t;
                  }
               }, null);
            } while (t != null);
            int count = types.size();
            // navigate the type, outer-most to inner-most, recording type annotations
            while (!types.isEmpty()) {
               t = types.pop();
               recordDirectTypeAnnotations(tv, forEachAnnotationType, typeRef, path, t);
               int i = 0;
               for (TypeMirror typeArg : t.getTypeArguments()) {
                  path.push(PathElement.typeArgPathElement(i++));
                  typeArg.accept(this, null);
                  path.pop();
               }
               path.push(PathElement.nestedPathElement());
            }
            // restore the type path by popping the nested path elements
            while (count > 0) {
               path.pop();
               count--;
            }
            return null;
         }

         @Override
         public Void visitWildcard(WildcardType t, Void p) {
            recordDirectTypeAnnotations(tv, forEachAnnotationType, typeRef, path, t);
            TypeMirror bound = t.getSuperBound();
            if (bound == null) {
               bound = t.getExtendsBound();
            }
            if (bound != null) {
               path.push(PathElement.wildcardPathElement());
               bound.accept(this, null);
               path.pop();
            }
            return null;
         }
      }, null);
   }
   
   private void recordDirectTypeAnnotations(TypeAnnotationVisitor tv,
         Consumer<TypeMirror> forEachAnnotationType, TypeReference typeRef, TypePathBuilder path,
         TypeMirror mirror) {
      for (AnnotationMirror typeAnnotation : mirror.getAnnotationMirrors()) {
         forEachAnnotationType.accept(typeAnnotation.getAnnotationType());
         AnnotationVisitor av = tv.visit(typeRef.getValue(), path.build(),
               typeNameUtils.getDescriptor(typeAnnotation.getAnnotationType()), true);
         annotationUtils.recordAnnotationValues(av,
               elementUtils.getElementValuesWithDefaults(typeAnnotation));
      }
   }
}
