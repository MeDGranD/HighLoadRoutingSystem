package ru.ilyubarskiy.mai.planner_global.domen

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.*

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
interface KafkaEventMission

data class MissionSetEvent(
    val payload: MissionPayload
): KafkaEventMission

data class MissionStatusUpdateEvent(
    val missionId: UUID,
    val status: String
): KafkaEventMission

data class MissionUpdateEvent(
    val missionId: UUID,
    val payload: MissionPayload
): KafkaEventMission