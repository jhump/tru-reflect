package com.bluegosling.apt.trureflect;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Array;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleAnnotationValueVisitor8;
import javax.lang.model.util.SimpleElementVisitor8;
import javax.lang.model.util.SimpleTypeVisitor8;
import javax.lang.model.util.Types;

/**
 * Provides the ability to use Java reflection during annotation processing. The types and elements
 * can be inspected and manipulated using normal reflection. Reflective operations that won't work
 * include object instantiation, method invocations, and field accesses.
 * 
 * <p>This works by synthesizing classes, including all fields, methods, etc, based on the types'
 * elements. The synthesized classes do not have any code (all methods and constructors simply throw
 * {@link UnsupportedOperationException}).
 * 
 * <p>Classes are synthesized using a custom {@link ClassLoader} that relies on the ASM library for
 * generating bytecode. A limitation of this approach is that you cannot use it to interact with
 * source forms for anything in {@code java.*} packages or sub-packages thereof. These classes can
 * only be defined/loaded by the boot class loader. Attempting to use this library to reflect on
 * these types will result in the use of class tokens from the actual JRE classes on the compiler's
 * class path (as if loaded using {@link Class#forName(String)}) and <em>not</em> the use the types'
 * elements (even they are present in source form for this invocation of the annotation processor).
 * 
 * <p>In addition to viewing {@link Element}s using reflection types, this class also allows for
 * viewing {@link TypeMirror}s as reflection {@link Type}s and {@link AnnotationMirror}s as actual
 * instances of {@link Annotation}s. 
 * 
 * @author Joshua Humphries (jhumphries131@gmail.com)
 */
// TODO: javadoc, tests
public final class TruReflect {

   private final Elements elementUtils;
   private final Types typeUtils;
   private final TruReflectClassLoader loader;
   
   /**
    * Constructs a new instance for the current processing environment.
    * 
    * @param elementUtils element utilities for the current processing environment
    * @param typeUtils type mirror utilities for the current processing environment
    */
   public TruReflect(Elements elementUtils, Types typeUtils) {
      this.elementUtils = elementUtils;
      this.typeUtils = typeUtils;
      loader = new TruReflectClassLoader(elementUtils, typeUtils);
   }

   /**
    * Returns a class token for the given type element.
    * 
    * <p>Note that if the given element corresponds to a type in a {@code java.*} package or
    * sub-package then the returned class token may not correspond to the given element. The actual
    * class token returned in that situation is the same as if loading the class using the
    * following:<br>
    * {@code Class.forName(element.getQualifiedName().toString())}
    * 
    * @param element the type element
    * @return the class token that corresponds to the given element
    * 
    * @see #forElement(Element)
    */
   public Class<?> forElement(TypeElement element) {
      return loader.loadClass(element);
   }
   
   /**
    * Returns a type variable for the given element.
    * 
    * <p>Note that if the given element corresponds to a type variable defined in a class in a
    * {@code java.*} package or sub-package then the returned object may not correspond to the given
    * element. The actual type variable returned may instead come from JRE classes on the compiler's
    * class path.
    * 
    * @param element the type parameter element
    * @return the type variable that corresponds to the given element
    * 
    * @see #forElement(Element)
    */
   public TypeVariable<?> forElement(TypeParameterElement element) {
      Element generic = element.getGenericElement(); 
      GenericDeclaration d = (GenericDeclaration) forElement(generic);
      String variableName = element.getSimpleName().toString();
      for (TypeVariable<?> var : d.getTypeParameters()) {
         if (var.getName().equals(variableName)) {
            return var;
         }
      }
      throw new AssertionError("Type parameter " + variableName
            + " not found in type parameter list for " + fullName(generic));
   }
   
