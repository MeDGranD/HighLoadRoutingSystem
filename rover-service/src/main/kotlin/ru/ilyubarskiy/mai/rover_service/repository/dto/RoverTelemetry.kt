package ru.ilyubarskiy.mai.rover_service.repository.dto

data class RoverTelemetry(
    val lat: Double,
    val lon: Double,
    val battery: Double,
    val timestamp: Long
)
