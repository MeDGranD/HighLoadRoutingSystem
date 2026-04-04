package ru.ilyubarskiy.mai.rover_facade.service

import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Service

@Service
class MqttFacadeRouter(
    private val mqttClient: MqttAsyncClient
) {

    @KafkaListener(topics = ["mission-set-event"], groupId = "rover-facade-group")
    fun consumeMissionSetEvent(
        @Payload missionJson: String,
        @Header("kafka_receivedMessageKey") roverId: String
    ) {
        val mqttTopic = "rovers/$roverId/missions"

        val message = MqttMessage(missionJson.toByteArray()).apply {
            qos = 1
        }

        mqttClient.publish(mqttTopic, message)
    }
}