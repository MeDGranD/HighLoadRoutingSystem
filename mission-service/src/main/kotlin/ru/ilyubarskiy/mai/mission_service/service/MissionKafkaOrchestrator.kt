package ru.ilyubarskiy.mai.mission_service.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.ilyubarskiy.mai.mission_service.repository.MissionRepository
import ru.ilyubarskiy.mai.mission_service.repository.dto.*
import ru.ilyubarskiy.mai.mission_service.repository.entities.MissionEntity
import java.util.*

@Service
class MissionKafkaOrchestrator(
    private val missionRepository: MissionRepository,
) {
    private val mapper = jacksonObjectMapper()

    @KafkaListener(topics = ["mission-event"], groupId = "mission-group")
    @Transactional
    fun consumeMissionEvent(event: String) {

        val kafkaEvent = mapper.readValue(event, KafkaEvent::class.java)
        when(kafkaEvent) {
            is MissionSetEvent -> createMission(kafkaEvent)
            is MissionStatusUpdateEvent -> updateStatus(kafkaEvent)
            is MissionUpdateEvent -> updateMission(kafkaEvent)
        }
    }

    private fun updateMission(event: MissionUpdateEvent) {

        val payload = event.payload
        val entity = MissionEntity(
                id = UUID.fromString(payload.mission_id),
                roverId = UUID.fromString(payload.rover_id),
                status = "IN_PROGRESS",
                payload = payload
            )
        missionRepository.save(entity)
    }

    private fun updateStatus(event: MissionStatusUpdateEvent) {
        missionRepository.updateStatus(event.missionId, event.status)
    }

    private fun createMission(event: MissionSetEvent) {
        val payload = event.payload

        val entity = MissionEntity.createNew {
            MissionEntity(
                id = UUID.fromString(payload.mission_id),
                roverId = UUID.fromString(payload.rover_id),
                status = "IN_PROGRESS",
                payload = payload
            )
        }
        missionRepository.save(entity)
    }


}