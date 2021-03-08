package io.p8e.annotations.processor

import com.google.protobuf.Message
import io.p8e.annotations.Fact
import io.p8e.annotations.Input
import io.p8e.annotations.processor.P8eAnnotations.PREREQUISITE
import io.p8e.annotations.processor.P8eAnnotations.FUNCTION
import io.p8e.annotations.processor.P8eAnnotations.FACT
import io.p8e.annotations.processor.P8eAnnotations.INPUT
import io.p8e.annotations.processor.P8eAnnotations.PARTICIPANT
import io.p8e.spec.P8eContract
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedAnnotationTypes
import javax.annotation.processing.SupportedSourceVersion
import javax.lang.model.SourceVersion
import javax.lang.model.SourceVersion.RELEASE_11
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind.METHOD
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.FINAL
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic.Kind.ERROR
import javax.tools.Diagnostic.Kind.NOTE
import javax.tools.Diagnostic.Kind.WARNING

@SupportedAnnotationTypes("io.p8e.annotations.*")
@SupportedSourceVersion(RELEASE_11)
open class ContractProcessor: AbstractProcessor() {

    override fun process(
        annotations: MutableSet<out TypeElement>,
        roundEnv: RoundEnvironment
    ): Boolean {
        val annotationMap = P8eAnnotations.values().map { it.clazz to roundEnv.getElementsAnnotatedWith(it.clazz) }.toMap()
        val elementUtil = processingEnv.elementUtils
        val typeUtil = processingEnv.typeUtils

        // Handle our contracts
        annotationMap[PARTICIPANT.clazz]?.let { elements ->
            elements.forEach { element ->
                handleContract(
                    elementUtil,
                    typeUtil,
                    element
                )
            }
        }

        return false
    }

    override fun getSupportedSourceVersion(): SourceVersion {
        return RELEASE_11
    }

    override fun getSupportedAnnotationTypes(): MutableSet<String> {
        return mutableSetOf("io.p8e.annotations.*")
    }

    private fun info(message: String, element: Element) {
        processingEnv.messager.printMessage(NOTE, message, element)
    }

    private fun warn(message: String, element: Element) {
        processingEnv.messager.printMessage(WARNING, message, element)
    }

    private fun error(message: String, element: Element) {
        processingEnv.messager.printMessage(ERROR, message, element)
    }

    private fun handleContract(
        elements: Elements,
        types: Types,
        element: Element
    ) {
        val p8eContractType = elements.getTypeElement(P8eContract::class.java.name).asType()
        require(types.isAssignable(element.asType(), p8eContractType)) {
            "${element.simpleName} must extend the $p8eContractType abstract class." to element
        }
        require(!element.modifiers.contains(FINAL)) {
            "${element.simpleName} must not be marked final to allow for proxying." to element
        }

        val prerequisites = element.enclosedElements
            .filter { it.kind == METHOD && it.getAnnotation(PREREQUISITE.clazz) != null }
            .map { it as ExecutableElement }
            .also { methods ->
                methods.forEach { method ->
                    handleConsideration(
                        elements,
                        types,
                        method
                    )
                }
            }

        val considerations = element.enclosedElements
            .filter { it.kind == METHOD && it.getAnnotation(FUNCTION.clazz) != null }
            .map { it as ExecutableElement }
            .also { methods ->
                methods.forEach { method ->
                    handleConsideration(
                        elements,
                        types,
                        method
                    )
                }
            }

        val allOutputMethods = (prerequisites + considerations)

        val facts = allOutputMethods
            .mapNotNull { it.getAnnotation(FACT.clazz) as? Fact }
        val factNames = facts.map { it.name }
            .toSet()

        require(factNames.size == facts.size) {
            "${element.simpleName} methods annotated with ${FACT.clazz.name} must have unique names in the annotation." to element
        }
    }

    private fun handleConsideration(
        elements: Elements,
        types: Types,
        element: ExecutableElement
    ) {
        val returnType = element.returnType
        val messageType = elements.getTypeElement(Message::class.java.name).asType()
        require(returnType == null || types.isAssignable(returnType, messageType)) {
            "${ElementUtil.printMethodSignature(element)} return type must implement $messageType." to element
        }

        require(!element.modifiers.contains(Modifier.FINAL)) {
            "${ElementUtil.printMethodSignature(element)} must not be final." to element
        }

        val inputs = element.parameters
            .mapNotNull { it.getAnnotation(INPUT.clazz) as? Input }
        val inputNames = inputs.map { it.name }
            .toSet()

        require(inputNames.size == inputs.size) {
            "${ElementUtil.printMethodSignature(element)} parameters annotated with ${INPUT.clazz.name} must have unique names." to element
        }

    }

    private fun require(condition: Boolean, messageProvider: () -> Pair<String, Element>) {
        if (!condition) {
            val (message, element) = messageProvider()
            error(message, element)
        }
    }
}
