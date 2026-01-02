package com.example.simcore

/**
 * Basic adjacency lookup built from (planets, edges).
 *
 * We keep lookups deterministic by always returning results in sorted order.
 */
class MapGraph(
    planets: List<Planet>,
    edges: List<Edge>
) {
    private val planetById: Map<Int, Planet> = planets.associateBy { it.id }
    private val edgeById: Map<Int, Edge> = edges.associateBy { it.id }

    // planetId -> sorted list of edgeIds
    private val adjacency: Map<Int, List<Int>> = run {
        val tmp = HashMap<Int, MutableList<Int>>()
        for (p in planets) tmp[p.id] = mutableListOf()

        for (e in edges) {
            tmp.getOrPut(e.aPlanetId) { mutableListOf() }.add(e.id)
            tmp.getOrPut(e.bPlanetId) { mutableListOf() }.add(e.id)
        }

        tmp.mapValues { (_, list) -> list.sorted() }
    }

    fun planet(planetId: Int): Planet? = planetById[planetId]
    fun edge(edgeId: Int): Edge? = edgeById[edgeId]

    fun edgeIdsForPlanet(planetId: Int): List<Int> = adjacency[planetId].orEmpty()

    /**
     * Returns neighbor planet ids (sorted), based on edges incident to planetId.
     */
    fun neighborPlanetIds(planetId: Int): List<Int> {
        val neighbors = edgeIdsForPlanet(planetId).mapNotNull { eid ->
            edgeById[eid]?.other(planetId)
        }
        return neighbors.sorted()
    }

    /**
     * Returns (neighborPlanetId, edgeId) pairs, deterministically sorted by neighbor then edge.
     */
    fun neighborPairs(planetId: Int): List<Pair<Int, Int>> {
        val pairs = edgeIdsForPlanet(planetId).mapNotNull { eid ->
            val e = edgeById[eid] ?: return@mapNotNull null
            val other = e.other(planetId) ?: return@mapNotNull null
            other to eid
        }
        return pairs.sortedWith(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
    }
}
