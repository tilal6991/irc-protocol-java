package com.tilal6991.irc

import com.squareup.javapoet.*
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.SourceTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.incremental.IncrementalTaskInputs
import java.io.File
import java.net.URLClassLoader
import javax.annotation.Nonnull
import javax.lang.model.element.Modifier

open class CallbackGenTask : SourceTask() {
  private val SLASH = File.separator

  private val callbackClassName = ClassName.get("com.tilal6991.irc", "MessageCallback")
  private val abstractClassName = ClassName.get("com.tilal6991.irc", "AbstractMessageCallback")
  private val pojoCallbackClassName = ClassName.get("com.tilal6991.irc", "PojoMessageCallback")

  private val canonicalCallbackTypeVariable = TypeVariableName.get("T")
  private val parameterizedCallbackName =
      ParameterizedTypeName.get(callbackClassName, canonicalCallbackTypeVariable)

  @TaskAction
  @Suppress("unused", "UNUSED_PARAMETER")
  fun generate(inputs: IncrementalTaskInputs) {
    val container = project.property("sourceSets") as SourceSetContainer
    val classesDir = container.getByName("main").output.classesDir

    val projectDir = project.projectDir.absolutePath.removeSuffix("-core")
    val output = File("$projectDir${SLASH}src${SLASH}main${SLASH}java")

    val loader = URLClassLoader.newInstance(
        arrayOf(classesDir.toURI().toURL()), Nonnull::class.java.classLoader)

    val name = NameGenerator(loader.loadClass(callbackClass("NamesParser")))

    val code = CodeGenerator(
        loader.loadClass(callbackClass("CodeParser")), ClassName.get(name.klass.enclosingClass))

    val argument = ArgumentGenerator(
        loader.loadClass(callbackClass("ArgumentParser")), ClassName.get(code.klass.enclosingClass))

    val tokenizer = TokenizerGenerator(
        loader.loadClass(callbackClass("MessageTokenizer")),
        ClassName.get(argument.klass.enclosingClass))
    val tokenizerName = ClassName.get(tokenizer.klass.enclosingClass)

    val flattenedCallback = generateFlattenedCallback(argument, code, name)
    JavaFile.builder("com.tilal6991.irc", flattenedCallback).build().writeTo(output)

    val abstractCallback = generateAbstractCallback(flattenedCallback)
    JavaFile.builder("com.tilal6991.irc", abstractCallback).build().writeTo(output)

    val message = generateMessageClasses(tokenizer, argument, code, name)
    JavaFile.builder("com.tilal6991.irc", message).build().writeTo(output)

    val pojo = generatePojoCallback(flattenedCallback)
    JavaFile.builder("com.tilal6991.irc", pojo).build().writeTo(output)

    val parser = generateParser(tokenizerName, tokenizer, argument, code, name)
    JavaFile.builder("com.tilal6991.irc", parser).build().writeTo(output)
  }

  private fun generatePojoCallback(flattenedCallback: TypeSpec): TypeSpec? {
    return TypeSpec.classBuilder(pojoCallbackClassName)
        .addModifiers(Modifier.PUBLIC)
        .addSuperinterface(ParameterizedTypeName.get(callbackClassName, messageClassName))
        .addMethods(
            flattenedCallback.methodSpecs.map {
              val params = it.parameters.map { it.name }.joinToString(", ")
              overriding(it)
                  .returns(messageClassName)
                  .addStatement("return new Message.${it.name.removePrefix("on")}($params)")
                  .build()
            })
        .build()
  }

  private fun generateAbstractCallback(flattenedCallback: TypeSpec): TypeSpec {
    return TypeSpec.classBuilder(abstractClassName)
        .addModifiers(Modifier.PUBLIC)
        .addTypeVariable(canonicalCallbackTypeVariable)
        .addSuperinterface(parameterizedCallbackName)
        .addMethods(
            flattenedCallback.methodSpecs.map {
              overriding(it).addStatement("return null").build()
            })
        .build()
  }

  private fun generateFlattenedCallback(vararg generators: Generator): TypeSpec {
    return TypeSpec.interfaceBuilder(callbackClassName)
        .addModifiers(Modifier.PUBLIC)
        .addMethods(
            generators.flatMap { it.callbackMethods() }
                .sortedBy { it.name })
        .addTypeVariable(canonicalCallbackTypeVariable)
        .build()
  }

  private fun generateMessageClasses(vararg generators: Generator): TypeSpec {
    val constructor = MethodSpec.constructorBuilder()
        .addTokenizerParameters()
        .addTokenizerAssignment()
        .addModifiers(Modifier.PUBLIC)
        .build()

    return TypeSpec.classBuilder(messageClassName)
        .addModifiers(Modifier.PUBLIC)
        .addTokenizerFields()
        .addMethod(constructor)
        .addTypes(generators.flatMap { it.messages() })
        .build()
  }

  private fun generateParser(tokenizer: ClassName, vararg generators: Generator): TypeSpec {
    val innerClassName = ClassName.get("com.tilal6991.irc", "MessageParser", "Inner")
    return outerParserClass(innerClassName, tokenizer)
        .addType(innerParserClass(innerClassName, *generators).build())
        .build()
  }

  private fun innerParserClass(inner: ClassName, vararg generators: Generator): TypeSpec.Builder {
    return TypeSpec.classBuilder(inner)
        .addSuperinterfaces(
            generators.map {
              ParameterizedTypeName.get(ClassName.get(it.klass), canonicalCallbackTypeVariable)
            })
        .addModifiers(Modifier.PRIVATE)
        .addField(STRING_LIST_CLASS, "tags", Modifier.PRIVATE)
        .addField(STRING_CLASS, "prefix", Modifier.PRIVATE)
        .addField(STRING_CLASS, "target", Modifier.PRIVATE)
        .addMethods(generators.flatMap { it.parserMethods() }.sortedBy { it.name })
  }

  private fun outerParserClass(innerClassName: ClassName, tokenizer: ClassName): TypeSpec.Builder {
    val callbackConstructor = MethodSpec.constructorBuilder()
        .addModifiers(Modifier.PUBLIC)
        .addParameter(ParameterSpec.builder(parameterizedCallbackName, "callback")
            .addAnnotation(Nonnull::class.java)
            .build())
        .addStatement("this.callback = callback")
        .addStatement("this.inner = new \$T()", innerClassName)
        .build()

    val parseMethod = MethodSpec.methodBuilder("parse")
        .addModifiers(Modifier.PUBLIC)
        .addParameter(ParameterSpec.builder(STRING_CLASS, "line")
            .addAnnotation(Nonnull::class.java)
            .build())
        .addStatement("return \$T.tokenize(line, inner)", tokenizer)
        .returns(canonicalCallbackTypeVariable)
        .build()

    return TypeSpec.classBuilder(ClassName.get("com.tilal6991.irc", "MessageParser"))
        .addModifiers(Modifier.PUBLIC)
        .addField(parameterizedCallbackName, "callback", Modifier.PRIVATE, Modifier.FINAL)
        .addField(innerClassName, "inner", Modifier.PRIVATE, Modifier.FINAL)
        .addMethod(callbackConstructor)
        .addMethod(parseMethod)
        .addTypeVariable(canonicalCallbackTypeVariable)
  }

  private fun callbackClass(outer: String): String {
    return "com.tilal6991.irc.$outer\$Callback"
  }
}