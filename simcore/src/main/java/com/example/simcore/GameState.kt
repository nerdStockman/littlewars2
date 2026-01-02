package com.example.simcore

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Deterministic sim core (Milestone 0..5).
 *
 * Milestone 5 adds:
 * - Fleet -> Planet combat on arrival (spec exact).
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
    val nextFleetId: Int = 0,

    // Milestone 4
    val pendingCommands: List<Command> = emptyList(),
    val nextCommandId: Int = 0
) {

    init {
        // Canonical ordering rules:
        // - planets stored sorted by id; adjacencyEdgeIds sorted
        // - edges stored sorted by id
        // - fleets stored sorted by id
        // - pendingCommands stored sorted by (simTimeMicros, id)
        val canonPlanets = planets.map { it.canonical() }.sortedBy { it.id }
        val canonEdges = edges.sortedBy { it.id }
        val canonFleets = fleets.map { it.canonical() }.sortedBy { it.id }
        val canonCommands = pendingCommands.sortedWith(compareBy<Command>({ it.simTimeMicros }, { it.id }))

        require(planets == canonPlanets) {
            "GameState.planets must be canonical (sorted by id; adjacencyEdgeIds sorted)."
        }
        require(edges == canonEdges) {
            "GameState.edges must be canonical (sorted by id)."
        }
        require(fleets == canonFleets) {
            "GameState.fleets must be canonical (sorted by id; progress clamped)."
        }
        require(pendingCommands == canonCommands) {
            "GameState.pendingCommands must be canonical (sorted by simTimeMicros then id)."
        }

        require(nextFleetId >= 0) { "nextFleetId must be >= 0" }
        if (fleets.isNotEmpty()) {
            val maxId = fleets.maxOf { it.id }
            require(nextFleetId > maxId) {
                "nextFleetId must be > max existing fleet id (max=$maxId, nextFleetId=$nextFleetId)"
            }
        }

        require(nextCommandId >= 0) { "nextCommandId must be >= 0" }
        if (pendingCommands.isNotEmpty()) {
            val maxId = pendingCommands.maxOf { it.id }
            require(nextCommandId > maxId) {
                "nextCommandId must be > max existing command id (max=$maxId, nextCommandId=$nextCommandId)"
            }
        }
    }

    /**
     * Deterministic helper to spawn a fleet with a monotonic ID.
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
     * Milestone 4 helper: enqueue a Volley Send command deterministically.
     *
     * Note: UI will eventually create commands directly; this is for tests / plumbing.
     */
    fun enqueueVolleySend(
        simTimeMicros: Long,
        playerId: Int,
        sourcePlanetId: Int,
        targetPlanetId: Int,
        fraction: SendFraction
    ): GameState {
        val cmd = Command(
            id = nextCommandId,
            simTimeMicros = simTimeMicros,
            playerId = playerId,
            kind = Command.Kind.VOLLEY_SEND,
            sourcePlanetId = sourcePlanetId,
            targetPlanetId = targetPlanetId,
            fraction = fraction
        )
        val next = (pendingCommands + cmd).sortedWith(compareBy<Command>({ it.simTimeMicros }, { it.id }))
        return copy(
            pendingCommands = next,
            nextCommandId = nextCommandId + 1
        )
    }

    /**
     * Deterministic fixed timestep step.
     *
     * Milestone 4 command timing rule:
     * - Apply any pending command with simTimeMicros <= *current* simTimeMicros
     *   at the START of the step.
     *
     * Ordering:
     * 0) apply commands due at current simTimeMicros
     * 1) update checksum inputs (time/tick)
     * 2) production update
     * 3) fleet movement update (and Milestone 5: arrival combat)
     */
    fun step(dtMicros: Long = DEFAULT_DT_MICROS): GameState {
        require(dtMicros >= 0L) { "dtMicros must be >= 0" }

        // 0) Apply due commands at the start of the step.
        val afterCommands = applyDueCommands()

        val nextTick = afterCommands.tick + 1L
        val nextTime = afterCommands.simTimeMicros + dtMicros

        // Update checksum deterministically based on meaningful state evolution.
        var h = afterCommands.checksum
        h = fnv1aUpdateLong(h, nextTick)
        h = fnv1aUpdateLong(h, nextTime)
        h = fnv1aUpdateLong(h, dtMicros)

        val dtSeconds = dtMicros.toDouble() / 1_000_000.0

        // Milestone 2: production + clamp
        val producedPlanets = if (afterCommands.planets.isEmpty() || dtMicros == 0L) {
            afterCommands.planets
        } else {
            afterCommands.planets.map { p ->
                val updated = p.unitsFloat + (p.spawnRate * dtSeconds)
                val clamped = if (updated < 0.0) 0.0 else updated
                if (clamped == p.unitsFloat) p else p.copy(unitsFloat = clamped)
            }
        }

        // Milestone 3/5: fleets + movement + arrival combat
        val graph = if (afterCommands.fleets.isEmpty() || dtMicros == 0L) null else afterCommands.mapGraph()

        val (resolvedPlanets, nextFleets) = if (graph == null) {
            producedPlanets to afterCommands.fleets
        } else {
            // Deterministic id->index lookup for the (produced) planet list (still canonical sorted by id).
            val planetIndexById = HashMap<Int, Int>(producedPlanets.size)
            for (i in producedPlanets.indices) planetIndexById[producedPlanets[i].id] = i

            val planetsMutable = producedPlanets.toMutableList()
            val speed = FLEET_SPEED_WORLD_UNITS_PER_SEC

            val moved = ArrayList<Fleet>(afterCommands.fleets.size)

            // Fleets are canonical sorted by id; resolve in that order for determinism.
            for (f in afterCommands.fleets) {
                val e = graph.edge(f.edgeId)

                // If edge missing, keep fleet as-is (deterministic, non-crashing).
                if (e == null) {
                    moved.add(f)
                    continue
                }

                val len = e.lengthWorldUnits
                if (len <= 0.0) {
                    // Instant arrival => resolve combat, fleet consumed.
                    resolveFleetArrivesAtPlanet(f, planetsMutable, planetIndexById)
                    continue
                }

                val deltaProgress = (speed * dtSeconds) / len
                val newProgress = f.progress + deltaProgress

                if (newProgress >= 1.0) {
                    // Arrival => resolve combat, fleet consumed.
                    resolveFleetArrivesAtPlanet(f, planetsMutable, planetIndexById)
                    continue
                } else {
                    moved.add(f.copy(progress = newProgress))
                }
            }

            planetsMutable.toList() to moved
        }

        return afterCommands.copy(
            tick = nextTick,
            simTimeMicros = nextTime,
            checksum = h,
            planets = resolvedPlanets,
            fleets = nextFleets
        )
    }

    /**
     * Milestone 5: Fleet -> Planet combat, spec exact.
     *
     * Let:
     *   A = attacking fleet units
     *   O = attacker offense
     *   H = defender planet HP (floor(unitsFloat))
     *   D = defender defense
     *   B = attacker colonization bonus
     *
     * Damage:
     *   damage = floor(A * O / D)
     *
     * Resolution:
     * - If damage < H: defender keeps planet; newHP = H - damage
     * - If damage == H: defender keeps planet; newHP = 0
     * - If damage > H: attacker captures:
     *     required = ceil(H * D / O)
     *     remainder = A - required
     *     newHP = remainder + B
     *   On capture: ownership flips; internal units set exactly to newHP
     *
     * Note: fleet is always consumed on arrival.
     */
    private fun resolveFleetArrivesAtPlanet(
        fleet: Fleet,
        planetsMutable: MutableList<Planet>,
        planetIndexById: Map<Int, Int>
    ) {
        val targetIndex = planetIndexById[fleet.toPlanetId] ?: return
        val target = planetsMutable[targetIndex]

        val attackerProfile = ShipProfiles.forOwner(fleet.owner)
        val defenderProfile = ShipProfiles.forOwner(target.owner)

        val A = fleet.unitsInt
        val O = attackerProfile.offense
        val H = target.intHP()
        val D = defenderProfile.defense
        val B = attackerProfile.colonizationBonus

        // damage = floor(A * O / D)
        val damage = floor((A.toDouble() * O) / D).toInt()

        val updatedTarget = if (damage < H) {
            val newHP = H - damage
            // Interactions are integer-based; set internal exactly to the integer result.
            if (newHP == H) target else target.copy(unitsFloat = newHP.toDouble())
        } else if (damage == H) {
            // Defender keeps planet with 0 HP.
            if (H == 0) target else target.copy(unitsFloat = 0.0)
        } else {
            // Capture
            // required = ceil(H * D / O)
            val required = ceil((H.toDouble() * D) / O).toInt()
            val remainder = A - required
            val newHP = remainder + B

            // On capture: ownership flips; internal units set exactly to newHP.
            target.copy(
                owner = fleet.owner,
                unitsFloat = newHP.toDouble()
            )
        }

        planetsMutable[targetIndex] = updatedTarget
    }

    /**
     * Applies all commands with simTimeMicros <= current simTimeMicros, in canonical order.
     * Invalid commands are dropped deterministically (no state change).
     */
    private fun applyDueCommands(): GameState {
        if (pendingCommands.isEmpty()) return this

        // pendingCommands is canonical sorted by (simTimeMicros, id).
        val cutoff = simTimeMicros
        var splitIndex = 0
        while (splitIndex < pendingCommands.size && pendingCommands[splitIndex].simTimeMicros <= cutoff) {
            splitIndex++
        }
        if (splitIndex == 0) return this

        val toApply = pendingCommands.subList(0, splitIndex)
        val remaining = pendingCommands.subList(splitIndex, pendingCommands.size)

        if (toApply.isEmpty()) {
            return copy(pendingCommands = remaining.toList())
        }

        val graph = mapGraph()

        // Planets are canonical sorted by id; build deterministic id->index lookup.
        val planetIndexById = HashMap<Int, Int>(planets.size)
        for (i in planets.indices) planetIndexById[planets[i].id] = i

        var cur = this.copy(pendingCommands = remaining.toList())
        for (cmd in toApply) {
            cur = cur.applyCommand(cmd, graph, planetIndexById)
        }
        return cur
    }

    private fun applyCommand(cmd: Command, graph: MapGraph, planetIndexById: Map<Int, Int>): GameState {
        return when (cmd.kind) {
            Command.Kind.VOLLEY_SEND -> applyVolleySend(cmd, graph, planetIndexById)
        }
    }

    /**
     * Volley Send implementation (Milestone 4).
     */
    private fun applyVolleySend(cmd: Command, graph: MapGraph, planetIndexById: Map<Int, Int>): GameState {
        // Basic ownership check: command player must own the source planet.
        val srcIndex = planetIndexById[cmd.sourcePlanetId] ?: return this
        val src = planets[srcIndex]
        if (src.owner != cmd.playerId) return this

        // Source/target must be adjacent; pick the smallest edgeId if multiple edges exist.
        val edgeId = graph.neighborPairs(cmd.sourcePlanetId)
            .filter { (neighborId, _) -> neighborId == cmd.targetPlanetId }
            .minOfOrNull { it.second }
            ?: return this

        // Available is floored int HP.
        val available = src.intHP()
        val sendUnits = cmd.fraction.computeSendUnits(available)
        if (sendUnits < 1) return this

        // Subtract immediately from unitsFloat (keeping fractional remainder).
        val newUnitsFloat = (src.unitsFloat - sendUnits.toDouble()).coerceAtLeast(0.0)
        val updatedSrc = if (newUnitsFloat == src.unitsFloat) src else src.copy(unitsFloat = newUnitsFloat)

        // Update planets list deterministically by index (list stays canonical sorted by id).
        val nextPlanets = planets.toMutableList()
        nextPlanets[srcIndex] = updatedSrc

        // Spawn the fleet.
        val afterSubtract = copy(planets = nextPlanets)
        return afterSubtract.spawnFleet(
            owner = cmd.playerId,
            unitsInt = sendUnits,
            edgeId = edgeId,
            fromPlanetId = cmd.sourcePlanetId,
            toPlanetId = cmd.targetPlanetId,
            progress = 0.0
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
     * planets + edges + fleets + pendingCommands.
     */
    fun worldHash(): ULong {
        var h = FNV_OFFSET_BASIS

        h = fnv1aUpdateLong(h, planets.size.toLong())
        h = fnv1aUpdateLong(h, edges.size.toLong())
        h = fnv1aUpdateLong(h, fleets.size.toLong())
        h = fnv1aUpdateLong(h, pendingCommands.size.toLong())

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

        // Commands (canonical sorted by simTimeMicros then id)
        for (c in pendingCommands) {
            h = fnv1aUpdateLong(h, c.id.toLong())
            h = fnv1aUpdateLong(h, c.simTimeMicros)
            h = fnv1aUpdateLong(h, c.playerId.toLong())
            h = fnv1aUpdateLong(h, c.kind.ordinal.toLong())
            h = fnv1aUpdateLong(h, c.sourcePlanetId.toLong())
            h = fnv1aUpdateLong(h, c.targetPlanetId.toLong())
            h = fnv1aUpdateLong(h, c.fraction.ordinal.toLong())
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
        const val FLEET_SPEED_WORLD_UNITS_PER_SEC: Double = 10.0

        private const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325uL
        private const val FNV_PRIME: ULong = 0x100000001b3uL
    }
}
