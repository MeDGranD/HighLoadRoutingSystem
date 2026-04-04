package ru.ilyubarskiy.mai.planner_global.domen

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.*

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = OrderCreatedKafkaEvent::class, name = "CREATE"),
    JsonSubTypes.Type(value = OrderCancelKafkaEvent::class, name = "CANCEL"),
    JsonSubTypes.Type(value = OrderUpdateEvent::class, name = "UPDATE"),
)
sealed interface OrderKafkaEvent

data class OrderCreatedKafkaEvent(
    val orderId: UUID,
    val from: Point,
    val to: Point,
    val capacity: Double,
): OrderKafkaEvent

data class OrderCancelKafkaEvent(
    val orderId: UUID,
): OrderKafkaEvent

class OrderUpdateEvent(
    val orderId: UUID,
    val from: Point,
    val to: Point,
): OrderKafkaEvent

enum class OrderStatus(val value: String) {

    ROUTING("ROUTING"),
    FAILED("FAILED"),
    IN_PROGRESS("IN_PROGRESS"),
    CANCELED("CANCELED"),
    DONE("DONE")
}

data class OrderRoverEvent(
    val orderId: UUID,
    val status: OrderStatus
)