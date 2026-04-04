package ru.ilyubarskiy.mai.planner_global.domen

import java.util.*

data class RoverStatusEvent(
    val roverId: UUID,
    val status: RoverStatus,
)

enum class RoverStatus(val value: String) {

    ON_MISSION("ON_MISSION"),
    DEAD("DEAD"),
    FREE("FREE"),
    CHARGING("CHARGING")

}