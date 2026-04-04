package ru.ilyubarskiy.mai.order_service.repository.dto.rest

import ru.ilyubarskiy.mai.order_service.repository.dto.Point

data class OrderCreateRequest(
    val from: Point,
    val to: Point,
    val capacityNeed: Double,
)