   /**
    * Returns a package for the given element.
    * 
    * <p>Note that if the given element corresponds to a {@code java.*} package or sub-package then
    * the returned object may not correspond to the given element. The actual package returned may
    * instead come from JRE classes on the compiler's class path.
    * 
    * @param element the package element
    * @return the package that corresponds to the given element
    * 
    * @see #forElement(Element)
    */
   public Package forElement(PackageElement element) {
      String packageName = element.getQualifiedName().toString(); 
      return loader.ensurePackageDefined(packageName, element);
   }

   /**
    * Returns a method or constructor for the given executable element.
    * 
    * <p>Note that if the given element corresponds to a member of a class in a {@code java.*}
    * package or sub-package then the returned object may not correspond to the given element. The
    * actual method or constructor returned may instead come from a JRE class on the compiler's
    * class path.
    * 
    * @param element the executable element
    * @return the method or constructor that corresponds to the given element
    * @throws IllegalArgumentException if the given element does not represent a method or
    *       constructor but instead represents a static or instance initializer
    * 
    * @see #forElement(Element)
    */
   public Executable forElement(ExecutableElement element) {
      String methodName;
      switch (element.getKind()) {
         case METHOD:
            methodName = element.getSimpleName().toString();
            break;
         case CONSTRUCTOR:
            methodName = null;
            break;
         default:
            throw new IllegalArgumentException(
                  "Cannot represent " + element.getKind() + " element via reflection");
      }
      // determine argument list
      List<? extends VariableElement> args = element.getParameters();
      Class<?> argTypes[] = new Class<?>[args.size()];
      for (int i = 0, len = argTypes.length; i < len; i++) {
         Type t = forTypeMirror(args.get(i).asType());
         argTypes[i] = rawType(t);
      }
      // get declaring type
      TypeElement type = (TypeElement) element.getEnclosingElement();
      Class<?> clazz = forElement(type);
      // finally, query for the executable member
      try {
         return methodName == null
               ? clazz.getConstructor(argTypes)
               : clazz.getDeclaredMethod(methodName, argTypes);
      } catch (NoSuchMethodException e) {
         throw new AssertionError("Failed to extract method|ctor from synthesized class", e);
      }
   }
   
   /**
    * Extracts the raw type, or erased type, from a given generic type.
    * 
    * @param t a generic type
    * @return the corresponding raw type
    */
   private Class<?> rawType(Type t) {
      if (t instanceof Class) {
         return (Class<?>) t;
      } else if (t instanceof ParameterizedType) {
         return rawType(((ParameterizedType) t).getRawType());
      } else if (t instanceof GenericArrayType) {
         Class<?> comp = rawType(((GenericArrayType) t).getGenericComponentType());
         return Array.newInstance(comp, 0).getClass();
      } else if (t instanceof TypeVariable || t instanceof WildcardType) {
         Type bounds[] = t instanceof TypeVariable
               ? ((TypeVariable<?>) t).getBounds()
               : ((WildcardType) t).getUpperBounds();
         assert bounds.length > 0;
         return rawType(bounds[0]);
      } else {
         throw new AssertionError("Unrecognized type " + t);
      }
   }
   
   /**
    * Returns a field for the given variable element.
    * 
    * @param element the variable element
    * @return the field that corresponds to the given element
    * 
    * @see #forElement(Element)
    */
   private Field forFieldElement(VariableElement element) {
      assert element.getKind().isField();
      // get declaring type
      TypeElement type = (TypeElement) element.getEnclosingElement();
      Class<?> clazz = forElement(type);
      // then query for the field
      try {
         return clazz.getDeclaredField(element.getSimpleName().toString());
      } catch (NoSuchFieldException e) {
         throw new AssertionError("Failed to extract field from synthesized class", e);
      }
   }

