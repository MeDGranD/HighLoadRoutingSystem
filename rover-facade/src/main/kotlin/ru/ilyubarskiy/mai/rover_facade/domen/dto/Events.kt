package ru.ilyubarskiy.mai.rover_facade.domen.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

data class RoverTelemetryPayload(
    val roverId: UUID,
    val lat: Double,
    val lon: Double,
    val battery: Double,
    val timestamp: Long
)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = RoverOrderEventPayload::class, name = "ORDER"),
    JsonSubTypes.Type(value = RoverMissionEventPayload::class, name = "MISSION"),
)
interface Event

data class RoverOrderEventPayload(
    val roverId: UUID,
    val action: String,
    val orderId: UUID?,
    val timestamp: Long
): Event

data class RoverMissionEventPayload(
    val roverId: UUID,
    val missionId: UUID,
    val status: String
): Event

data class OrderRoverEvent(
    val orderId: UUID,
    val status: OrderStatus
)

enum class OrderStatus(val value: String) {

    ROUTING("ROUTING"),
    FAILED("FAILED"),
    IN_PROGRESS("IN_PROGRESS"),
    CANCELED("CANCELED"),
    DONE("DONE")

}

data class RoverStatusEvent(
    val roverId: UUID,
    val status: RoverStatus,
)

enum class RoverStatus(val value: String) {

    ON_MISSION("ON_MISSION"),
    DEAD("DEAD"),
    FREE("FREE"),
    CHARGING("CHARGING")

}

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = MissionStatusUpdateEvent::class, name = "UPDATE_STATUS"),
)
interface KafkaEvent


data class MissionStatusUpdateEvent(
    val missionId: UUID,
    val status: String
): KafkaEvent