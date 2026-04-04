package ru.ilyubarskiy.mai.order_service.repository.dto.rest

import java.util.*

data class OrderStatusResponse(
    val orderId: UUID,
    val status: String,
    val details: Details?,
    val errors: List<String>
) {
    data class Details(
        val distance: Double,
        val time: Long,
        val robotLat: Double,
        val robotLon: Double
    )

}