   /**
    * Returns a parameter for the given variable element.
    * 
    * @param element the variable element
    * @return the parameter that corresponds to the given element
    * 
    * @see #forElement(Element)
    */
   private Parameter forParameterElement(VariableElement element) {
      assert element.getKind() == ElementKind.PARAMETER;
      ExecutableElement exEl = (ExecutableElement) element.getEnclosingElement(); 
      Executable ex = forElement(exEl);
      int idx = 0;
      for (VariableElement p : exEl.getParameters()) {
         if (element.equals(p)) {
            Parameter ret = ex.getParameters()[idx];
            assert ret.getName().equals(element.getSimpleName().toString());
            return ret;
         }
         idx++;
      }
      throw new AssertionError("Parameter " + element.getSimpleName()
            + " not found in parameter list for executable " + fullName(exEl));
   }

   /**
    * Computes a fully-qualified name for the given element. If the element has a {@linkplain
    * QualifiedNameable qualified name} then it is returned. Otherwise, the value returned is the
    * fully-qualified name of the element's enclosing element plus a period and then the given
    * element's simple name.
    * 
    * @param e an element
    * @return a fully-qualified name for the give element
    */
   private String fullName(Element e) {
      if (e instanceof QualifiedNameable) {
         return ((QualifiedNameable) e).getQualifiedName().toString();
      } else {
         Element enclosing = e.getEnclosingElement();
         return enclosing == null
               ? e.getSimpleName().toString()
               : fullName(e.getEnclosingElement()) + "." + e.getSimpleName().toString();
      }
   }

   /**
    * Returns a reflective object for the given element. This will return a class token, type
    * variable, package, method, constructor, field, or parameter, depending on the actual type of
    * the given element.
    * 
    * <p>If the given element cannot be represented by one of the reflective objects listed above
    * then an exception is thrown. Unsupported elements include those that represent static and
    * instance initializers and those that represent local variables (including resource variables
    * declared in try-with-resources blocks and exception variables declared in catch blocks).
    * 
    * <p>Note that if the given element corresponds to anything in a {@code java.*} package or
    * sub-package then the returned object may not correspond to the given element. The actual
    * object returned in that situation may instead come from a JRE class on the compiler's class
    * path.
    * 
    * @param element the element
    * @return the reflection object that corresponds to the given element
    * @throws IllegalArgumentException if the given element cannot be represented via a reflection
    *       type
    */
   public AnnotatedElement forElement(Element element) {
      return element.accept(new SimpleElementVisitor8<AnnotatedElement, Void>() {
         @Override
         public AnnotatedElement visitPackage(PackageElement e, Void p) {
            return forElement(e);
         }
 
         @Override
         public AnnotatedElement visitType(TypeElement e, Void p) {
            return forElement(e);
         }

         @Override
         public AnnotatedElement visitVariable(VariableElement e, Void p) {
            if (e.getKind().isField()) {
               return forFieldElement(e);
            } else if (e.getKind() == ElementKind.PARAMETER) {
               return forParameterElement(e);
            } else {
               throw new IllegalArgumentException(
                     "Cannot represent " + e.getKind() + " element via reflection");
            }
         }

         @Override
         public AnnotatedElement visitExecutable(ExecutableElement e, Void p) {
            return forElement(e);
         }

         @Override
         public AnnotatedElement visitTypeParameter(TypeParameterElement e, Void p) {
            return forElement(e);
         }
         
         @Override
         public AnnotatedElement defaultAction(Element e, Void p) {
            throw new IllegalArgumentException(
                  "Cannot represent " + e.getKind() + " element via reflection");
         }
      }, null);
   }

   public Class<?> forTypeMirror(PrimitiveType type) {
      switch (type.getKind()) {
         case BOOLEAN:
            return boolean.class;
         case BYTE:
            return byte.class;
         case CHAR:
            return char.class;
         case SHORT:
            return short.class;
         case INT:
            return int.class;
         case LONG:
            return long.class;
         case FLOAT:
            return float.class;
         case DOUBLE:
            return double.class;
         default:
            throw new AssertionError(
                  "Cannot represent " + type.getKind() + " type mirror via reflection");
      }
   }

