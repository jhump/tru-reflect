package com.bluegosling.apt.trureflect;

import static org.objectweb.asm.Opcodes.*;

import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.AnnotatedTypeVariable;
import java.lang.reflect.AnnotatedWildcardType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementKindVisitor8;
import javax.lang.model.util.SimpleElementVisitor8;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.util.ASMifier;
import org.objectweb.asm.util.TraceClassVisitor;

/**
 * A class loader that generates classes based on the elements available in the current processing
 * environment. The classes simply reflect the shape and types of the source elements and do not
 * contain any implementation logic. If an attempt is made to instantiate any of the generated
 * classes or invoke any methods, an {@link UnsupportedOperationException} is thrown.
 * 
 * @see TruReflect
 * 
 * @author Joshua Humphries (jhumphries131@gmail.com)
 */
class TruReflectClassLoader extends ClassLoader {
   
   // We have to reflectively construct packages because ClassLoader provides no other API for
   // defining packages that could also be defined by a parent ClassLoader.
   private static final Constructor<Package> PACKAGE_CTOR;
   static {
      try {
         PACKAGE_CTOR = Package.class.getDeclaredConstructor(String.class, String.class,
               String.class, String.class, String.class, String.class, String.class, URL.class,
               ClassLoader.class);
         PACKAGE_CTOR.setAccessible(true);
      } catch (NoSuchMethodException e) {
         throw new RuntimeException(e);
      }
   }
   
   private static final Pattern ENUM_CTOR_DESC_PATTERN =
         Pattern.compile("\\(Ljava/lang/String;I(Z*)\\)V");
   
   private final Set<String> observedClassNames = new HashSet<>();
   private final Map<String, TypeElement> typeElements = new HashMap<>(); 
   private final Map<TypeElement, String> classNamesByElement = new HashMap<>(); 
   private final Map<String, PackageElement> packageElements = new HashMap<>();
   private final Map<String, Package> packages = new HashMap<>();
   private final Environment env;
   
   TruReflectClassLoader(Environment env) {
      this.env = env;
   }
   
   @Override protected synchronized Class<?> loadClass(String name, boolean resolve)
         throws ClassNotFoundException {
      Class<?> c = super.loadClass(name, false);
      if (observedClassNames.add(name)) {
         if (c.getClassLoader() != this && !name.startsWith("java.") && !verifyStructure(c)) {
            // Class provided by parent class loader doesn't match elements, so synthesize one
            c = findClass(name);
         }
      }
      if (resolve) {
         resolveClass(c);
      }
      return c;
   }
   
   private synchronized boolean verifyStructure(Class<?> clazz) {
      Element e = findElement(clazz.getName());
      return e instanceof PackageElement
            ? verifyPackageStructure(clazz, (PackageElement) e)
            : verifyTypeStructure(clazz, (TypeElement) e);
   }
   
   private boolean verifyPackageStructure(Class<?> pkgInfoClass, PackageElement element) {
      // TODO
      return false;      
   }

   private boolean verifyTypeStructure(Class<?> clazz, TypeElement element) {
      // TODO
      return false;      
   }

   @Override protected synchronized Class<?> findClass(String name) throws ClassNotFoundException {
      Element e = findElement(name);
      if (e == null) {
         throw new ClassNotFoundException(name);
      }
      byte classBytes[] = e instanceof PackageElement
            ? createPackageInfo(name, (PackageElement) e)
            : createClass(name, (TypeElement) e);
      return defineClass(name, classBytes, 0, classBytes.length);
   }
   
   private String mapType(TypeElement e) {
      String className = env.elementUtils().getBinaryName(e).toString();
      mapClassName(className, e);
      return className;
   }

