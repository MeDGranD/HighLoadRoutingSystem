package ru.ilyubarskiy.mai.order_service.repository.entity

enum class OrderStatus(val value: String) {

    ROUTING("ROUTING"),
    FAILED("FAILED"),
    IN_PROGRESS("IN_PROGRESS"),
    CANCELED("CANCELED"),
    DONE("DONE")
}