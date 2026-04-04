package ru.ilyubarskiy.mai.mission_service.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.grpc.Status
import io.grpc.StatusException
import org.slf4j.LoggerFactory
import org.springframework.grpc.server.exception.GrpcExceptionHandler
import org.springframework.grpc.server.service.GrpcService
import org.springframework.stereotype.Component
import ru.ilyubarskiy.mai.generated.*
import ru.ilyubarskiy.mai.mission_service.repository.MissionRepository
import java.util.*

private val logger = LoggerFactory.getLogger(MissionGrpcService::class.java)

@GrpcService
class MissionGrpcService(
    private val missionRepository: MissionRepository
) : MissionServiceGrpcKt.MissionServiceCoroutineImplBase() {

    private val mapper = jacksonObjectMapper()

    override suspend fun getByOrderId(request: GetByOrderIdRequest): GetByOrderIdResponse {
        val mission = missionRepository.findByOrderId(request.orderId)
            ?: return GetByOrderIdResponse.newBuilder().setFound(false).build()

        return GetByOrderIdResponse.newBuilder()
            .setFound(true)
            .setMissionId(mission.id.toString())
            .setRoverId(mission.roverId.toString())
            .setStatus(mission.status)
            .setPayloadJson(mapper.writeValueAsString(mission.payload))
            .build()
    }

    override suspend fun getByRoverId(request: GetByRoverIdRequest): GetByRoverIdResponse {

        val mission = missionRepository.getActiveByRoverId(UUID.fromString(request.roverId)).firstOrNull()
            ?: return GetByRoverIdResponse.newBuilder().setFound(false).build()

        return GetByRoverIdResponse.newBuilder()
            .setFound(true)
            .setMissionId(mission.id.toString())
            .setRoverId(mission.roverId.toString())
            .setStatus(mission.status)
            .setPayloadJson(mapper.writeValueAsString(mission.payload))
            .build()

    }
}

@Component
class GlobalGrpcExceptionHandler : GrpcExceptionHandler {

    override fun handleException(e: Throwable): StatusException? {
        logger.error("gRPC Error: ${e.message}", e)

        return Status.INTERNAL
            .withDescription(e.message ?: "Internal server error")
            .withCause(e)
            .asException()
    }
}