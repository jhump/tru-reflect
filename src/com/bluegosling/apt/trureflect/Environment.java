package com.bluegosling.apt.trureflect;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Represents a processing environment. This provides access to additional helpers in the current
 * environment, beyond what {@link ProcessingEnvironment} provides.
 * 
 * @author Joshua Humphries (jhumphries131@gmail.com)
 */
public class Environment {
   private final Elements elementUtils;
   private final Types typeUtils;
   private final TypeNames typeNameUtils;
   private final Signatures signatureUtils;
   private final Annotations annotationUtils;
   private final TypeAnnotations typeAnnotationUtils;
   
   public Environment(ProcessingEnvironment env) {
      this(env.getElementUtils(), env.getTypeUtils());
   }
   
   public Environment(Elements elementUtils, Types typeUtils) {
      this.elementUtils = elementUtils;
      this.typeUtils = typeUtils;
      this.typeNameUtils = new TypeNames(elementUtils, typeUtils);
      TypeMirror javaLangObject =
            elementUtils.getTypeElement(Object.class.getCanonicalName()).asType();
      this.signatureUtils = new Signatures(javaLangObject, typeUtils, typeNameUtils);
      this.annotationUtils = new Annotations(elementUtils, typeNameUtils);
      this.typeAnnotationUtils = new TypeAnnotations(elementUtils, typeNameUtils, annotationUtils);
   }
   
   public Elements elementUtils() {
      return elementUtils;
   }
   
   public Types typeUtils() {
      return typeUtils;
   }
   
   public TypeNames typeNameUtils() {
      return typeNameUtils;
   }
   
   public Signatures signatureUtils() {
      return signatureUtils;
   }
   
   public Annotations annotationUtils() {
      return annotationUtils;
   }
   
   public TypeAnnotations typeAnnotationUtils() {
      return typeAnnotationUtils;
   }
}
