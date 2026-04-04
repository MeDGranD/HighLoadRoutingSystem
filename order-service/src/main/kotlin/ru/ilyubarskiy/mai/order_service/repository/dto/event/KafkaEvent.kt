package ru.ilyubarskiy.mai.order_service.repository.dto.event

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

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
sealed interface KafkaEvent