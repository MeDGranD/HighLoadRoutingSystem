package ru.ilyubarskiy.mai.rover_service.repository.dto

import ru.ilyubarskiy.mai.rover_service.repository.entity.RoverStatus
import java.util.UUID

data class RoverStatusEvent(
    val roverId: UUID,
    val status: RoverStatus,
)