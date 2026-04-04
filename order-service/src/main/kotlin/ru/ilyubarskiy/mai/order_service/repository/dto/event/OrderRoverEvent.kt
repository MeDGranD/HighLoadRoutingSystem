package ru.ilyubarskiy.mai.order_service.repository.dto.event

import ru.ilyubarskiy.mai.order_service.repository.entity.OrderStatus
import java.util.UUID

data class OrderRoverEvent(
    val orderId: UUID,
    val status: OrderStatus
)
