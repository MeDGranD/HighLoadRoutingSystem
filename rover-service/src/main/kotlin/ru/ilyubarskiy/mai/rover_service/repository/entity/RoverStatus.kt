package ru.ilyubarskiy.mai.rover_service.repository.entity

enum class RoverStatus(val value: String) {

    ON_MISSION("ON_MISSION"),
    DEAD("DEAD"),
    FREE("FREE"),
    CHARGING("CHARGING")

}