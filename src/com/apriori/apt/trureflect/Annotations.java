package com.apriori.apt.trureflect;

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

public class Annotations {
   private final Elements elementUtils;
   private final TypeNames typeNameUtils;
   
   Annotations(Elements elementUtils, TypeNames typeNameUtils) {
      this.elementUtils = elementUtils;
      this.typeNameUtils = typeNameUtils;
   }

   public void recordAnnotation(ClassVisitor visitor, AnnotationMirror mirror) {
      recordAnnotation(n -> visitor.visitAnnotation(n, true), mirror);
   }

   public void recordAnnotation(FieldVisitor visitor, AnnotationMirror mirror) {
      recordAnnotation(n -> visitor.visitAnnotation(n, true), mirror);
   }

   public void recordAnnotation(MethodVisitor visitor, AnnotationMirror mirror) {
      recordAnnotation(n -> visitor.visitAnnotation(n, true), mirror);
   }

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
   
   public void recordAnnotationValues(AnnotationVisitor visitor,
         Map<? extends ExecutableElement, ? extends AnnotationValue> values) {
      for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
            : values.entrySet()) {
         ExecutableElement annotationField = entry.getKey();
         recordAnnotationValue(visitor, annotationField.getSimpleName().toString(),
               entry.getValue());
       }
   }
   
   public void recordAnnotationValue(AnnotationVisitor visitor, String name,
         AnnotationValue value) {
      value.accept(new SimpleAnnotationValueVisitor8<Void, Void>() {
         @Override
         public Void visitBoolean(boolean b, Void p) {
            visitor.visit(name, b);
            return null;
         }

         @Override
         public Void visitByte(byte b, Void p) {
            visitor.visit(name, b);
            return null;
         }

         @Override
         public Void visitChar(char c, Void p) {
            visitor.visit(name, c);
            return null;
         }

         @Override
         public Void visitDouble(double d, Void p) {
            visitor.visit(name, d);
            return null;
         }

         @Override
         public Void visitFloat(float f, Void p) {
            visitor.visit(name, f);
            return null;
         }

         @Override
         public Void visitInt(int i, Void p) {
            visitor.visit(name, i);
            return null;
         }

         @Override
         public Void visitLong(long i, Void p) {
            visitor.visit(name, i);
            return null;
         }

         @Override
         public Void visitShort(short s, Void p) {
            visitor.visit(name, s);
            return null;
         }

         @Override
         public Void visitString(String s, Void p) {
            visitor.visit(name, s);
            return null;
         }

         @Override
         public Void visitType(TypeMirror t, Void p) {
            visitor.visit(name, getType(typeNameUtils.getDescriptor(t)));
            return null;
         }

         @Override
         public Void visitEnumConstant(VariableElement c, Void p) {
            visitor.visitEnum(name, typeNameUtils.getDescriptor(c.getEnclosingElement().asType()),
                  c.getSimpleName().toString());
            return null;
         }

         @Override
         public Void visitAnnotation(AnnotationMirror a, Void p) {
            AnnotationVisitor v =
                  visitor.visitAnnotation(name, typeNameUtils.getDescriptor(a.getAnnotationType()));
            recordAnnotationValues(v, elementUtils.getElementValuesWithDefaults(a));
            v.visitEnd();
            return null;
         }

         @Override
         public Void visitArray(List<? extends AnnotationValue> vals, Void p) {
            AnnotationVisitor v = visitor.visitArray(name);
            for (AnnotationValue arrayElement : vals) {
               recordAnnotationValue(v, "", arrayElement);
            }
            v.visitEnd();
            return null;
         }
      }, null);
   }
}