   public TypeVariable<?> forTypeMirror(javax.lang.model.type.TypeVariable type) {
      return forElement((TypeParameterElement) typeUtils.asElement(type));
   }

   public WildcardType forTypeMirror(javax.lang.model.type.WildcardType type) {
      TypeMirror upper = type.getExtendsBound();
      Type upperBounds[];
      if (upper == null) {
         upperBounds = new Type[] { Object.class };
      } else if (upper.getKind() == TypeKind.INTERSECTION) {
         IntersectionType intersection = (IntersectionType) upper;
         List<? extends TypeMirror> bounds = intersection.getBounds();
         upperBounds = new Type[bounds.size()];
         int i = 0;
         for (TypeMirror bound : bounds) {
            upperBounds[i++] = forTypeMirror(bound);
         }
      } else {
         upperBounds = new Type[] { forTypeMirror(upper) };
      }
      TypeMirror lower = type.getSuperBound();
      Type lowerBounds[] = lower == null ? new Type[0] : new Type[] { forTypeMirror(lower) };
      return new WildcardType() {
         @Override
         public Type[] getUpperBounds() {
            return upperBounds.clone();
         }

         @Override
         public Type[] getLowerBounds() {
            return lowerBounds.clone();
         }
         
         // TODO: hashCode, equals, toString
      };
   }
   
   private Type forDeclaredType(DeclaredType type) {
      TypeMirror owner = type.getEnclosingType();
      Type ownerType = owner.getKind() == TypeKind.NONE
            ? null : forTypeMirror(owner);
      List<? extends TypeMirror> args = type.getTypeArguments();
      Class<?> rawType = forElement((TypeElement) type.asElement()); 
      if ((ownerType == null || ownerType instanceof Class)
            && args.isEmpty()) {
         return rawType;
      } else {
         Type argTypes[] = new Type[args.size()];
         for (int i = 0, len = argTypes.length; i < len; i++) {
            argTypes[i] = forTypeMirror(args.get(i));
         }
         return new ParameterizedType() {
            @Override
            public Type getRawType() {
               return rawType;
            }
            
            @Override
            public Type getOwnerType() {
               return ownerType;
            }
            
            @Override
            public Type[] getActualTypeArguments() {
               return argTypes.clone();
            }
            
            // TODO: hashCode, equals, toString
         };
      }
   }

   private Type forArrayType(ArrayType type) {
      Type comp = forTypeMirror(type.getComponentType());
      return comp instanceof Class
            ? Array.newInstance((Class<?>) comp, 0).getClass()
            : new GenericArrayType() {
               @Override
               public Type getGenericComponentType() {
                  return comp;
               }
               
               // TODO: hashCode, equals, toString
            };
   }

   public Type forTypeMirror(TypeMirror type) {
      return type.accept(new SimpleTypeVisitor8<Type, Void>() {
         @Override
         public Type visitPrimitive(PrimitiveType t, Void p) {
            return forTypeMirror(t);
         }

         @Override
         public Type visitArray(ArrayType t, Void p) {
            return forArrayType(t);
         }

         @Override
         public Type visitDeclared(DeclaredType t, Void p) {
            return forDeclaredType(t);
         }

         @Override
         public Type visitTypeVariable(javax.lang.model.type.TypeVariable t, Void p) {
            return forTypeMirror(t);
         }

         @Override
         public Type visitWildcard(javax.lang.model.type.WildcardType t, Void p) {
            return forTypeMirror(t);
         }

         @Override
         public Type visitNoType(NoType t, Void p) {
            return t.getKind() == TypeKind.VOID ? void.class : defaultAction(t, p);
         }

         @Override
         public Type defaultAction(TypeMirror t, Void p) {
            throw new IllegalArgumentException(
                  "Cannot represent " + t.getKind() + " type mirror via reflection");
         }
      }, null);
   }

