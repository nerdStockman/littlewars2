package com.example.simcore

/**
 * Deterministic sim core (Milestone 0 + Milestone 1 world model + Milestone 2 production + Milestone 3 fleets).
 *
 * Milestone 3 adds:
 * - fleets list stored canonically sorted by id
 * - monotonic nextFleetId counter
 * - deterministic fleet movement on edges each step
 * - fleets removed on arrival (progress >= 1)
 */
data class GameState(
    val tick: Long = 0L,
    val simTimeMicros: Long = 0L,
    val checksum: ULong = FNV_OFFSET_BASIS,

    // Milestone 1
    val planets: List<Planet> = emptyList(),
    val edges: List<Edge> = emptyList(),

    // Milestone 3
    val fleets: List<Fleet> = emptyList(),
    val nextFleetId: Int = 0
) {

    init {
        // Canonical ordering rules:
        // - planets stored sorted by id; adjacencyEdgeIds sorted
        // - edges stored sorted by id
        // - fleets stored sorted by id
        val canonPlanets = planets.map { it.canonical() }.sortedBy { it.id }
        val canonEdges = edges.sortedBy { it.id }
        val canonFleets = fleets.map { it.canonical() }.sortedBy { it.id }

        require(planets == canonPlanets) {
            "GameState.planets must be canonical (sorted by id; adjacencyEdgeIds sorted)."
        }
        require(edges == canonEdges) {
            "GameState.edges must be canonical (sorted by id)."
        }
        require(fleets == canonFleets) {
            "GameState.fleets must be canonical (sorted by id; progress clamped)."
        }

        require(nextFleetId >= 0) { "nextFleetId must be >= 0" }
        if (fleets.isNotEmpty()) {
            val maxId = fleets.maxOf { it.id }
            require(nextFleetId > maxId) {
                "nextFleetId must be > max existing fleet id (max=$maxId, nextFleetId=$nextFleetId)"
            }
        }
    }

    /**
     * Deterministic helper to spawn a fleet with a monotonic ID.
     *
     * (Commands come in Milestone 4; for now this is used by tests / future plumbing.)
     */
    fun spawnFleet(
        owner: Int,
        unitsInt: Int,
        edgeId: Int,
        fromPlanetId: Int,
        toPlanetId: Int,
        progress: Double = 0.0
    ): GameState {
        require(unitsInt >= 1) { "spawnFleet unitsInt must be >= 1" }

        val fleet = Fleet(
            id = nextFleetId,
            owner = owner,
            unitsInt = unitsInt,
            edgeId = edgeId,
            fromPlanetId = fromPlanetId,
            toPlanetId = toPlanetId,
            progress = progress
        ).canonical()

        val nextFleets = (fleets + fleet).sortedBy { it.id }
        return copy(
            fleets = nextFleets,
            nextFleetId = nextFleetId + 1
        )
    }

    /**
     * Deterministic fixed timestep step.
     * dtMicros default ~= 60 Hz (16.666 ms).
     *
     * Ordering:
     * 1) update checksum inputs
     * 2) production update
     * 3) fleet movement update
     */
    fun step(dtMicros: Long = DEFAULT_DT_MICROS): GameState {
        require(dtMicros >= 0L) { "dtMicros must be >= 0" }

        val nextTick = tick + 1L
        val nextTime = simTimeMicros + dtMicros

        // Update checksum deterministically based on meaningful state evolution.
        var h = checksum
        h = fnv1aUpdateLong(h, nextTick)
        h = fnv1aUpdateLong(h, nextTime)
        h = fnv1aUpdateLong(h, dtMicros)

        val dtSeconds = dtMicros.toDouble() / 1_000_000.0

        // Milestone 2: production + clamp
        val nextPlanets = if (planets.isEmpty() || dtMicros == 0L) {
            planets
        } else {
            planets.map { p ->
                val updated = p.unitsFloat + (p.spawnRate * dtSeconds)
                val clamped = if (updated < 0.0) 0.0 else updated
                if (clamped == p.unitsFloat) p else p.copy(unitsFloat = clamped)
            }
        }

        // Milestone 3: fleets + movement
        val graph = if (fleets.isEmpty() || dtMicros == 0L) null else mapGraph()

        val nextFleets = if (graph == null) {
            fleets
        } else {
            val speed = FLEET_SPEED_WORLD_UNITS_PER_SEC
            val moved = ArrayList<Fleet>(fleets.size)

            for (f in fleets) {
                val e = graph.edge(f.edgeId)

                // If edge missing, keep fleet as-is (deterministic, non-crashing).
                if (e == null) {
                    moved.add(f)
                    continue
                }

                val len = e.lengthWorldUnits
                // Edge length 0 => instant arrival.
                if (len <= 0.0) {
                    // Arrival: remove fleet (Milestone 5 will resolve arrival combat).
                    continue
                }

                val deltaProgress = (speed * dtSeconds) / len
                val newProgress = f.progress + deltaProgress

                if (newProgress >= 1.0) {
                    // Arrival: remove fleet.
                    continue
                } else {
                    moved.add(f.copy(progress = newProgress))
                }
            }

            // Canonical order preserved (fleet ids are unique, list was canonical, we iterate in order).
            moved
        }

        return copy(
            tick = nextTick,
            simTimeMicros = nextTime,
            checksum = h,
            planets = nextPlanets,
            fleets = nextFleets
        )
    }

    /**
     * Stable, human-readable hash string.
     *
     * Format:
     *   "{tickHex}-{timeHex}-{checksumHex}-{worldHashHex}"
     */
    fun stateHash(): String {
        val t = tick.toString(16)
        val tm = simTimeMicros.toString(16)
        val c = checksum.toString(16).padStart(16, '0')
        val w = worldHash().toString(16).padStart(16, '0')
        return "$t-$tm-$c-$w"
    }

    /**
     * Deterministic hash of the world+dynamic state we care about for replay-style checks:
     * planets + edges + fleets.
     *
     * (This intentionally includes fleets so replays detect movement differences.)
     */
    fun worldHash(): ULong {
        var h = FNV_OFFSET_BASIS

        h = fnv1aUpdateLong(h, planets.size.toLong())
        h = fnv1aUpdateLong(h, edges.size.toLong())
        h = fnv1aUpdateLong(h, fleets.size.toLong())

        // Planets (canonical sorted by id)
        for (p in planets) {
            h = fnv1aUpdateLong(h, p.id.toLong())
            h = fnv1aUpdateLong(h, p.owner.toLong())
            h = fnv1aUpdateLong(h, p.pos.x.toBits())
            h = fnv1aUpdateLong(h, p.pos.y.toBits())
            h = fnv1aUpdateLong(h, p.unitsFloat.toBits())
            h = fnv1aUpdateLong(h, p.spawnRate.toBits())
            h = fnv1aUpdateLong(h, p.adjacencyEdgeIds.size.toLong())
            for (eid in p.adjacencyEdgeIds) {
                h = fnv1aUpdateLong(h, eid.toLong())
            }
        }

        // Edges (canonical sorted by id)
        for (e in edges) {
            h = fnv1aUpdateLong(h, e.id.toLong())
            h = fnv1aUpdateLong(h, e.aPlanetId.toLong())
            h = fnv1aUpdateLong(h, e.bPlanetId.toLong())
            h = fnv1aUpdateLong(h, e.lengthWorldUnits.toBits())
        }

        // Fleets (canonical sorted by id)
        for (f in fleets) {
            h = fnv1aUpdateLong(h, f.id.toLong())
            h = fnv1aUpdateLong(h, f.owner.toLong())
            h = fnv1aUpdateLong(h, f.unitsInt.toLong())
            h = fnv1aUpdateLong(h, f.edgeId.toLong())
            h = fnv1aUpdateLong(h, f.fromPlanetId.toLong())
            h = fnv1aUpdateLong(h, f.toPlanetId.toLong())
            h = fnv1aUpdateLong(h, f.progress.toBits())
        }

        return h
    }

    fun mapGraph(): MapGraph = MapGraph(planets, edges)

    private fun fnv1aUpdateLong(hash: ULong, value: Long): ULong {
        // Interpret the signed Long as a 64-bit pattern, then feed 8 bytes (little-endian).
        var h = hash
        var v = value.toULong()
        repeat(8) {
            h = (h xor (v and 0xFFu)) * FNV_PRIME
            v = v shr 8
        }
        return h
    }

    companion object {
        const val DEFAULT_DT_MICROS: Long = 16_666L // ~60 Hz

        // Milestone 3: base fleet speed (world-units / second).
        // Speed multipliers per ship type come later; this is the shared baseline.
        const val FLEET_SPEED_WORLD_UNITS_PER_SEC: Double = 10.0

        private const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325uL
        private const val FNV_PRIME: ULong = 0x100000001b3uL
    }
}
