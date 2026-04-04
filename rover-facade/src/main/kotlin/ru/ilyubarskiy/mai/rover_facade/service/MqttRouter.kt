package ru.ilyubarskiy.mai.rover_facade.service

import jakarta.annotation.PostConstruct
import org.eclipse.paho.client.mqttv3.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import ru.ilyubarskiy.mai.rover_facade.domen.ClickHouseTelemetryWriter
import ru.ilyubarskiy.mai.rover_facade.domen.dto.*
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration

private val logger = LoggerFactory.getLogger(MqttRouter::class.java)

@Service
class MqttRouter(
    private val mqttClient: MqttAsyncClient,
    private val clickHouseWriter: ClickHouseTelemetryWriter,
    private val redisTemplate: RedisTemplate<String, String>,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val mqttOptions: MqttConnectOptions
) {

    private val mapper = jacksonObjectMapper()

    @PostConstruct
    fun initMqtt() {
        mqttClient.setCallback(object : MqttCallbackExtended {

            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                val status = if (reconnect) "Restored" else "Set"
                logger.info("$status connection with MQTT. Initializing Shared-subscribes...")
                subscribeToTopics()
            }

            override fun connectionLost(cause: Throwable?) {
                logger.warn("Connection with MQTT lost: ${cause?.message}")
            }

            override fun messageArrived(topic: String, message: MqttMessage) {}
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        try {
            mqttClient.connect(mqttOptions).waitForCompletion()
        } catch (e: Exception) {
            logger.error("Cannot set primary connection to MQTT: ${e.message}", e)
        }
    }

    private fun subscribeToTopics() {
        val telemetryTopic = "\$share/facade-group/rovers/+/telemetry"
        val eventsTopic = "\$share/facade-group/rovers/+/events"
        val qos = 1

        try {
            mqttClient.subscribe(telemetryTopic, qos) { actualTopic, message ->
                try {
                    val payload = String(message.payload)
                    handleIncomingMqttMessage(actualTopic, payload)
                } catch (e: Exception) {
                    logger.error("Telemetry error: ${e.message}", e)
                }
            }

            mqttClient.subscribe(eventsTopic, qos) { actualTopic, message ->
                try {
                    val payload = String(message.payload)
                    handleIncomingMqttMessage(actualTopic, payload)
                } catch (e: Exception) {
                    logger.error("Event error: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Subscribing error: ${e.message}", e)
        }
    }

    private fun handleIncomingMqttMessage(topic: String, payload: String) {
        try {
            when {
                topic.endsWith("/telemetry") -> processTelemetry(payload)
                topic.endsWith("/events") -> processEvent(payload)
            }
        } catch (e: Exception) {
            logger.error("MQTT event routing error: $topic", e)
        }
    }

    private fun processTelemetry(payload: String) {
        val telemetry = mapper.readValue(payload, RoverTelemetryPayload::class.java)

        clickHouseWriter.pushToQueue(telemetry)

        redisTemplate.opsForValue().set(
            "rover:telemetry:${telemetry.roverId}",
            payload,
            Duration.ofMinutes(5)
        )
    }

    private fun processEvent(payload: String) {
        val event = mapper.readValue(payload, Event::class.java)

        when(event){
            is RoverOrderEventPayload -> handleOrderEvent(event)
            is RoverMissionEventPayload -> handleMissionEvent(event)
        }
    }

    private fun handleOrderEvent(event: RoverOrderEventPayload) {

        if(event.action == "DROPOFF") {
            val orderEvent = OrderRoverEvent(
                orderId = event.orderId!!,
                status = OrderStatus.DONE
            ).let { mapper.writeValueAsString(it) }
            kafkaTemplate.send("order-rover-event", event.orderId.toString(), orderEvent)
        }

    }

    private fun handleMissionEvent(event: RoverMissionEventPayload) {
        if (event.status == "DONE") {
            val roverEvent = RoverStatusEvent(
                roverId = event.roverId,
                status = RoverStatus.FREE
            ).let { mapper.writeValueAsString(it) }
            kafkaTemplate.send("rover-mission-event", event.roverId.toString(), roverEvent)
            val missionEvent = MissionStatusUpdateEvent(
                missionId = event.missionId,
                status = "DONE"
            ).let { mapper.writeValueAsString(it) }
            kafkaTemplate.send("mission-event", event.missionId.toString(), missionEvent)
        }
    }
}