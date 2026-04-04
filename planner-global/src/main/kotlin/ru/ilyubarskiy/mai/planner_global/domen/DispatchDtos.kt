package ru.ilyubarskiy.mai.planner_global.domen

data class Point(
    val lat: Double,
    val lon: Double
)

class SlaViolationException(message: String) : RuntimeException(message)
class RouteNotFoundException(message: String) : RuntimeException(message)



