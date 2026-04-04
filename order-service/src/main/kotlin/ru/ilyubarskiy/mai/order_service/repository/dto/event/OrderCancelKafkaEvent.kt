package ru.ilyubarskiy.mai.order_service.repository.dto.event

import ru.ilyubarskiy.mai.order_service.repository.dto.Point
import java.util.*

data class OrderCancelKafkaEvent(
    val orderId: UUID,
): KafkaEvent
