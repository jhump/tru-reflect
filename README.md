# tru-reflect

True Reflect provides the ability to use core reflection APIs, instead of `javax.lang.model` APIs (elements and mirrors), when implementing an annotation processor.

This works by synthesizing classes at runtime. Since the compiler hasn't finished compiling the classes yet, the generated classes have no method implementations and aren't usable to actually instantiate or otherwise interact with the classes. But core reflection APIs can be used to query for annotated elements, annotations, etc.

(Exported from http://code.google.com/p/tru-reflect on 3/21/2015.)
