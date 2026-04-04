package ru.ilyubarskiy.mai.domen

data class RegionMetadata(
    val regionId: Int,
    val bbox: BoundingBox,
    val connections: MutableMap<Int, MutableList<BoundaryLink>> = mutableMapOf()
)