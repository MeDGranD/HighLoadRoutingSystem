package ru.ilyubarskiy.mai.order_service.repository.dto.event

import ru.ilyubarskiy.mai.order_service.repository.dto.Point
import java.util.*

class OrderUpdateEvent(
    val orderId: UUID,
    val from: Point,
    val to: Point,
): KafkaEvent