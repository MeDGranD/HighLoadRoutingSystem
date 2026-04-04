package ru.ilyubarskiy.mai.mission_service.repository.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = MissionSetEvent::class, name = "SET"),
    JsonSubTypes.Type(value = MissionStatusUpdateEvent::class, name = "UPDATE_STATUS"),
    JsonSubTypes.Type(value = MissionUpdateEvent::class, name = "UPDATE"),
)
interface KafkaEvent

data class MissionSetEvent(
    val payload: MissionPayload
): KafkaEvent

data class MissionStatusUpdateEvent(
    val missionId: UUID,
    val status: String
): KafkaEvent

data class MissionUpdateEvent(
    val missionId: UUID,
    val payload: MissionPayload
): KafkaEvent