package ru.ilyubarskiy.mai.rover_facade.configuration

import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MqttConfig(
    @Value("\${mqtt.broker-url}") private val brokerUrl: String,
    @Value("\${mqtt.client-id}") private val clientId: String
) {

    @Bean
    fun mqttClient(): MqttAsyncClient {
        return MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
    }

    @Bean
    fun mqttConnectOptions(): MqttConnectOptions {
        return MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 60
        }
    }
}