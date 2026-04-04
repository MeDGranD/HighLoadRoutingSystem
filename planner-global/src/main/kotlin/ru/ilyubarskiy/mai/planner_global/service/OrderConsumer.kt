package ru.ilyubarskiy.mai.planner_global.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service
import ru.ilyubarskiy.mai.planner_global.domen.*

@Service
class OrderConsumer(
    private val dispatchEngine: DispatchEngine
) {

    private val mapper = jacksonObjectMapper()

    @KafkaListener(topics = ["order-planner-event"], groupId = "planner-group")
    suspend fun consumeOrderEvent(payload: String) {
        val event = mapper.readValue(payload, OrderKafkaEvent::class.java)

        when(event){
            is OrderCreatedKafkaEvent -> dispatchEngine.assignOrder(event)
            is OrderCancelKafkaEvent -> dispatchEngine.cancelOrder(event.orderId.toString())
            is OrderUpdateEvent -> dispatchEngine.modifyOrder(event.orderId.toString(), event.from.lat, event.from.lon, event.to.lat, event.to.lon)
        }

    }

}