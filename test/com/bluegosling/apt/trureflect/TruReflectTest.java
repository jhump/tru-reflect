package com.bluegosling.apt.trureflect;

import static java.lang.reflect.Modifier.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import org.junit.Test;

import com.bluegosling.apt.trureflect.TruReflect;

public class TruReflectTest {

   @Test public void test() {
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler(); 
      compiler.getStandardFileManager(null, null, null);
      JavaFileObject obj = new JavaFileObject() {
         @Override
         public URI toUri() {
            try {
               return new URI("misc:///TruReflectTest.java");
            } catch (URISyntaxException e) {
               throw new RuntimeException(e);
            }
         }

         @Override
         public String getName() {
            return "TruReflectTest.java";
         }

         @Override
         public InputStream openInputStream() throws IOException {
            return new FileInputStream("/Users/jh/Development/personal/apt-reflect/test/com/apriori/apt/trureflect/TruReflectTest.java");
         }

         @Override
         public OutputStream openOutputStream() throws IOException {
            throw new IllegalStateException();
         }

         @Override
         public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
            return new InputStreamReader(openInputStream());
         }

         @Override
         public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            StringWriter w = new StringWriter();
            Reader r = openReader(ignoreEncodingErrors);
            while (true) {
               char cbuf[] = new char[4096];
               int chars = r.read(cbuf);
               if (chars == -1) break;
               w.write(cbuf, 0, chars);
            }
            return w.toString();
         }

         @Override
         public Writer openWriter() throws IOException {
            throw new IllegalStateException();
         }

         @Override
         public long getLastModified() {
            return 0;
         }

         @Override
         public boolean delete() {
            return false;
         }

         @Override
         public Kind getKind() {
            return Kind.SOURCE;
         }

         @Override
         public boolean isNameCompatible(String simpleName, Kind kind) {
            return simpleName.equals("TruReflectTest");
         }

         @Override
         public NestingKind getNestingKind() {
            return null;
         }

