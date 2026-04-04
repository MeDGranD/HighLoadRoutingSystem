package ru.ilyubarskiy.mai.order_service.repository.dto.event

import ru.ilyubarskiy.mai.order_service.repository.dto.Point
import java.util.UUID

data class OrderCreatedKafkaEvent(
    val orderId: UUID,
    val from: Point,
    val to: Point,
    val capacity: Double,
): KafkaEvent