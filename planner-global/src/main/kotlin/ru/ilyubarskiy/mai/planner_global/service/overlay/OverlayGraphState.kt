package ru.ilyubarskiy.mai.planner_global.service.overlay

import org.springframework.stereotype.Component
import ru.ilyubarskiy.mai.planner_global.domen.overlay.GlobalEdge
import ru.ilyubarskiy.mai.planner_global.domen.overlay.GlobalNode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Component
class OverlayGraphState {

    val nodes = ConcurrentHashMap<Long, GlobalNode>()
    val adjacencyList = ConcurrentHashMap<Long, MutableList<GlobalEdge>>()
    private val boundariesByRegion = ConcurrentHashMap<Int, MutableList<GlobalNode>>()

    fun addNode(node: GlobalNode) {
        nodes.putIfAbsent(node.id, node)

        boundariesByRegion.computeIfAbsent(node.regionId) {
            CopyOnWriteArrayList()
        }.add(node)
    }

    fun addEdge(fromNodeId: Long, edge: GlobalEdge) {
        adjacencyList.computeIfAbsent(fromNodeId) {
            CopyOnWriteArrayList()
        }.add(edge)
    }

    fun getBoundariesForRegion(regionId: Int): List<GlobalNode> {
        return boundariesByRegion[regionId] ?: emptyList()
    }

}