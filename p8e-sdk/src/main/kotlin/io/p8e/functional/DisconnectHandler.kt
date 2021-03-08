package io.p8e.functional

@FunctionalInterface
interface DisconnectHandler {
    fun handle(reconnectHandler: ReconnectHandler)
}
