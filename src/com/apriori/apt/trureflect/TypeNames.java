package com.apriori.apt.trureflect;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.TypeKindVisitor8;
import javax.lang.model.util.Types;

public class TypeNames {
   private final Elements elementUtils;
   private final Types typeUtils;
   
   TypeNames(Elements elementUtils, Types typeUtils) {
      this.elementUtils = elementUtils;
      this.typeUtils = typeUtils;
   }

   public String getInternalName(TypeElement element) {
      return elementUtils.getBinaryName(element).toString().replace('.', '/');
   }

   public String getInternalName(TypeMirror type) {
      return getInternalName((TypeElement) typeUtils.asElement(type));
   }

   public String getDescriptor(TypeMirror type) {
      return type.accept(new TypeKindVisitor8<String, Void>() {
         @Override
         protected String defaultAction(TypeMirror e, Void p) {
            throw new IllegalArgumentException(
                  "Cannot create type descriptor for type mirror " + e.getKind());
         }

         @Override
         public String visitPrimitiveAsBoolean(PrimitiveType t, Void p) {
            return "Z";
         }

         @Override
         public String visitPrimitiveAsByte(PrimitiveType t, Void p) {
            return "B";
         }

         @Override
         public String visitPrimitiveAsShort(PrimitiveType t, Void p) {
            return "S";
         }

         @Override
         public String visitPrimitiveAsInt(PrimitiveType t, Void p) {
            return "I";
         }

         @Override
         public String visitPrimitiveAsLong(PrimitiveType t, Void p) {
            return "J";
         }

         @Override
         public String visitPrimitiveAsChar(PrimitiveType t, Void p) {
            return "C";
         }

         @Override
         public String visitPrimitiveAsFloat(PrimitiveType t, Void p) {
            return "F";
         }

         @Override
         public String visitPrimitiveAsDouble(PrimitiveType t, Void p) {
            return "D";
         }

         @Override
         public String visitNoTypeAsVoid(NoType t, Void p) {
            return "V";
         }

         @Override
         public String visitArray(ArrayType t, Void p) {
            return "[" +  getDescriptor(t.getComponentType());
         }

         @Override
         public String visitDeclared(DeclaredType t, Void p) {
            return "L" + getInternalName((TypeElement) t.asElement()) + ";";
         }

      }, null);
   }
   
   private void getDescriptor(TypeMirror type, StringBuilder sb) {
      type.accept(new TypeKindVisitor8<Void, Void>() {
         @Override
         protected Void defaultAction(TypeMirror e, Void p) {
            throw new IllegalArgumentException(
                  "Cannot create type descriptor for type mirror " + e.getKind());
         }

         @Override
         public Void visitTypeVariable(TypeVariable t, Void p) {
            return typeUtils.erasure(t).accept(this, p);
         }

         @Override
         public Void visitPrimitiveAsBoolean(PrimitiveType t, Void p) {
            sb.append('Z');
            return null;
         }

         @Override
         public Void visitPrimitiveAsByte(PrimitiveType t, Void p) {
            sb.append('B');
            return null;
         }

         @Override
         public Void visitPrimitiveAsShort(PrimitiveType t, Void p) {
            sb.append('S');
            return null;
         }

         @Override
         public Void visitPrimitiveAsInt(PrimitiveType t, Void p) {
            sb.append('I');
            return null;
         }

         @Override
         public Void visitPrimitiveAsLong(PrimitiveType t, Void p) {
            sb.append('J');
            return null;
         }

         @Override
         public Void visitPrimitiveAsChar(PrimitiveType t, Void p) {
            sb.append('C');
            return null;
         }

         @Override
         public Void visitPrimitiveAsFloat(PrimitiveType t, Void p) {
            sb.append('F');
            return null;
         }

         @Override
         public Void visitPrimitiveAsDouble(PrimitiveType t, Void p) {
            sb.append('D');
            return null;
         }

         @Override
         public Void visitNoTypeAsVoid(NoType t, Void p) {
            sb.append('V');
            return null;
         }

         @Override
         public Void visitArray(ArrayType t, Void p) {
            sb.append('[');
            getDescriptor(t.getComponentType(), sb);
            return null;
         }

         @Override
         public Void visitDeclared(DeclaredType t, Void p) {
            sb.append('L');
            sb.append(getInternalName((TypeElement) t.asElement()));
            sb.append(';');
            return null;
         }

      }, null);
   }
   
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

   public String getDescriptor(VariableElement element) {
      return getDescriptor(element.asType());
   }
}