   private byte[] createPackageInfo(String name, PackageElement element) {
      ClassWriter writer = new ClassWriter(0);
      // Class header
      writer.visit(V1_8, ACC_INTERFACE | ACC_ABSTRACT | ACC_SYNTHETIC, name.replace('.', '/'),
            null, "java/lang/Object", new String[0]);
      // Annotations
      for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
         env.annotationUtils().recordAnnotation(writer, mirror);
      }
      writer.visitEnd();
      return writer.toByteArray();
   }
   
   private byte[] createClass(String name, TypeElement element) {
      // TODO: refactor this behemoth!!!
      
      PackageElement pkg = env.elementUtils().getPackageOf(element);
      ensurePackageDefined(pkg.getQualifiedName().toString(), pkg);
      ClassWriter writer = new ClassWriter(0);
      boolean isInterface = element.getKind().isInterface();
      boolean isEnum = element.getKind() == ElementKind.ENUM;
      String typeDescriptor = env.typeNameUtils().getDescriptor(element.asType());
      String internalName = env.typeNameUtils().getInternalName(element);
      // Crawl elements and map all referenced types. Also records all referenced inner types and,
      // if this is an enum, records properties we need to know to possibly synthesize methods.
      Map<Integer, ExecutableElement> enumConstructors = new HashMap<>();
      List<String> enumConstants = new ArrayList<>();
      class EnumProperties {
         boolean hasBaseConstructor;
         boolean hasVisibleConstructor;
         int numParametersForUsableConstructor;
         boolean hasClInit;
         boolean hasValues;
         boolean hasValueOf;
         boolean hasAbstractMethods;
      }
      EnumProperties enumProps = new EnumProperties();
      HashMap<String, TypeElement> innerClasses = new HashMap<>();
      class TypeScanner extends SimpleElementVisitor8<Void, Void> {
         @Override
         public Void visitType(TypeElement e, Void p) {
            String className = mapType(e);
            if (e.getNestingKind().isNested()) {
               // save all inner classes for later
               innerClasses.put(className.replace('.', '/'), e);
            }
            return null;
         }
         
         void visitTypeMirror(TypeMirror type) {
            Element element = env.typeUtils().asElement(type);
            if (element != null) {
               element.accept(this, null);
            }
         }

         @Override
         public Void visitVariable(VariableElement e, Void p) {
            if (e.getKind() == ElementKind.ENUM_CONSTANT) {
               enumConstants.add(e.getSimpleName().toString());
            }
            visitTypeMirror(e.asType());
            return null;
         }

         @Override
         public Void visitExecutable(ExecutableElement e, Void p) {
            if (isEnum) {
               if (e.getModifiers().contains(Modifier.ABSTRACT)) {
                  enumProps.hasAbstractMethods = true;
               }
               String methodName = e.getSimpleName().toString();
               if (e.getKind() == ElementKind.STATIC_INIT) {
                  enumProps.hasClInit = true;  
               } else if (e.getKind() == ElementKind.CONSTRUCTOR) {
                  String consDescriptor = env.typeNameUtils().getDescriptor(e);
                  Matcher m = ENUM_CTOR_DESC_PATTERN.matcher(consDescriptor);
                  if (m.matches()) {
                     enumConstructors.put(m.group(1).length(), e);
                  }
               } else if (methodName.equals("values") && e.getParameters().isEmpty()) {
                  enumProps.hasValues = true;
               } else if (methodName.equals("valueOf")
                     && env.typeNameUtils().getDescriptor(e).equals("(Ljava/lang/String;)"
                           + typeDescriptor)) {
                  enumProps.hasValueOf = true;
               }
            }
            
            e.getTypeParameters().forEach(typeParam -> visitTypeParameter(typeParam, null));
            visitTypeMirror(e.getReturnType());
            e.getParameters().forEach(param -> visitVariable(param, null));
            e.getThrownTypes().forEach(this::visitTypeMirror);
            return null;
         }
         
         @Override
         public Void visitTypeParameter(TypeParameterElement e, Void p) {
            e.getBounds().forEach(this::visitTypeMirror);
            return null;
         }
      };
      TypeScanner scanner = new TypeScanner();
      // scan all elements in this class
      for (Element e : element.getEnclosedElements()) {
         e.accept(scanner, null);
      }
      if (isEnum) {
         ExecutableElement ctor = enumConstructors.get(0);
         enumProps.hasBaseConstructor = ctor != null;
         if (!enumProps.hasAbstractMethods) {
            // no abstract methods? then base constructor is visible (even if private, since it's
            // only needed by this class)
            enumProps.hasVisibleConstructor = enumProps.hasBaseConstructor;
            enumProps.numParametersForUsableConstructor = 2;
         } else {
            // if it has abstract methods, we need to determine a constructor signature
            // we can use for synthesized sub-class to invoke
            if (ctor != null && !ctor.getModifiers().contains(Modifier.PRIVATE)) {
               enumProps.hasVisibleConstructor = true;
               enumProps.numParametersForUsableConstructor = 2;
            }
            if (!enumProps.hasVisibleConstructor) {
               // try to find a visible constructor
               int foundCtorArity = -1;
               for (int numParams = 1; numParams < 256; numParams++) {
                  ExecutableElement e = enumConstructors.get(numParams);
                  if (e == null || !e.getModifiers().contains(Modifier.PRIVATE)) {
                     // If it doesn't exist, we can synthesize it. If it does exist
                     // and isn't private, then it's visible, and we can generate the
                     // impl. Either way, this is the one.
                     enumProps.hasVisibleConstructor = e != null;
                     enumProps.numParametersForUsableConstructor = foundCtorArity = numParams;
                     break;
                  }
               }
               if (foundCtorArity == -1) {
                  throw new IllegalStateException(
                        "No signature could be formulated for a visible constructor for "
                              + internalName);
               }
            }
         }
      }
      
      // Class header
      int modifiers = computeModifierFlags(element.getModifiers());
      if (!isInterface) {
         modifiers |= ACC_SUPER;
      }
      switch (element.getKind()) {
         case ANNOTATION_TYPE:
            modifiers |= ACC_ANNOTATION;
            // intentional fall-through
         case INTERFACE:
            modifiers |= ACC_INTERFACE | ACC_ABSTRACT;
            break;
         case ENUM:
            modifiers |= ACC_ENUM;
            if (!enumProps.hasAbstractMethods) {
               modifiers |= ACC_FINAL;               
            }
            break;
         default:
            // no extra modifiers
      }
      // scan the element's type hierarchy
      scanner.visitTypeMirror(element.getSuperclass());
      for (TypeMirror iface : element.getInterfaces()) {
         scanner.visitTypeMirror(iface);
      }
      TypeMirror superclass = isInterface
            ? env.elementUtils().getTypeElement("java.lang.Object").asType()
            : element.getSuperclass();
      List<? extends TypeMirror> interfaces = element.getInterfaces();
      writer.visit(V1_8, modifiers, internalName,
            env.signatureUtils().getClassSignature(element),
            env.typeNameUtils().getInternalName(superclass),
            interfaces.stream()
                  .map(mirror -> env.typeNameUtils().getInternalName(mirror))
                  .toArray(sz -> new String[sz]));
      // Class Annotations
      for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
         env.annotationUtils().recordAnnotation(writer, mirror);
         // scan annotations
         scanner.visitTypeMirror(mirror.getAnnotationType());
      }
      // Type annotations
      //  - Superclass
      env.typeAnnotationUtils().recordSuperTypeAnnotations(writer, scanner::visitTypeMirror,
            element.getSuperclass(), -1);
      //  - Interfaces
      int i = 0;
      for (TypeMirror interfaceMirror : element.getInterfaces()) {
         env.typeAnnotationUtils().recordSuperTypeAnnotations(writer, scanner::visitTypeMirror,
               interfaceMirror, i++);
      }
      //  - Type variables and bounds
      i = 0;
      for (TypeParameterElement typeVar : element.getTypeParameters()) {
         env.typeAnnotationUtils().recordClassTypeParameterAnnotations(writer,
               scanner::visitTypeMirror, typeVar, i++);
      }
      // Outer Class Info
      Element enclosing = element.getEnclosingElement();
      enclosing.accept(new SimpleElementVisitor8<Void, Void>() {
         @Override
         public Void visitType(TypeElement element, Void p) {
            writer.visitOuterClass(env.typeNameUtils().getInternalName(element), null, null);
            return null;
         }
         
         @Override
         public Void visitExecutable(ExecutableElement element, Void p) {
            writer.visitOuterClass(
                  env.typeNameUtils().getInternalName((TypeElement) element.getEnclosingElement()),
                  element.getSimpleName().toString(),
                  env.typeNameUtils().getDescriptor(element));
            return null;
         }
      }, null);
      // Fields and Methods
      //  - Emit all fields and methods
      for (Element e : element.getEnclosedElements()) {
         e.accept(new ElementKindVisitor8<Void, Void>() {
            @Override
            public Void visitVariableAsEnumConstant(VariableElement e, Void p) {
               return visitVariableAsField(e, p);
            }

            @Override
            public Void visitVariableAsField(VariableElement e, Void p) {
               int access = computeModifierFlags(e.getModifiers());
               if (isInterface) {
                  access |= ACC_PUBLIC | ACC_STATIC;
               }
               // Field declaration
               FieldVisitor visitor = writer.visitField(access,
                     e.getSimpleName().toString(),
                     env.typeNameUtils().getDescriptor(e),
                     env.signatureUtils().getTypeSignature(e.asType()),
                     (access & ACC_STATIC) == 0 ? null : e.getConstantValue());
               // Annotations
               for (AnnotationMirror mirror : e.getAnnotationMirrors()) {
                  env.annotationUtils().recordAnnotation(visitor, mirror);
                  // scan field annotations
                  scanner.visitTypeMirror(mirror.getAnnotationType());
               }
               // Type Annotations
               env.typeAnnotationUtils().recordFieldTypeAnnotations(visitor,
                     scanner::visitTypeMirror, e.asType());
               visitor.visitEnd();
               return null;
            }

            @Override
            public Void visitExecutable(ExecutableElement e, Void p) {
               int access = computeModifierFlags(e.getModifiers());
               if (isInterface) {
                  access |= ACC_PUBLIC;
                  if (!e.getModifiers().contains(Modifier.DEFAULT)) {
                     access |= ACC_ABSTRACT;
                  }
               }
               // Method declaration
               String methodName;
               switch (e.getKind()) {
                  case STATIC_INIT:
                     methodName = "<clinit>";
                     break;
                  case CONSTRUCTOR:
                     methodName = "<init>";
                     break;
                  default:
                     assert e.getKind() == ElementKind.METHOD;
                     methodName = e.getSimpleName().toString();
                     break;
               }
               MethodVisitor visitor = writer.visitMethod(access, methodName,
                     env.typeNameUtils().getDescriptor(e),
                     env.signatureUtils().getMethodSignature(e),
                     e.getThrownTypes().stream()
                        .map(mirror -> env.typeNameUtils().getInternalName(mirror))
                        .toArray(sz -> new String[sz]));
               // Default values for annotation methods
               AnnotationValue defaultValue = e.getDefaultValue();
               if (defaultValue != null) {
                  assert element.getKind() == ElementKind.ANNOTATION_TYPE;
                  AnnotationVisitor av = visitor.visitAnnotationDefault();
                  env.annotationUtils().recordAnnotationValue(av, "", defaultValue);
                  av.visitEnd();
               }
               // Annotations
               for (AnnotationMirror mirror : e.getAnnotationMirrors()) {
                  env.annotationUtils().recordAnnotation(visitor, mirror);
                  // scan method annotations
                  scanner.visitTypeMirror(mirror.getAnnotationType());
               }
               // Parameters and parameter annotations
               int i = 0;
               for (VariableElement param : e.getParameters()) {
                  visitor.visitParameter(param.getSimpleName().toString(),
                        computeModifierFlags(param.getModifiers()));
                  for (AnnotationMirror mirror : param.getAnnotationMirrors()) {
                     env.annotationUtils().recordParameterAnnotation(visitor, i, mirror);
                  }
                  i++;
               }
               // Type annotations
               //  - Type variables and bounds
               i = 0;
               for (TypeParameterElement typeVar : e.getTypeParameters()) {
                  env.typeAnnotationUtils().recordMethodTypeParameterAnnotations(visitor,
                        scanner::visitTypeMirror, typeVar, i++);
               }
               //  - Receiver type annotations
               TypeMirror receiverType = e.getReceiverType();
               if (receiverType != null && receiverType.getKind() != TypeKind.NONE) {
                  env.typeAnnotationUtils().recordReceiverTypeAnnotations(visitor,
                        scanner::visitTypeMirror, receiverType);
               }
               //  - Return type annotations
               TypeMirror returnType = e.getReturnType();
               if (returnType.getKind() != TypeKind.VOID) {
                  env.typeAnnotationUtils().recordReturnTypeAnnotations(visitor,
                        scanner::visitTypeMirror, returnType);
               }
               //  - Parameter type annotations
               i = 0;
               for (VariableElement param : e.getParameters()) {
                  env.typeAnnotationUtils().recordParameterTypeAnnotations(visitor,
                        scanner::visitTypeMirror, param.asType(), i++);
               }
               //  - Throws type annotations
               i = 0;
               for (TypeMirror thrownType : e.getThrownTypes()) {
                  env.typeAnnotationUtils().recordExceptionTypeAnnotations(visitor,
                        scanner::visitTypeMirror, thrownType, i++);
               }
               // Code / Method body
               Set<Modifier> modifiers = e.getModifiers();
               if (!modifiers.contains(Modifier.ABSTRACT) && !modifiers.contains(Modifier.NATIVE)) {
                  boolean writeDefaultImpl = true; 
                  if (isEnum) {
                     if (methodName.equals("<clinit>")) {
                        writeEnumClInitImplementation(visitor, enumConstants, internalName,
                              typeDescriptor, enumProps.numParametersForUsableConstructor);
                        writeDefaultImpl = false;
                     } else if (methodName.equals("<init>")
                           && env.typeNameUtils().getDescriptor(e).equals("(Ljava/lang/String;I)V")) {
                        // TODO: generate method body for visible constructor w/ different signature
                        // if needed by concrete sub-class
                        writeEnumBaseConstructorImplementation(visitor,
                              getParameterNames(e.getParameters()), typeDescriptor);
                        writeDefaultImpl = false;
                     } else if (methodName.equals("values") &&
                           env.typeNameUtils().getDescriptor(e).equals("()[" + typeDescriptor)) {
                        writeEnumValuesImplementation(visitor, enumConstants, internalName,
                              typeDescriptor);
                        writeDefaultImpl = false;
                     } else if (methodName.equals("valueOf")
                           && env.typeNameUtils().getDescriptor(e).equals("(Ljava/lang/String;)"
                                 + typeDescriptor)) {
                        assert e.getParameters().size() == 1;
                        writeEnumValueOfImplementation(visitor,
                              e.getParameters().get(0).getSimpleName().toString(), internalName,
                              typeDescriptor);
                        writeDefaultImpl = false;
                     }
                  }
                  if (writeDefaultImpl) {
                     writeDefaultMethodImplementation(visitor, e.getParameters(),
                           e.getModifiers().contains(Modifier.STATIC) ? null : typeDescriptor);
                  }
               }
               visitor.visitEnd();
               return null;
            }
         }, null);
      }
      //  - Synthesize enum methods if necessary
      if (isEnum) {
         if (enumProps.hasAbstractMethods) {
            if (!enumProps.hasVisibleConstructor) {
               // must synthesize a visible constructor
               int countBoolArgs = enumProps.numParametersForUsableConstructor - 2;
               MethodVisitor mv = writer.visitMethod(ACC_SYNTHETIC, "<init>",
                     "(Ljava/lang/String;I" + Collections.nCopies(countBoolArgs, "Z") + ")V", null,
                     null);
               List<String> paramNames =
                     new ArrayList<>(enumProps.numParametersForUsableConstructor);
               paramNames.add("name");
               paramNames.add("ordinal");
               for (int c = 0; c < countBoolArgs; c++) {
                  paramNames.add("p" + (c + 3));
               }
               writeEnumBaseConstructorImplementation(mv, paramNames, typeDescriptor);
               mv.visitEnd();
            }
            // TODO: synthesize concrete sub-class
         }
         if (!enumProps.hasBaseConstructor) {
            MethodVisitor mv =
                  writer.visitMethod(0, "<init>", "(Ljava/lang/String;I)V", null, null);
            writeEnumBaseConstructorImplementation(mv, Arrays.asList("name", "ordinal"),
                  typeDescriptor);
            mv.visitEnd();
         }
         if (!enumProps.hasClInit) {
            MethodVisitor mv = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            writeEnumClInitImplementation(mv, enumConstants, internalName, typeDescriptor,
                  enumProps.numParametersForUsableConstructor);
            mv.visitEnd();
         }
         if (!enumProps.hasValueOf) {
            MethodVisitor mv = writer.visitMethod(ACC_PUBLIC + ACC_STATIC, "valueOf",
                  "(Ljava/lang/String;)" + typeDescriptor, null, null);
            writeEnumValueOfImplementation(mv, "name", internalName, typeDescriptor);
            mv.visitEnd();
         }
         if (!enumProps.hasValues) {
            MethodVisitor mv = writer.visitMethod(ACC_PUBLIC + ACC_STATIC, "values",
                  "()[" + typeDescriptor, null, null);
            writeEnumValuesImplementation(mv, enumConstants, internalName, typeDescriptor);
            mv.visitEnd();
         }
      }
      // Inner Class Info
      // (We save this for last since, at this point, we've scanned every type that is referenced
      // from within this type, including annotations and type annotations on fields and methods.)
      for (Entry<String, TypeElement> entry : innerClasses.entrySet()) {
         String innerClassInternalName = entry.getKey();
         TypeElement innerClass = entry.getValue();
         TypeElement outerClass = null;
         for (Element e = innerClass.getEnclosingElement(); e != null;
               e = e.getEnclosingElement()) {
            outerClass = e.accept(new SimpleElementVisitor8<TypeElement, Void>() {
               public TypeElement visitType(TypeElement e, Void p) {
                  return e;
               }
            }, null);
            if (outerClass != null) {
               break;
            }
         }
         assert outerClass != null;
         writer.visitInnerClass(innerClassInternalName,
               env.typeNameUtils().getInternalName(outerClass),
               innerClass.getSimpleName().toString(),
               computeModifierFlags(innerClass.getModifiers()));
      }
      // Done!
      writer.visitEnd();
      return writer.toByteArray();
   }
   
   private int computeModifierFlags(Set<Modifier> modifiers) {
      int ret = 0;
      for (Modifier m : modifiers) {
         switch (m) {
            case ABSTRACT:
               ret |= ACC_ABSTRACT;
               break;
            case DEFAULT:
               // indicates absence of ACC_ABSTRACT on interface method
               break;
            case FINAL:
               ret |= ACC_FINAL;
               break;
            case NATIVE:
               ret |= ACC_NATIVE;
               break;
            case PRIVATE:
               ret |= ACC_PRIVATE;
               break;
            case PROTECTED:
               ret |= ACC_PROTECTED;
               break;
            case PUBLIC:
               ret |= ACC_PUBLIC;
               break;
            case STATIC:
               ret |= ACC_STATIC;
               break;
            case STRICTFP:
               ret |= ACC_STRICT;
               break;
            case SYNCHRONIZED:
               ret |= ACC_SYNCHRONIZED;
               break;
            case TRANSIENT:
               ret |= ACC_TRANSIENT;
               break;
            case VOLATILE:
               ret |= ACC_VOLATILE;
               break;
            default:
               throw new AssertionError("Unrecognized modifier: " + m);
         }
      }
      return ret;
   }

   private void writeEnumClInitImplementation(MethodVisitor mv, List<String> enumConstants,
         String internalName, String typeDescriptor, int numParams) {
      mv.visitCode();
      Label scopeEnter = new Label();
      mv.visitLabel(scopeEnter);
      
      StringBuilder sb = new StringBuilder(20 + numParams);
      sb.append("(Ljava/lang/String;I");
      for (int p = 2; p < numParams; p++) {
         sb.append('Z');
      }
      String consDescriptor = sb.append(")V").toString();

      int i = 0;
      for (String enumConst : enumConstants) {
         mv.visitTypeInsn(NEW, internalName);
         mv.visitInsn(DUP);
         mv.visitLdcInsn(enumConst);
         mv.visitIntInsn(BIPUSH, i++);
         for (int p = 2; p < numParams; p++) {
            mv.visitInsn(ICONST_0);
         }
         mv.visitMethodInsn(INVOKESPECIAL, internalName, "<init>", consDescriptor, false);
         mv.visitFieldInsn(PUTSTATIC, internalName, enumConst, typeDescriptor);
      }
      mv.visitInsn(RETURN);
      Label scopeExit = new Label();
      mv.visitLabel(scopeExit);
      mv.visitMaxs(2 + numParams, 0);
   }

   private void writeEnumBaseConstructorImplementation(MethodVisitor mv,
         List<String> parameterNames, String typeDescriptor) {
      mv.visitCode();
      Label scopeEnter = new Label();
      mv.visitLabel(scopeEnter);
      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, 2);
      mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V",
            false);
      mv.visitInsn(RETURN);
      Label scopeExit = new Label();
      mv.visitLabel(scopeExit);
      int i = 0;
      mv.visitLocalVariable("this", typeDescriptor, null, scopeEnter, scopeExit, i++);
      for (String paramName : parameterNames) {
         String paramDescriptor =  i == 1 ? "Ljava/lang/String;" : (i == 2 ? "I" : "Z"); 
         mv.visitLocalVariable(paramName, paramDescriptor, null, scopeEnter, scopeExit, i++);
      }
      mv.visitMaxs(3, parameterNames.size() + 1);
   }

   private void writeEnumValuesImplementation(MethodVisitor mv, List<String> enumConstants,
         String internalName, String typeDescriptor) {
      mv.visitCode();
      Label scopeEnter = new Label();
      mv.visitLabel(scopeEnter);
      mv.visitIntInsn(BIPUSH, enumConstants.size());
      mv.visitTypeInsn(ANEWARRAY, internalName);
      int i = 0;
      for (String enumConst : enumConstants) {
         mv.visitInsn(DUP);
         mv.visitIntInsn(BIPUSH, i++);
         mv.visitFieldInsn(GETSTATIC, internalName, enumConst, typeDescriptor);
         mv.visitInsn(AASTORE);
      }
      mv.visitInsn(ARETURN);
      Label scopeExit = new Label();
      mv.visitLabel(scopeExit);
      mv.visitMaxs(4, 0);
   }

   private void writeEnumValueOfImplementation(MethodVisitor mv, String parameterName,
         String internalName, String typeDescriptor) {
      mv.visitCode();
      Label scopeEnter = new Label();
      mv.visitLabel(scopeEnter);
      
      mv.visitLdcInsn(org.objectweb.asm.Type.getType(typeDescriptor));
      mv.visitVarInsn(ALOAD, 0);
      mv.visitMethodInsn(INVOKESTATIC, "java/lang/Enum", "valueOf",
            "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
      mv.visitTypeInsn(CHECKCAST, internalName);
      mv.visitInsn(ARETURN);      
      Label scopeExit = new Label();
      mv.visitLabel(scopeExit);
      mv.visitLocalVariable(parameterName, "Ljava/lang/String;", null, scopeEnter,
            scopeExit, 0);
      mv.visitMaxs(2, 1);
   }
   
   private void writeDefaultMethodImplementation(MethodVisitor mv,
         List<? extends VariableElement> parameters, String typeDescriptor) {
      mv.visitCode();
      Label scopeEnter = new Label();
      mv.visitLabel(scopeEnter);
      mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
      mv.visitInsn(DUP);
      mv.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "()V",
            false);
      mv.visitInsn(ATHROW);
      Label scopeExit = new Label();
      mv.visitLabel(scopeExit);
      int offset; 
      if (typeDescriptor == null) {
         offset = 0;
      } else {
         mv.visitLocalVariable("this", typeDescriptor, null, scopeEnter, scopeExit, 0);
         offset = 1;
      }
      int i = offset;
      for (VariableElement param : parameters) {
         mv.visitLocalVariable(param.getSimpleName().toString(),
               env.typeNameUtils().getDescriptor(param.asType()), null, scopeEnter, scopeExit, i++);
      }
      mv.visitMaxs(2, parameters.size() + offset);
   }

   private List<String> getParameterNames(List<? extends VariableElement> parameters) {
      return parameters.stream()
            .map(p -> p.getSimpleName().toString())
            .collect(Collectors.toList());
   }
   
   private synchronized Element findElement(String name) {
      int pos = name.lastIndexOf('.');
      String simpleName = pos == -1 ? name : name.substring(pos + 1);
      Element ret;
      if (simpleName.equals("package-info")) {
         String pkgName = pos == -1 ? "" : name.substring(0, pos);
         ret = packageElements.get(pkgName); 
         if (ret == null) {
            throw new IllegalStateException("Cannot load package-info for package "
                  + pkgName + " without associated PackageElement");
         }
      } else {
         ret = typeElements.get(name);
         if (ret == null) {
            throw new IllegalStateException(
                  "Cannot load class " + name + " without associated TypeElement");
         }
      }
      return ret;
   }
   
   synchronized Class<?> loadClass(TypeElement element) {
      String className = classNamesByElement.get(element);
      if (className == null) {
         className = env.elementUtils().getBinaryName(element).toString();
         mapClassName(className, element);
      }
      assert element.equals(typeElements.get(className));
      try {
         return loadClass(className);
      } catch (ClassNotFoundException e) {
         throw new AssertionError("Failed to load class for TypeElement", e);
      }
   }
   
   private synchronized void mapClassName(String name, TypeElement element) {
      TypeElement existing = typeElements.putIfAbsent(name, element);
      if (existing == null) {
         String existingName = classNamesByElement.put(element, name);
         assert existingName == null;
      } else if (!existing.equals(element)) {
         throw new IllegalStateException(
               "Class " + name + " already defined with different TypeElement");
      }
   }
   
   synchronized Package ensurePackageDefined(String name, PackageElement e) {
      PackageElement existing = packageElements.putIfAbsent(name, e);
      if (existing == null) {
         return definePackage(name, "", "", "", "", "", "", null);
      } else if (!existing.equals(e)) {
         throw new IllegalStateException(
               "Package " + name + " already defined with different PackageElement");
      } else {
         return getPackage(name);
      }
   }
   
   @Override protected synchronized Package getPackage(String name) {
      return packages.get(name);
   }

   @Override protected synchronized Package definePackage(String name, String specTitle,
         String specVersion, String specVendor, String implTitle, String implVersion,
         String implVendor, URL sealBase) {
      PackageElement e = packageElements.get(name);
      if (e == null) {
         throw new IllegalStateException(
               "Cannot define package " + name + " without associated PackageElement");
      }
      Package p;
      try {
         p = PACKAGE_CTOR.newInstance(name, specTitle, specVersion, specVendor, implTitle,
               implVersion, implVendor, sealBase, this);
      } catch (Exception ex) {
         if (ex instanceof RuntimeException) {
            throw (RuntimeException) ex;
         }
         throw new RuntimeException(ex);
      }
      packages.put(name, p);
      return p;
   }
   
   

   
   
   
   
   
   
   
   
   @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
   @Retention(RetentionPolicy.RUNTIME)
   @interface Nullable {
   }

   @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
   @Retention(RetentionPolicy.RUNTIME)
   @interface NonNull {
   }
   
   interface Xyz {
      List<String> xyz();
      class B {
      }
   }
   
   static class DefaultXyz implements Xyz {
      static final int x;
      static final int y = 99;
      static final Object A;
      static final Object B;
      static {
         x = 321;
      }
      static {
         A = new Object() { };
         class Efg {
         }
         B = new Efg();
      }
      
      final Object c;
      final Object d;
      
      {
         class Abc {
         }
         d = new Abc();
      }
      
      DefaultXyz() {
         class Hij {
         }
         c = new Hij();
      }

      DefaultXyz(String boo) {
         class Klm {
         }
         c = new Klm();
      }
      
      @Override public List<String> xyz() {
         return null;
      }
      
      void abc() {
         throw new UnsupportedOperationException();
      }
      void abc(Object o1) {
         throw new UnsupportedOperationException();
      }
      void abc(Object o2, String s2) {
         throw new UnsupportedOperationException();
      }
      
      public String name() {
         return null;
      }
      
      public static En[] values() {
         return new En[] { En.ABC, En.DEF, En.GHI, En.JKL, En.MNO, En.PQR, En.STU, En.VW, En.XYZ };
      }
      
      public static En valueOf(String name) {
         return Enum.valueOf(En.class, name);
      }
   }

   class XyzImpl implements Xyz {
      int x;
      {
         x = 123;
      }
      @Override public @Nullable List<@NonNull String> xyz() {
         throw new UnsupportedOperationException();
      }
   }
   
   enum En {
      ABC, DEF, GHI, JKL() {
      }, MNO("abc"), PQR("def") {
      }, STU, VW, XYZ;
      
      En() {
      }
      En(String str) {
      }
      
      void doIt() {
      }
   }
   
   static class EnBase<T extends EnBase<T>> {
      
      private final String name;
      private final int ordinal;
      
      EnBase(String name, int ordinal) {
         this.name = name;
         this.ordinal = ordinal;
      }

      EnBase(String name, int ordinal, boolean b1, boolean b2) {
         this.name = name;
         this.ordinal = ordinal;
      }
      

      public String name() {
         return name;
      }
      
      public int ordinal() {
         return ordinal;
      }
      
      @SuppressWarnings("unchecked")
      public static <T extends EnBase<T>> T valueOf(Class<T> type, String name) {
         T vals[];
         try {
            vals = (T[]) type.getDeclaredMethod("values").invoke(null);
         } catch (Exception e) {
            throw new RuntimeException(e);
         }
         for (T v : vals) {
            if (v.name().equals(name)) {
               return v;
            }
         }
         return null;
      }
   }
   
   static class EnClass extends EnBase<EnClass> {
      public static final EnClass ABC = new EnClass("ABC", 0);
      public static final EnClass DEF = new EnClass("DEF", 1);
      public static final EnClass GHI = new EnClass("GHI", 2);
      public static final EnClass JKL = new EnClass("JKL", 3);
      public static final EnClass MNO = new EnClass("MNO", 4);
      public static final EnClass PQR = new EnClass("PQR", 5);
      public static final EnClass STU = new EnClass("STU", 6);
      public static final EnClass VW = new EnClass("VW", 7);
      public static final EnClass XYZ = new EnClass("XYZ", 8);
      
      EnClass(String name, int ordinal) {
         super(name, ordinal, true, false);
      }
      
      public static EnClass[] values() {
         return new EnClass[] { ABC, DEF, GHI, JKL, MNO, PQR, STU, VW, XYZ };
      }
      
      public static EnClass valueOf(String name) {
         return EnBase.valueOf(EnClass.class, name);
      }
   }
   
   public static void main(String args[]) throws Exception {
      System.out.println(System.getProperty("java.version"));
      class C<@NonNull T, @Nullable M extends @NonNull Map<@Nullable List<@NonNull T>, Number>,
            V2, V3, V4, V5, V6, V7, V8, V9, @Nullable V10,
            V11 extends @NonNull C<T, M, V2, V3, V4, V5, V6, V7, V8, V9, @Nullable V10, @NonNull V11>> {
         Map<List<@NonNull T>, Map<String, Function<@Nullable T, @Nullable Set<@NonNull Number>>>> get() {
            return null;
         }
         
         class D<A, B, C1, D, E, F, G, H, I, J, K,
            L extends C<T, Map<@Nullable List<T>, Number>, V2, V3, V4, V5, V6, V7, V8, V9, @Nullable V10, @NonNull V11>
            .D<A, B, C1, D, E, F, G, H, I, J, @Nullable K, @NonNull L>> {
            
            D(@NonNull C<T, M, V2, V3, V4, V5, V6, V7, V8, V9, V10, V11> C.this) {
            }
            
            class Foo {
               Foo(@Nullable C<T, M, V2, V3, V4, V5, V6, V7, V8, V9, V10, V11>.@NonNull D<A, @Nullable B, C1, @NonNull D, E, F, G, H, I, J, K, L> D.this) {
               }
               
               Foo(String str) {
               }
            }
         }
      }
      class X<T, U> extends @NonNull C<Object, Map<List<Object>, Number>, C, C, C, C, C, C, C, T, U, X<@Nullable T, @NonNull U>> {
         void runIt(@NonNull X<T, U> this, int a) {
         }
      }
      TypePath path;
      ClassVisitor v;
      //v.visitTypeAnnotation(TypeReference.newTypeParameterBoundReference(sort, paramIndex, boundIndex), typePath, desc, visible)
      C x = new X() { };
      
      Constructor cons = C.D.Foo.class.getDeclaredConstructors()[0];
      System.out.println(" First param:");
      System.out.println("getGenericParameterType()[0] => " + cons.getGenericParameterTypes()[0]);
      System.out.println("getParameters()[0].getParameterizedType() => " + cons.getParameters()[0].getParameterizedType());
      System.out.println("getAnnotatedParameterType()[0] => " + toString(cons.getAnnotatedParameterTypes()[0]));
      System.out.println("getParameters()[0].getAnnotatedType() => " + toString(cons.getParameters()[0].getAnnotatedType()));
      System.out.println(" Receiver type:");
      System.out.println("getAnnotatedReceiverType() => " + toString(cons.getAnnotatedReceiverType()));
      
      System.out.println();
      for (Constructor c : C.D.Foo.class.getDeclaredConstructors()) {
         System.out.println(c.toGenericString() + ": " + Arrays.stream(c.getParameters())
               .map(Parameter::getName).collect(Collectors.joining(", ")));
      }
      
      System.out.println();
      //System.out.println(toString(X.class.getAnnotatedSuperclass()));
      //System.out.println();
      //TypePath p = TypePath.fromString("10");
      /*System.out.println(
            Arrays.stream(C.D.Foo.class.getDeclaredConstructors())
                  .map(ctor -> new Object[] { ctor, Arrays.asList(ctor.getParameters()) })
                  .collect(Collectors.toMap(a -> a[0], a -> a[1]))
                  .toString());
      java.lang.reflect.Method m;*/
      
      
      new ClassReader(EnClass.class.getName().replace('.', '/')).accept(
            new TraceClassVisitor(null, new ASMifier(),
                  new PrintWriter(System.out /*new File("/Users/jh/asm-output")*/)), 0);
      
      
      for (Constructor<?> ctor : En.class.getDeclaredConstructors()) {
         System.out.println(ctor);
      }
   }
   
   private static String toString(AnnotatedType t) {
      StringBuilder sb = new StringBuilder();
      toString(t, sb);
      return sb.toString();
   }
   
   private static void toString(AnnotatedType t, StringBuilder sb) {
      if (t instanceof AnnotatedParameterizedType) {
         for (Annotation a : t.getDeclaredAnnotations()) {
            sb.append(a.toString());
            sb.append(' ');
         }
         Type s = t.getType();
         assert s instanceof ParameterizedType || s instanceof Class;
         if (s instanceof ParameterizedType) {
            ParameterizedType p = (ParameterizedType) s;
            Type owner = p.getOwnerType();
            if (owner != null) {
               sb.append(owner.getTypeName());
               sb.append('.');
            }
            sb.append(p.getRawType().getTypeName());
         } else {
            sb.append(s.getTypeName());
         }
         sb.append('<');
         boolean first = true;
         for (AnnotatedType param : ((AnnotatedParameterizedType) t).getAnnotatedActualTypeArguments()) {
            if (first) {
               first = false;
            } else {
               sb.append(", ");
            }
            toString(param, sb);
         }
         sb.append(">");
      } else if (t instanceof AnnotatedArrayType) {
         toString(((AnnotatedArrayType) t).getAnnotatedGenericComponentType(), sb);
         for (Annotation a : t.getDeclaredAnnotations()) {
            sb.append(a.toString());
            sb.append(' ');
         }
         sb.append("[]");
      } else if (t instanceof AnnotatedTypeVariable) {
         for (Annotation a : t.getDeclaredAnnotations()) {
            sb.append(a.toString());
            sb.append(' ');
         }
         TypeVariable<?> tv = (TypeVariable<?>) t.getType();
         sb.append(tv.getName());
         /*sb.append(" extends ");
         for (AnnotatedType)
         ((AnnotatedTypeVariable) t).getAnnotatedBounds()*/
      } else if (t instanceof AnnotatedWildcardType) {
         for (Annotation a : t.getDeclaredAnnotations()) {
            sb.append(a.toString());
            sb.append(' ');
         }
         AnnotatedWildcardType awt = (AnnotatedWildcardType) t;
         AnnotatedType lower[] = awt.getAnnotatedLowerBounds();
         AnnotatedType upper[] = awt.getAnnotatedUpperBounds();
         AnnotatedType bounds[];
         if (lower.length > 0) {
            assert upper.length == 1 && upper[0].getType() == Object.class;
            bounds = lower;
         } else {
            bounds = upper;
         }
         boolean first = true;
         for (AnnotatedType bound : bounds) {
            if (first) {
               first = false;
            } else {
               sb.append(" & ");
            }
            toString(bound, sb);
         }
      } else {
         for (Annotation a : t.getDeclaredAnnotations()) {
            sb.append(a.toString());
            sb.append(' ');
         }
         sb.append(t.getType().getTypeName());
      }
   }
}
