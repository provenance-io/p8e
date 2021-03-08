package io.p8e.proxy

import com.google.protobuf.Message
import io.p8e.spec.P8eContract


@FunctionalInterface
interface Function<OUT: Message, IN: P8eContract> {
    fun apply(t: IN): OUT
}

@FunctionalInterface
interface Function1<OUT: Message, IN: P8eContract, ARG1> {
    fun apply(t: IN, arg: ARG1?): OUT
}

@FunctionalInterface
interface Function2<OUT: Message, IN: P8eContract, ARG1, ARG2> {
    fun apply(t: IN, arg1: ARG1?, arg2: ARG2?): OUT
}

@FunctionalInterface
interface Function3<OUT: Message, IN: P8eContract, ARG1, ARG2, ARG3> {
    fun apply(t: IN, arg1: ARG1?, arg2: ARG2?, arg3: ARG3?): OUT
}

@FunctionalInterface
interface Function4<OUT: Message, IN: P8eContract, ARG1, ARG2, ARG3, ARG4> {
    fun apply(t: IN, arg1: ARG1?, arg2: ARG2?, arg3: ARG3?, arg4: ARG4?): OUT
}

@FunctionalInterface
interface Function5<OUT: Message, IN: P8eContract, ARG1, ARG2, ARG3, ARG4, ARG5> {
    fun apply(t: IN, arg1: ARG1?, arg2: ARG2?, arg3: ARG3?, arg4: ARG4?, arg5: ARG5?): OUT
}

@FunctionalInterface
interface Function6<OUT: Message, IN: P8eContract, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6> {
    fun apply(t: IN, arg1: ARG1?, arg2: ARG2?, arg3: ARG3?, arg4: ARG4?, arg5: ARG5?, arg6: ARG6?): OUT
}

@FunctionalInterface
interface Function7<OUT: Message, IN: P8eContract, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6, ARG7> {
    fun apply(t: IN, arg1: ARG1?, arg2: ARG2?, arg3: ARG3?, arg4: ARG4?, arg5: ARG5?, arg6: ARG6?, arg7: ARG7?): OUT
}