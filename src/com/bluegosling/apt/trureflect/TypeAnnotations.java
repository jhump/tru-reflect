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

/**
 * Conveys type annotations, present in type mirrors, to generated constructs, via ASM visitors.
 * 
 * @see Environment
 * 
 * @author Joshua Humphries (jhumphries131@gmail.com)
 */
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
   
   /**
    * Records annotations in the the given mirror as type annotations on a supertype using the given
    * class visitor.
    * 
    * @param visitor a class visitor
    * @param forEachAnnotationType optional consumer that will receive each encountered annotation
    *       type
    * @param superclass the supertype, whose type annotations will be recorded
    * @param i the index in the class's {@code implements} list of the interface this supertype
    *       represents; or -1 if this supertype is the superclass
    */
   public void recordSuperTypeAnnotations(ClassVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeMirror superclass, int i) {
      recordTypeAnnotations(visitor::visitTypeAnnotation, noOpIfNull(forEachAnnotationType),
            TypeReference.newSuperTypeReference(i), new TypePathBuilder(), superclass);
   }

   /**
    * Records annotations in the the given type parameter element as type annotations on a type
    * variable using the given class visitor.
    * 
    * @param visitor a class visitor
    * @param forEachAnnotationType optional consumer that will receive each encountered annotation
    *       type
    * @param typeVar the type variable, whose type annotations will be recorded
    * @param index the index of the given type variable in this class's type variable list
    */
   public void recordClassTypeParameterAnnotations(ClassVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeParameterElement typeVar, int index) {
      recordTypeParameterAnnotations(visitor::visitTypeAnnotation,
            noOpIfNull(forEachAnnotationType), typeVar, index, TypeReference.CLASS_TYPE_PARAMETER,
            TypeReference.CLASS_TYPE_PARAMETER_BOUND);
   }

   /**
    * Records annotations in the the given type parameter element as type annotations on a type
    * variable using the given method visitor.
    * 
    * @param visitor a method visitor
    * @param forEachAnnotationType optional consumer that will receive each encountered annotation
    *       type
    * @param typeVar the type variable, whose type annotations will be recorded
    * @param index the index of the given type variable in this method's type variable list
    */
   public void recordMethodTypeParameterAnnotations(MethodVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeParameterElement typeVar, int index) {
      recordTypeParameterAnnotations(visitor::visitTypeAnnotation,
            noOpIfNull(forEachAnnotationType), typeVar, index, TypeReference.METHOD_TYPE_PARAMETER,
            TypeReference.METHOD_TYPE_PARAMETER_BOUND);
   }

   /**
    * Records annotations in the the given mirror as type annotations on the receiver type using the
    * given method visitor.
    * 
    * @param visitor a method visitor
    * @param forEachAnnotationType optional consumer that will receive each encountered annotation
    *       type
    * @param receiver the receiver type, whose annotations will be recorded
    */
   public void recordReceiverTypeAnnotations(MethodVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeMirror receiver) {
      recordTypeAnnotations(visitor::visitTypeAnnotation, noOpIfNull(forEachAnnotationType),
            TypeReference.newTypeReference(TypeReference.METHOD_RECEIVER), new TypePathBuilder(),
            receiver);
   }

   /**
    * Records annotations in the the given mirror as type annotations on the return type using the
    * given method visitor.
    * 
    * @param visitor a method visitor
    * @param forEachAnnotationType optional consumer that will receive each encountered annotation
    *       type
    * @param returnType the return type, whose annotations will be recorded
    */
   public void recordReturnTypeAnnotations(MethodVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeMirror returnType) {
      recordTypeAnnotations(visitor::visitTypeAnnotation, noOpIfNull(forEachAnnotationType),
            TypeReference.newTypeReference(TypeReference.METHOD_RETURN), new TypePathBuilder(),
            returnType);
   }

   /**
    * Records annotations in the the given mirror as type annotations on a method parameter using
    * the given method visitor.
    * 
    * @param visitor a method visitor
    * @param forEachAnnotationType optional consumer that will receive each encountered annotation
    *       type
    * @param paramType the parameter type, whose annotations will be recorded
    * @param index the index of the given parameter in this method's parameter list
    */
   public void recordParameterTypeAnnotations(MethodVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeMirror paramType, int index) {
      recordTypeAnnotations(visitor::visitTypeAnnotation, noOpIfNull(forEachAnnotationType),
            TypeReference.newFormalParameterReference(index), new TypePathBuilder(), paramType);
   }

   /**
    * Records annotations in the the given mirror as type annotations on an exception type using
    * the given method visitor.
    * 
    * @param visitor a method visitor
    * @param forEachAnnotationType optional consumer that will receive each encountered annotation
    *       type
    * @param exceptionType the exception type, whose annotations will be recorded
    * @param index the index of the given exception in this method's list of declared thrown
    *       exception types
    */
   public void recordExceptionTypeAnnotations(MethodVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeMirror exceptionType, int index) {
      recordTypeAnnotations(visitor::visitTypeAnnotation, noOpIfNull(forEachAnnotationType),
            TypeReference.newExceptionReference(index), new TypePathBuilder(), exceptionType);
   }
   
   /**
    * Records annotations in the the given mirror as type annotations on a field using the given
    * method visitor.
    * 
    * @param visitor a field visitor
    * @param forEachAnnotationType optional consumer that will receive each encountered annotation
    *       type
    * @param fieldType the field type, whose annotations will be recorded
    */
   public void recordFieldTypeAnnotations(FieldVisitor visitor,
         Consumer<TypeMirror> forEachAnnotationType, TypeMirror fieldType) {
      recordTypeAnnotations(visitor::visitTypeAnnotation, noOpIfNull(forEachAnnotationType),
            TypeReference.newTypeReference(TypeReference.FIELD), new TypePathBuilder(), fieldType);
   }
   
   private Consumer<TypeMirror> noOpIfNull(Consumer<TypeMirror> consumer) {
      return consumer != null ? consumer : t -> {};
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
   
   private static class VisitorContext {
      final TypeAnnotationVisitor tv;
      final Consumer<TypeMirror> forEachAnnotationType;
      final TypeReference typeRef;
      final TypePathBuilder path;
      
      VisitorContext(TypeAnnotationVisitor tv, Consumer<TypeMirror> forEachAnnotationType,
            TypeReference typeRef, TypePathBuilder path) {
         this.tv = tv;
         this.forEachAnnotationType = forEachAnnotationType;
         this.typeRef = typeRef;
         this.path = path;
      }
   }
            
   private final SimpleTypeVisitor8<Void, VisitorContext> typeAnnotationVisitor =
         new SimpleTypeVisitor8<Void, VisitorContext>() {
            @Override
            public Void visitArray(ArrayType t, VisitorContext ctx) {
               recordDirectTypeAnnotations(
                     ctx.tv, ctx.forEachAnnotationType, ctx.typeRef, ctx.path, t);
               ctx.path.push(PathElement.arrayPathElement());
               t.getComponentType().accept(this, null);
               ctx.path.pop();
               return null;
            }
      
            @Override
            public Void visitDeclared(DeclaredType t, VisitorContext ctx) {
               // doing this recursively would generate the type paths in reverse order
               ArrayDeque<DeclaredType> types = new ArrayDeque<>();
               do {
                  types.push(t);
                  t = t.getEnclosingType().accept(Signatures.EXTRACT_DECLARED_TYPE, null);
               } while (t != null);
               int count = types.size();
               // navigate the type, outer-most to inner-most, recording type annotations
               while (!types.isEmpty()) {
                  t = types.pop();
                  recordDirectTypeAnnotations(
                        ctx.tv, ctx.forEachAnnotationType, ctx.typeRef, ctx.path, t);
                  int i = 0;
                  for (TypeMirror typeArg : t.getTypeArguments()) {
                     ctx.path.push(PathElement.typeArgPathElement(i++));
                     typeArg.accept(this, null);
                     ctx.path.pop();
                  }
                  ctx.path.push(PathElement.nestedPathElement());
               }
               // restore the type path by popping the nested path elements
               while (count > 0) {
                  ctx.path.pop();
                  count--;
               }
               return null;
            }
      
            @Override
            public Void visitWildcard(WildcardType t, VisitorContext ctx) {
               recordDirectTypeAnnotations(
                     ctx.tv, ctx.forEachAnnotationType, ctx.typeRef, ctx.path, t);
               TypeMirror bound = t.getSuperBound();
               if (bound == null) {
                  bound = t.getExtendsBound();
               }
               if (bound != null) {
                  ctx.path.push(PathElement.wildcardPathElement());
                  bound.accept(this, null);
                  ctx.path.pop();
               }
               return null;
            }
         };
   
   private void recordTypeAnnotations(TypeAnnotationVisitor tv,
         Consumer<TypeMirror> forEachAnnotationType, TypeReference typeRef, TypePathBuilder path,
         TypeMirror mirror) {
      mirror.accept(typeAnnotationVisitor,
            new VisitorContext(tv, forEachAnnotationType, typeRef, path));
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
