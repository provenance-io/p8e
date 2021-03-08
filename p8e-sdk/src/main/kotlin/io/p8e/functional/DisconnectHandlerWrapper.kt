package io.p8e.functional

import io.p8e.ContractManager.WatchBuilder
import io.p8e.spec.P8eContract

class DisconnectHandlerWrapper<T: P8eContract>(
    private val disconnectHandler: DisconnectHandler,
    private val watchBuilder: WatchBuilder<T>
) {

    fun handle() {
        disconnectHandler.handle(
            object: ReconnectHandler {
                override fun reconnect() {
                    watchBuilder.watch()
                }
            }
        )
    }

}
