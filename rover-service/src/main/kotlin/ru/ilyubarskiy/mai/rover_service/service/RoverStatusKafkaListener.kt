package ru.ilyubarskiy.mai.rover_service.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service
import ru.ilyubarskiy.mai.rover_service.repository.RoverRepository
import ru.ilyubarskiy.mai.rover_service.repository.dto.RoverStatusEvent
import ru.ilyubarskiy.mai.rover_service.repository.entity.RoverStatus
import java.util.*

@Service
class RoverStatusKafkaListener(
    private val roverRepository: RoverRepository
) {
    private val mapper = jacksonObjectMapper()

    @KafkaListener(topics = ["rover-mission-event"], groupId = "rover-group")
    fun consumeOrderEvent(payload: String) {
        val event = mapper.readValue(payload, RoverStatusEvent::class.java)

        roverRepository.updateStatus(event.roverId, event.status.value)
    }
}