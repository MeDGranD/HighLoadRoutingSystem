package ru.ilyubarskiy.mai.planner_global.configuration

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import jakarta.annotation.PreDestroy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.ilyubarskiy.mai.generated.MissionServiceGrpcKt
import ru.ilyubarskiy.mai.generated.RoverServiceGrpcKt

@Configuration
class GrpcConfiguration {

    @Bean(destroyMethod = "shutdown")
    fun missionChannel(): ManagedChannel =
        ManagedChannelBuilder.forAddress("mission-service-mission-service", 9090)
            .usePlaintext()
            .build()

    @Bean
    fun missionStub(missionChannel: ManagedChannel): MissionServiceGrpcKt.MissionServiceCoroutineStub =
        MissionServiceGrpcKt.MissionServiceCoroutineStub(missionChannel)


    @Bean(destroyMethod = "shutdown")
    fun roverChannel(): ManagedChannel =
        ManagedChannelBuilder.forAddress("rover-service-rover-service", 9090)
            .usePlaintext()
            .build()

    @Bean
    fun roverStub(roverChannel: ManagedChannel): RoverServiceGrpcKt.RoverServiceCoroutineStub =
        RoverServiceGrpcKt.RoverServiceCoroutineStub(roverChannel)

}