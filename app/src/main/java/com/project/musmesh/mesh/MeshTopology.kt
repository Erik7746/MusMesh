package com.project.musmesh.mesh

import com.project.musmesh.protocol.*

data class MeshPeer(val id: Long, val session: Long, val required: Boolean = true)
data class MeshEdge(val from: Long, val to: Long, val rssi: Int = -90, val errors: Int = 0, val stableSeconds: Long = 0) {
    val cost: Int get() = 100 + (-rssi - 35).coerceIn(0, 100) + errors.coerceIn(0, 20) * 10 - stableSeconds.coerceIn(0, 30).toInt()
}

data class DistributionTree(val root: Long, val parents: Map<Long, Long>, val alternatives: Map<Long, Long> = emptyMap()) {
    fun children(node: Long) = parents.filterValues { it == node }.keys.sorted()
    fun path(node: Long): List<Long> {
        val result = mutableListOf(node)
        while (result.last() != root) {
            val parent = parents[result.last()] ?: return emptyList()
            if (parent in result || result.size > ProtocolConstants.MAX_HOPS) return emptyList()
            result.add(parent)
        }
        return result.reversed()
    }
    fun nextHop(from: Long, to: Long): Long? {
        val a = path(from); val b = path(to)
        if (a.isEmpty() || b.isEmpty() || from == to) return null
        val index = b.indexOf(from)
        return if (index >= 0) b.getOrNull(index + 1) else parents[from]
    }
}

object MeshTopology {
    /** Bounded exhaustive search avoids the dead ends of greedy degree-constrained trees (<=8 nodes). */
    fun build(root: Long, nodes: Set<Long>, edges: List<MeshEdge>, previous: DistributionTree? = null): DistributionTree {
        require(nodes.size <= ProtocolConstants.MAX_NODES && root in nodes)
        val costs = edges.filter { it.from in nodes && it.to in nodes && it.from != it.to }
            .groupBy { setOf(it.from, it.to) }.mapValues { (_, es) -> es.minOf { it.cost } }
        var best = emptyMap<Long, Long>(); var bestCost = Int.MAX_VALUE
        val visited = hashSetOf<String>()
        fun search(parents: Map<Long, Long>, depths: Map<Long, Int>, cost: Int) {
            val signature = parents.toSortedMap().entries.joinToString { "${it.key}:${it.value}" }
            if (!visited.add(signature)) return
            if (parents.size > best.size || (parents.size == best.size && cost < bestCost)) { best = parents; bestCost = cost }
            if (best.size == nodes.size - 1 && cost >= bestCost) return
            for (child in (nodes - depths.keys).sorted()) {
                for (parent in depths.keys.sorted()) {
                    val edgeCost = costs[setOf(child, parent)] ?: continue
                    if (depths.getValue(parent) >= ProtocolConstants.MAX_HOPS || parents.values.count { it == parent } >= 2) continue
                    search(parents + (child to parent), depths + (child to depths.getValue(parent) + 1),
                        cost + edgeCost + if (previous?.parents?.get(child) == parent) 0 else 15)
                }
            }
        }
        search(emptyMap(), mapOf(root to 0), 0)
        val tree = DistributionTree(root, best)
        val alternate = best.mapNotNull { (node, parent) ->
            val choice = best.keys.plus(root).filter { it != parent && it != node && node !in tree.path(it) }
                .filter { tree.path(it).size <= ProtocolConstants.MAX_HOPS && tree.children(it).size < 2 && costs.containsKey(setOf(node, it)) }
                .minByOrNull { costs.getValue(setOf(node, it)) }
            choice?.let { node to it }
        }.toMap()
        return tree.copy(alternatives = alternate)
    }
}

/** Session membership is installed by ROOM_ACCEPT; this window allows bounded packet reordering. */
class PacketDeduplicator(private val capacity: Int = 4096) {
    private val windows = mutableMapOf<Pair<Long, Long>, Window>()
    private class Window(var high: Long = 0, val seen: MutableSet<Long> = hashSetOf())
    fun accept(sender: Long, session: Long, sequence: Long): Boolean {
        if (sequence <= 0) return false
        val key = sender to session
        if (key !in windows && windows.size >= 32) return false
        val w = windows.getOrPut(key) { Window() }
        if (sequence <= w.high - capacity || !w.seen.add(sequence)) return false
        w.high = maxOf(sequence, w.high)
        w.seen.removeAll { it <= w.high - capacity }
        return true
    }
    fun clear() = windows.clear()
    fun installSession(sender: Long, session: Long) { windows.keys.removeAll { it.first == sender && it.second != session } }
}
