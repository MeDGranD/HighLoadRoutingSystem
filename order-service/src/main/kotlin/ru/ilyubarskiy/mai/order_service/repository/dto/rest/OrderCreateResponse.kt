package ru.ilyubarskiy.mai.order_service.repository.dto.rest

import java.util.UUID

data class OrderCreateResponse(
    val orderId: UUID
)