package ru.ilyubarskiy.mai.rover_service.service

import io.grpc.Status
import io.grpc.StatusException
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.grpc.server.exception.GrpcExceptionHandler
import org.springframework.grpc.server.service.GrpcService
import org.springframework.stereotype.Component
import ru.ilyubarskiy.mai.generated.*
import ru.ilyubarskiy.mai.rover_service.repository.RoverModelRepository
import ru.ilyubarskiy.mai.rover_service.repository.RoverRepository
import ru.ilyubarskiy.mai.rover_service.repository.dto.RoverTelemetry
import ru.ilyubarskiy.mai.rover_service.repository.entity.RoverStatus
import java.util.UUID

private val logger = LoggerFactory.getLogger(RoverGrpcService::class.java)

@GrpcService
class RoverGrpcService(
    private val roverRepository: RoverRepository,
    private val roverModelRepository: RoverModelRepository,
    private val redisTemplate: RedisTemplate<String, RoverTelemetry>
): RoverServiceGrpcKt.RoverServiceCoroutineImplBase() {

    private val TELEMETRY_KEY_PREFIX = "rover:telemetry:"

    override suspend fun getRoverStatus(request: RoverStatusRequest): RoverStatusResponse {

        val roverId = UUID.fromString(request.roverId)
        val roverEntity = roverRepository.findById(roverId).orElseThrow {
            Status.NOT_FOUND.withDescription("Rover with id: $roverId not found").asException()
        }
        val model = roverModelRepository.findById(roverEntity.modelId)

        val telemetry = redisTemplate.opsForValue().get(TELEMETRY_KEY_PREFIX + roverId )

        val modelResponse = RoverModel.newBuilder()
            .setId(model.get().id.toString())
            .setCapacity(model.get().capacity)
            .setAvgSpeed(model.get().avgSpeed)
            .build()
        return RoverStatusResponse.newBuilder()
            .setRoverId(roverId.toString())
            .setStatus(roverEntity.status.value)
            .setLat(telemetry?.lat ?: roverEntity.lastSeenLat)
            .setLon(telemetry?.lon ?: roverEntity.lastSeenLon)
            .setModel(modelResponse)
            .setBatteryLevel(telemetry?.battery ?: roverEntity.lastSeenBattery)
            .setLastUpdateTs(telemetry?.timestamp ?: roverEntity.lastSeen.toEpochMilli())
            .build()

    }

    override suspend fun getFreeRovers(request: FreeRoversRequest): FreeRoversResponse {

        val bbox = request.bbox

        val availableRovers = roverRepository.findAllInStatus(listOf(RoverStatus.FREE, RoverStatus.ON_MISSION).map { it.value })

        val response = availableRovers.mapNotNull { rover ->
            val telemetry = redisTemplate.opsForValue().get(TELEMETRY_KEY_PREFIX + rover.id)

            val currentLat = telemetry?.lat ?: rover.lastSeenLat
            val currentLon = telemetry?.lon ?: rover.lastSeenLon

            val isInsideBbox = currentLat >= bbox.minLat && currentLat <= bbox.maxLat &&
                    currentLon >= bbox.minLon && currentLon <= bbox.maxLon

            if (!isInsideBbox) {
                return@mapNotNull null
            }

            val model = roverModelRepository.findById(rover.modelId)
            val modelResponse = RoverModel.newBuilder()
                .setId(model.get().id.toString())
                .setCapacity(model.get().capacity)
                .setAvgSpeed(model.get().avgSpeed)
                .build()

            RoverStatusResponse.newBuilder()
                .setRoverId(rover.id.toString())
                .setLat(telemetry?.lat ?: rover.lastSeenLat)
                .setLon(telemetry?.lon ?: rover.lastSeenLon)
                .setBatteryLevel(telemetry?.battery ?: rover.lastSeenBattery)
                .setStatus(rover.status.value)
                .setModel(modelResponse)
                .setLastUpdateTs(telemetry?.timestamp ?: rover.lastSeen.toEpochMilli())
                .build()
        }
        return FreeRoversResponse.newBuilder().addAllRovers(response).build()
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