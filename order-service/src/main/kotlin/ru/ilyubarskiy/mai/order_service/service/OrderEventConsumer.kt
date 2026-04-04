package ru.ilyubarskiy.mai.order_service.service

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Service
import ru.ilyubarskiy.mai.order_service.repository.OrderRepository
import ru.ilyubarskiy.mai.order_service.repository.dto.event.OrderRoverEvent
import tools.jackson.module.kotlin.jacksonObjectMapper

@Service
class OrderEventConsumer(
    private val orderRepository: OrderRepository
) {

    @KafkaListener(topics = ["order-rover-event"], groupId = "order-group")
    fun consumeOrderEvent(
        @Payload message: String,
        @Header(KafkaHeaders.RECEIVED_PARTITION) partition: Int,
        @Header(KafkaHeaders.RECEIVED_KEY) key: String
    ) {
        val eventDto = jacksonObjectMapper().readValue(message, OrderRoverEvent::class.java)
        orderRepository.updateOrderStatus(eventDto.orderId, eventDto.status.value)
    }

}