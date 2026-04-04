package ru.ilyubarskiy.mai.routing_service.configuration

import com.graphhopper.GraphHopper
import com.graphhopper.config.LMProfile
import com.graphhopper.config.Profile
import com.graphhopper.reader.dem.CGIARProvider
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.ev.AverageSlope
import com.graphhopper.routing.ev.DecimalEncodedValueImpl
import com.graphhopper.routing.ev.DefaultImportRegistry
import com.graphhopper.routing.ev.ImportUnit
import com.graphhopper.routing.ev.MaxSlope
import com.graphhopper.routing.ev.MaxWidth
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.RoadClassLink
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue
import com.graphhopper.routing.ev.Surface
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.util.CustomModel
import com.graphhopper.util.PMap
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.ilyubarskiy.mai.routing_service.configuration.tag_parser.RobotAccessParser
import ru.ilyubarskiy.mai.routing_service.configuration.tag_parser.SurfacePenaltyParser
import ru.ilyubarskiy.mai.routing_service.configuration.tag_parser.WidthTagParser
import ru.ilyubarskiy.mai.routing_service.configuration.wieghting.RobotWeighting
import ru.ilyubarskiy.mai.routing_service.service.GraphCacheLoader
import ru.ilyubarskiy.mai.routing_service.service.PriorityProvider
import ru.ilyubarskiy.mai.routing_service.service.TrafficProvider

@Configuration
class GraphhopperConfiguration(
    private val cacheLoader: GraphCacheLoader,
    private val priorityProvider: PriorityProvider,
    private val trafficProvider: TrafficProvider
) {

    @Bean
    fun graphhopper(): GraphHopper {
        val downloadedPbfPath = cacheLoader.downloadPbfIfNeeded()

        val robotProfileName = "robot_profile"
        val robotProfile = Profile(robotProfileName)
            .setWeighting("custom")
            .setCustomModel(CustomModel())

        val profilesList = listOf(robotProfile)

        val hopper = object : GraphHopper() {
            override fun createWeightingFactory(): WeightingFactory {
                return object : WeightingFactory {
                    override fun createWeighting(
                        profile: Profile?,
                        hints: PMap?,
                        disableTurnCosts: Boolean
                    ): Weighting {
                        return RobotWeighting(
                            encodingManager,
                            trafficProvider,
                            priorityProvider
                        )
                    }
                }
            }
        }

        val customRegistry = object : DefaultImportRegistry() {
            override fun createImportUnit(name: String): ImportUnit {
                return when (name) {
                    WidthTagParser.KEY -> ImportUnit.create(
                        name,
                        { _ -> DecimalEncodedValueImpl(name, 10, 2.0, false) },
                        { lookup, _ -> WidthTagParser(lookup.getDecimalEncodedValue(name)) }
                    )
                    RobotAccessParser.KEY -> ImportUnit.create(
                        name,
                        { _ -> SimpleBooleanEncodedValue(name) },
                        { lookup, _ -> RobotAccessParser(lookup.getBooleanEncodedValue(name)) }
                    )
                    SurfacePenaltyParser.KEY -> ImportUnit.create(
                        name,
                        { _ -> DecimalEncodedValueImpl(name, 10, 2.0, false) },
                        { lookup, _ -> SurfacePenaltyParser(lookup.getDecimalEncodedValue(name)) }
                    )
                    else -> super.createImportUnit(name)
                }
            }
        }

        hopper.osmFile = downloadedPbfPath

        hopper.setGraphHopperLocation("graph-cache")

        hopper.encodedValuesString = listOf(
            WidthTagParser.KEY,
            RobotAccessParser.KEY,
            SurfacePenaltyParser.KEY,
            RoadClass.KEY,
            RoadClassLink.KEY,
            Surface.KEY,
            MaxWidth.KEY,
            AverageSlope.KEY,
            MaxSlope.KEY
        ).joinToString(",")

        hopper.profiles = profilesList

        val elevationProvider = CGIARProvider()
        hopper.elevationProvider = elevationProvider
        hopper.setElevation(true)
        hopper.setImportRegistry(customRegistry)
        hopper.setMinNetworkSize(0)
        hopper.lmPreparationHandler.setLMProfiles(LMProfile(robotProfileName))

        hopper.importOrLoad()

        priorityProvider.isInitiated = true

        return hopper
    }
}