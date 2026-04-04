package ru.ilyubarskiy.mai.order_service.repository.dto

data class OrderPayload(
    val order_id: String,
    val distance_m: Double,
    val estimated_time_sec: Int
)

data class WaypointPayload(
    val sequence_id: Int,
    val lat: Double,
    val lon: Double,
    val node_id: Long,
    val action: WaypointAction,
    val order_id: String?
)

data class MissionPayload(
    val mission_id: String,
    val rover_id: String,
    val total_distance_m: Double,
    val estimated_time_sec: Int,
    val orders: List<OrderPayload>,
    val route: List<WaypointPayload>
)

enum class WaypointAction(val value: String) {

    PICKUP("PICKUP"),
    DROPOFF("DROPOFF"),

}