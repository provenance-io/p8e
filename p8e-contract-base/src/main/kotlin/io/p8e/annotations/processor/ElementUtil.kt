package io.p8e.annotations.processor

import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.Name

object ElementUtil {
    fun printMethodSignature(element: ExecutableElement): String {
        val builder = mutableListOf<String>()

        when {
            element.modifiers.contains(Modifier.PUBLIC) -> builder.add(Modifier.PUBLIC.toString())
            element.modifiers.contains(Modifier.PRIVATE) -> builder.add(Modifier.PRIVATE.toString())
            element.modifiers.contains(Modifier.PROTECTED) -> builder.add(Modifier.PROTECTED.toString())
        }

        if (element.modifiers.contains(Modifier.STATIC)) {
            builder.add(Modifier.STATIC.toString())
        }

        if (element.modifiers.contains(Modifier.FINAL)) {
            builder.add(Modifier.FINAL.toString())
        }

        builder.add(element.returnType.toString().withoutPackage())

        val params = element.parameters
            .takeIf { it.isNotEmpty()}
            ?.map { "${it.asType().toString().withoutPackage()} ${it.simpleName.withoutPackage()}" }
            ?.reduce { acc, s -> "$acc, $s" }
            ?: ""

        return "${builder.reduce { acc, s -> "$acc $s"}} ${element.enclosingElement.simpleName}.${element.simpleName.withoutPackage()}($params)"
    }

    private fun Name.withoutPackage(): String {
        return this.toString().withoutPackage()
    }

    private fun String.withoutPackage(): String {
        val indexOf = indexOfFirst(Character::isUpperCase)
        return if (indexOf > -1) {
            substring(indexOf)
        } else {
            this
        }
    }
}