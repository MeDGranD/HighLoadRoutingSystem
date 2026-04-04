package ru.ilyubarskiy.mai.planner_global.service.client

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import ru.ilyubarskiy.mai.generated.RoutingServiceGrpcKt
import java.util.concurrent.ConcurrentHashMap

@Component
class GrpcClientManager{
    private val channels = ConcurrentHashMap<Int, ManagedChannel>()
    private val stubs = ConcurrentHashMap<Int, RoutingServiceGrpcKt.RoutingServiceCoroutineStub>()

    fun getStubForRegion(regionId: Int): RoutingServiceGrpcKt.RoutingServiceCoroutineStub {
        return stubs.getOrPut(regionId) {
            val host = "routing-service-$regionId-routing-service"

            val channel = ManagedChannelBuilder.forAddress(host, 9090)
                .usePlaintext()
                .build()

            channels[regionId] = channel
            RoutingServiceGrpcKt.RoutingServiceCoroutineStub(channel)
        }
    }

    @PreDestroy
    fun shutdown() {
        channels.values.forEach { it.shutdown() }
    }
}