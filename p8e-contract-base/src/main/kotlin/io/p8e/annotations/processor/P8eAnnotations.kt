package io.p8e.annotations.processor

import io.p8e.annotations.Prerequisite
import io.p8e.annotations.Function
import io.p8e.annotations.Fact
import io.p8e.annotations.Input
import io.p8e.annotations.Participants

enum class P8eAnnotations(val clazz: Class<out Annotation>) {
    PARTICIPANT(Participants::class.java),
    FUNCTION(Function::class.java),
    PREREQUISITE(Prerequisite::class.java),
    FACT(Fact::class.java),
    INPUT(Input::class.java)
}
