package ru.ilyubarskiy.mai.mission_service.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.postgresql.util.PGobject
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration
import ru.ilyubarskiy.mai.mission_service.repository.dto.MissionPayload

@Configuration
class JdbcConfig(
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
) : AbstractJdbcConfiguration() {

    override fun jdbcCustomConversions(): JdbcCustomConversions {
        return JdbcCustomConversions(
            listOf(
                MissionPayloadWritingConverter(objectMapper),
                MissionPayloadReadingConverter(objectMapper),
                MissionPayloadStringReadingConverter(objectMapper)
            )
        )
    }
}

@ReadingConverter
class MissionPayloadStringReadingConverter(
    private val objectMapper: ObjectMapper
) : Converter<String, MissionPayload> {

    override fun convert(source: String): MissionPayload {
        return objectMapper.readValue(source, MissionPayload::class.java)
    }
}

@ReadingConverter
class MissionPayloadReadingConverter(
    private val objectMapper: ObjectMapper
) : Converter<PGobject, MissionPayload> {

    override fun convert(source: PGobject): MissionPayload {
        val jsonValue = source.value ?: throw IllegalArgumentException("JSONB value is null")
        return objectMapper.readValue(jsonValue, MissionPayload::class.java)
    }
}

@WritingConverter
class MissionPayloadWritingConverter(
    private val objectMapper: ObjectMapper
) : Converter<MissionPayload, PGobject> {

    override fun convert(source: MissionPayload): PGobject {
        val pgObject = PGobject()
        pgObject.type = "jsonb"
        pgObject.value = objectMapper.writeValueAsString(source)
        return pgObject
    }
}