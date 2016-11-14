package com.bluegosling.apt.trureflect;

import static org.objectweb.asm.Type.getType;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleAnnotationValueVisitor8;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

/**
 * Conveys annotations, from {@link AnnotationMirror} objects, to generated constructs, via ASM
 * visitors.
 * 
 * @see Environment
 * 
 * @author Joshua Humphries (jhumphries131@gmail.com)
 */
public class Annotations {
   private final Elements elementUtils;
   private final TypeNames typeNameUtils;
   
   Annotations(Elements elementUtils, TypeNames typeNameUtils) {
      this.elementUtils = elementUtils;
      this.typeNameUtils = typeNameUtils;
   }

   /**
    * Records the given mirror as an annotation on a class using the given class visitor.
    * 
    * @param visitor a class visitor
    * @param mirror an annotation mirror
    */
   public void recordAnnotation(ClassVisitor visitor, AnnotationMirror mirror) {
      recordAnnotation(n -> visitor.visitAnnotation(n, true), mirror);
   }

   /**
    * Records the given mirror as an annotation on a field using the given field visitor.
    * 
    * @param visitor a field visitor
    * @param mirror an annotation mirror
    */
   public void recordAnnotation(FieldVisitor visitor, AnnotationMirror mirror) {
      recordAnnotation(n -> visitor.visitAnnotation(n, true), mirror);
   }

   /**
    * Records the given mirror as an annotation on a method using the given method visitor.
    * 
    * @param visitor a method visitor
    * @param mirror an annotation mirror
    */
   public void recordAnnotation(MethodVisitor visitor, AnnotationMirror mirror) {
      recordAnnotation(n -> visitor.visitAnnotation(n, true), mirror);
   }

   /**
    * Records the given mirror as an annotation on a method parameter using the given method
    * visitor.
    * 
    * @param visitor a method visitor
    * @param paramIndex the index of the parameter that has the given annotation (starting at zero)
    * @param mirror an annotation mirror
    */
   public void recordParameterAnnotation(MethodVisitor visitor, int paramIndex,
         AnnotationMirror mirror) {
      recordAnnotation(n -> visitor.visitParameterAnnotation(paramIndex, n, true), mirror);
   }

   private void recordAnnotation(Function<String, AnnotationVisitor> fn, AnnotationMirror mirror) {
      // we record all annotations as visible at runtime, if retention period says otherwise, so
      // that annotation processor can use reflective code to inspect all annotations
      AnnotationVisitor av = fn.apply(typeNameUtils.getDescriptor(mirror.getAnnotationType()));
      recordAnnotationValues(av, elementUtils.getElementValuesWithDefaults(mirror));
      av.visitEnd();
   }
   
   /**
    * Records the given annotation values with the given annotation visitor. This method is used
    * when conveying annotation mirror to
    * {@linkplain #recordAnnotation(ClassVisitor, AnnotationMirror) class},
    * {@linkplain #recordAnnotation(FieldVisitor, AnnotationMirror) field}, or
    * {@linkplain #recordAnnotation(MethodVisitor, AnnotationMirror) method}
    * {@linkplain #recordParameterAnnotation(MethodVisitor, int, AnnotationMirror) visitors}.
    * 
    * @param visitor an annotation visitor
    * @param values a map of annotation method elements to their associated value mirror
    */
   public void recordAnnotationValues(AnnotationVisitor visitor,
         Map<? extends ExecutableElement, ? extends AnnotationValue> values) {
      for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
            : values.entrySet()) {
         ExecutableElement annotationField = entry.getKey();
         recordAnnotationValue(visitor, annotationField.getSimpleName().toString(),
               entry.getValue());
       }
   }
   
   private static class ValueVisitorContext {
      final AnnotationVisitor visitor;
      final String name;
      
      ValueVisitorContext(AnnotationVisitor visitor, String name) {
         this.visitor = visitor;
         this.name = name;
      }
   }
   
   private final SimpleAnnotationValueVisitor8<Void, ValueVisitorContext> annotationValueVisitor =
         new SimpleAnnotationValueVisitor8<Void, ValueVisitorContext>() {
            @Override
            public Void visitBoolean(boolean b, ValueVisitorContext ctx) {
               ctx.visitor.visit(ctx.name, b);
               return null;
            }
      
            @Override
            public Void visitByte(byte b, ValueVisitorContext ctx) {
               ctx.visitor.visit(ctx.name, b);
               return null;
            }
      
            @Override
            public Void visitChar(char c, ValueVisitorContext ctx) {
               ctx.visitor.visit(ctx.name, c);
               return null;
            }
      
            @Override
            public Void visitDouble(double d, ValueVisitorContext ctx) {
               ctx.visitor.visit(ctx.name, d);
               return null;
            }
      
            @Override
            public Void visitFloat(float f, ValueVisitorContext ctx) {
               ctx.visitor.visit(ctx.name, f);
               return null;
            }
      
            @Override
            public Void visitInt(int i, ValueVisitorContext ctx) {
               ctx.visitor.visit(ctx.name, i);
               return null;
            }
      
            @Override
            public Void visitLong(long i, ValueVisitorContext ctx) {
               ctx.visitor.visit(ctx.name, i);
               return null;
            }
      
            @Override
            public Void visitShort(short s, ValueVisitorContext ctx) {
               ctx.visitor.visit(ctx.name, s);
               return null;
            }
      
            @Override
            public Void visitString(String s, ValueVisitorContext ctx) {
               ctx.visitor.visit(ctx.name, s);
               return null;
            }
      
            @Override
            public Void visitType(TypeMirror t, ValueVisitorContext ctx) {
               ctx.visitor.visit(ctx.name, getType(typeNameUtils.getDescriptor(t)));
               return null;
            }
      
            @Override
            public Void visitEnumConstant(VariableElement c, ValueVisitorContext ctx) {
               ctx.visitor.visitEnum(ctx.name,
                     typeNameUtils.getDescriptor(c.getEnclosingElement().asType()),
                     c.getSimpleName().toString());
               return null;
            }
      
            @Override
            public Void visitAnnotation(AnnotationMirror a, ValueVisitorContext ctx) {
               AnnotationVisitor v = ctx.visitor.visitAnnotation(ctx.name,
                     typeNameUtils.getDescriptor(a.getAnnotationType()));
               recordAnnotationValues(v, elementUtils.getElementValuesWithDefaults(a));
               v.visitEnd();
               return null;
            }
      
            @Override
            public Void visitArray(List<? extends AnnotationValue> vals, ValueVisitorContext ctx) {
               AnnotationVisitor v = ctx.visitor.visitArray(ctx.name);
               for (AnnotationValue arrayElement : vals) {
                  recordAnnotationValue(v, "", arrayElement);
               }
               v.visitEnd();
               return null;
            }
         };
   
   /**
    * Records the given annotation value with the given annotation visitor.
    * 
    * @param visitor an annotation visitor
    * @param name the name of an annotation method
    * @param value the value mirror to associate with the named method
    */
   public void recordAnnotationValue(AnnotationVisitor visitor, String name,
         AnnotationValue value) {
      value.accept(annotationValueVisitor, new ValueVisitorContext(visitor, name));
   }
}