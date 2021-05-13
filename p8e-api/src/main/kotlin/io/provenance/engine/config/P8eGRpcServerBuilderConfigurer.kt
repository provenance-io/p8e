package io.provenance.engine.config

import io.grpc.ServerBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.p8e.grpc.Constant
import io.p8e.util.ThreadPoolFactory
import org.lognet.springboot.grpc.GRpcServerBuilderConfigurer
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit.SECONDS

@Component
class P8eGRpcServerBuilderConfigurer: GRpcServerBuilderConfigurer() {
    override fun configure(serverBuilder: ServerBuilder<*>) {
        serverBuilder.maxInboundMessageSize(Constant.MAX_MESSAGE_SIZE)
            .also {
                val netty = (it as NettyServerBuilder)
                    .executor(ThreadPoolFactory.newFixedThreadPool(40, "grpc-server-%d"))
                    .permitKeepAliveTime(50, SECONDS)
                    .keepAliveTime(60, SECONDS)
                    .keepAliveTimeout(20, SECONDS)
                    .initialFlowControlWindow(NettyServerBuilder.DEFAULT_FLOW_CONTROL_WINDOW)
            }
    }
}