         @Override
         public Modifier getAccessLevel() {
            return null;
         }
      };
      CompilationTask t = compiler.getTask(null, null, null, null, null, Arrays.asList(obj));
      TestProcessor p = new TestProcessor();
      t.setProcessors(Arrays.asList(p));
      t.call();
   }

   @Target(ElementType.TYPE_USE)
   @interface TypeAbc {
   }

   @Target(ElementType.TYPE_USE)
   @interface TypeXyz {
   }

   @SupportedSourceVersion(SourceVersion.RELEASE_8)
   @SupportedAnnotationTypes("*")
   class TestProcessor extends AbstractProcessor {
      @Override
      public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
         TruReflect tru =
               new TruReflect(processingEnv.getElementUtils(), processingEnv.getTypeUtils());
         for (TypeElement e : annotations) {
            Class<?> clz = tru.forElement(e);
            System.out.println("\nAnnotation: " + clz);
            System.out.println("--------------------------");
            System.out.println("// loaded by " + clz.getClassLoader());
            doPrint(clz, 0);
            for (Element element : roundEnv.getElementsAnnotatedWith(e)) {
               AnnotatedElement ae = tru.forElement(element);
               System.out.println("\n  Annotated Element: " + ae);
               System.out.println("  ------------------------------");
               if (ae == TruReflectTest.class) {
                  throw new IllegalStateException("boo!");
               } else if (ae instanceof Class) {
                  System.out.println("  // loaded by " + ((Class<?>) ae).getClassLoader());
               }
               doPrint(ae, 1);
            }
         }
         return true;
      }
   }

   private void doPrint(AnnotatedElement e, int indent) {
      if (e instanceof Class) {
         doPrint((Class<?>) e, indent);
      } else if (e instanceof Field) {
         doPrint((Field) e, indent);
      } else if (e instanceof Constructor) {
         doPrint((Constructor<?>) e, indent);
      } else if (e instanceof Method) {
         doPrint((Method) e, indent);
      } else if (e instanceof Parameter) {
         doPrint((Parameter) e, indent);
      } else if (e instanceof Package) {
         doPrint((Package) e, indent);
      } else if (e instanceof TypeVariable) {
         doPrint((TypeVariable<?>) e, indent);
      } else if (e instanceof AnnotatedType) {
         doPrint((AnnotatedType) e, indent);
      }
   }

   private void doPrint(Field field, int indent) {
      printIndent(indent);
      printModifiers(field.getModifiers() & fieldModifiers());
      // TODO
   }

   private void doPrint(Parameter param, int indent) {
      printIndent(indent);
      doPrint(param);
      System.out.println();
   }

   private void doPrint(Parameter param) {
      printModifiers(param.getModifiers() & parameterModifiers());
      // TODO
   }

   private void doPrint(Method method, int indent) {
      printIndent(indent);
      printModifiers(method.getModifiers() & methodModifiers());
      // TODO
   }

   private void doPrint(Constructor<?> ctor, int indent) {
      printIndent(indent);
      printModifiers(ctor.getModifiers() & constructorModifiers());
      // TODO
   }

   private void doPrint(TypeVariable<?> typeVar, int indent) {
      printIndent(indent);
      doPrint(typeVar);
      System.out.println();
   }

   private void doPrint(TypeVariable<?> typeVar) {
      // TODO
   }

   private void doPrint(Class<?> clazz, int indent) {
      Class<?> enclosing = clazz.getEnclosingClass();
      if (enclosing != null) {
         printIndent(indent);
         System.out.println("// Enclosed in " + enclosing.getName());
      } else {
         Package pkg = clazz.getPackage();
         String pkgName = pkg.getName();
         if (!pkgName.isEmpty()) {
            if (pkg.getDeclaredAnnotations().length > 0) {
               printIndent(indent);
               System.out.print("// @...");
            }
            printIndent(indent);
            System.out.println("package " + pkgName + ";");
         }
      }
      for (Annotation a : clazz.getDeclaredAnnotations()) {
         printIndent(indent);
         System.out.println(a);
      }
      printIndent(indent);
      printModifiers(clazz.getModifiers() & classModifiers());
      if (clazz.isAnnotation()) {
         System.out.print("@interface ");
      } else if (clazz.isEnum()) {
         System.out.print("enum ");
      } else if (clazz.isInterface()) {
         System.out.print("interface ");
      } else {
         System.out.print("class ");
      }
      System.out.print(clazz.getSimpleName());
      TypeVariable<?> vars[] = clazz.getTypeParameters();
      if (vars.length > 0) {
         System.out.print("<");
         boolean first = true;
         for (TypeVariable<?> var : vars) {
            if (first) { first = false; } else { System.out.print(", "); }
            doPrint(var);
         }
         System.out.print(">");
      }
      if (!clazz.isInterface()) {
         System.out.print(" extends " + clazz.getGenericSuperclass().getTypeName());
      }
      Type ifaces[] = clazz.getInterfaces();
      if (ifaces.length > 0) {
         System.out.print(" implements ");
         boolean first = true;
         for (Type iface : ifaces) {
            if (first) { first = false; } else { System.out.print(", "); }
            System.out.print(iface.getTypeName());
         }
      }
      System.out.println(" {");
      // TODO
      printIndent(indent);
      System.out.println("}");
   }

   private void doPrint(Package pkg, int indent) {
      for (Annotation a : pkg.getDeclaredAnnotations()) {
         printIndent(indent);
         System.out.println(a);
      }
      printIndent(indent);
      System.out.println("package " + pkg.getName() + ";");
   }

   private void printIndent(int indent) {
      while (indent-- > 0) {
         System.out.print("  ");
      }
   }
   
   private void printModifiers(int mods) {
      if (isPrivate(mods)) {
         System.out.print("private ");
      }
      if (isProtected(mods)) {
         System.out.print("protected ");
      }
      if (isPublic(mods)) {
         System.out.print("public ");
      }
      if (isStatic(mods)) {
         System.out.print("static ");
      }
      if (isAbstract(mods)) {
         System.out.print("abstract ");
      }
      if (isFinal(mods)) {
         System.out.print("final ");
      }
      if (isNative(mods)) {
         System.out.print("native ");
      }
      if (isStrict(mods)) {
         System.out.print("strictfp ");
      }
      if (isSynchronized(mods)) {
         System.out.print("synchronized ");
      }
      if (isTransient(mods)) {
         System.out.print("transient ");
      }
      if (isVolatile(mods)) {
         System.out.print("volatile ");
      }
   }
}