   public Annotation forAnnotationMirror(AnnotationMirror annotation) {
      Map<? extends ExecutableElement, ? extends AnnotationValue> mirrorValues =
            elementUtils.getElementValuesWithDefaults(annotation);
      Map<String, Object> annotationValues = new HashMap<>((mirrorValues.size() + 1) * 4 / 3);
      for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
            : mirrorValues.entrySet()) {
         annotationValues.put(entry.getKey().getSimpleName().toString(),
               forAnnotationValue(entry.getValue(), entry.getKey()));
      }
      Class<?> annotationType =
            forElement((TypeElement) annotation.getAnnotationType().asElement());
      annotationValues.put("annotationType", annotationType);
      return (Annotation) Proxy.newProxyInstance(annotationType.getClassLoader(),
            new Class<?>[] { annotationType },
            (proxy, method, args) -> {
               if (method.getName().equals("equals") && args != null && args.length == 1) {
                  // TODO
                  return false;
               } else if (method.getName().equals("hashCode")
                     && (args == null || args.length == 0)) {
                  // TODO
                  return 1;
               } else if (method.getName().equals("toString")
                     && (args == null || args.length == 0)) {
                  // TODO
                  return "";
               } else if (args != null && args.length > 0) {
                  throw new AssertionError("Unexpected method call: " + method);
               } else {
                  Object value = annotationValues.get(method.getName());
                  if (value == null) {
                     throw new AssertionError("Unexpected method call: " + method);
                  }
                  return value;
               }
            });
   }

   public Object forAnnotationValue(AnnotationValue value) {
      return forAnnotationValue(value, null);
   }

   public Object forAnnotationValue(AnnotationValue value, ExecutableElement method) {
      return value.accept(new SimpleAnnotationValueVisitor8<Object, Void>() {
         @Override
         protected Object defaultAction(Object o, Void p) {
            return o;
         }

         @Override
         public Object visitType(TypeMirror t, Void p) {
            return (Class<?>) forTypeMirror(t);
         }

         @Override
         public Object visitEnumConstant(VariableElement c, Void p) {
            Field f = forFieldElement(c);
            f.setAccessible(true);
            try {
               return f.get(null);
            } catch (NullPointerException | IllegalArgumentException | IllegalAccessException e) {
               throw new AssertionError("Could not get enum constant value", e);
            }
         }

         @Override
         public Object visitAnnotation(AnnotationMirror a, Void p) {
            return forAnnotationMirror(a);
         }

         @Override
         public Object visitArray(List<? extends AnnotationValue> vals, Void p) {
            Object array;
            if (method != null) {
               array = Array.newInstance(getMethodReturnComponentType(method), vals.size());
            } else {
               array = null;
            }
            int i = 0;
            for (AnnotationValue val : vals) {
               Object v = forAnnotationValue(val);
               if (array == null) {
                  array = Array.newInstance(getValueType(v), vals.size());
               }
               Array.set(array, i++, v);
            }
            return array != null ? array : new Object[0];
         }
         
         private Class<?> getMethodReturnComponentType(ExecutableElement method) {
            assert method.getReturnType().getKind() == TypeKind.ARRAY;
            return rawType(forTypeMirror(((ArrayType) method.getReturnType()).getComponentType()));
         }
         
         private Class<?> getValueType(Object v) {
            if (v instanceof Boolean) {
               return boolean.class;
            } else if (v instanceof Byte) {
               return byte.class;
            } else if (v instanceof Character) {
               return char.class;
            } else if (v instanceof Short) {
               return short.class;
            } else if (v instanceof Integer) {
               return int.class;
            } else if (v instanceof Long) {
               return long.class;
            } else if (v instanceof Float) {
               return float.class;
            } else if (v instanceof Double) {
               return double.class;
            } else {
               assert v instanceof Class || v instanceof Annotation || v.getClass().isArray();
               return v.getClass();
            }
         }
      }, null);
   }
}