package com.example.simcore

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic sim core (Milestone 0..7).
 *
 * Milestone 5:
 * - Fleet -> Planet combat on arrival (spec exact).
 *
 * Milestone 6:
 * - Fleet <-> Fleet combat math (spec exact) in FleetFleetCombat.kt
 *
 * Milestone 7:
 * - Collision detection + event ordering
 *   Fleets interact only on collision; resolve deterministically in time order within the step.
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
     * 3) fleet movement update with Milestone 7 event ordering:
     *    - resolve collisions + arrivals in deterministic time order within the tick
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

        // Milestone 3/5/7: fleets + movement + collision/arrival event ordering
        val graph = if (afterCommands.fleets.isEmpty() || dtMicros == 0L) null else afterCommands.mapGraph()

        val (resolvedPlanets, nextFleets) = if (graph == null) {
            producedPlanets to afterCommands.fleets
        } else {
            // Deterministic id->index lookup for the (produced) planet list (still canonical sorted by id).
            val planetIndexById = HashMap<Int, Int>(producedPlanets.size)
            for (i in producedPlanets.indices) planetIndexById[producedPlanets[i].id] = i

            val planetsMutable = producedPlanets.toMutableList()

            val stepStartTime = afterCommands.simTimeMicros
            val stepEndTime = stepStartTime + dtMicros

            val resultFleets = resolveFleetEventsWithinStep(
                fleetsIn = afterCommands.fleets,
                graph = graph,
                planetsMutable = planetsMutable,
                planetIndexById = planetIndexById,
                stepStartTimeMicros = stepStartTime,
                stepEndTimeMicros = stepEndTime
            )

            planetsMutable.toList() to resultFleets
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
     * Milestone 7: Resolve all fleet movement, collisions, and arrivals within one simulation step,
     * in deterministic time order.
     *
     * Deterministic tie-breaks:
     * - Primary: eventTimeMicros ascending
     * - Secondary: event type order (collision before arrival at same instant)
     * - Tertiary: stable ID ordering
     *   - collision: (minFleetId, maxFleetId)
     *   - arrival: (fleetId, Int.MAX_VALUE)
     */
    private fun resolveFleetEventsWithinStep(
        fleetsIn: List<Fleet>,
        graph: MapGraph,
        planetsMutable: MutableList<Planet>,
        planetIndexById: Map<Int, Int>,
        stepStartTimeMicros: Long,
        stepEndTimeMicros: Long
    ): List<Fleet> {
        if (fleetsIn.isEmpty()) return emptyList()
        val dtMicrosTotal = stepEndTimeMicros - stepStartTimeMicros
        if (dtMicrosTotal <= 0L) return fleetsIn

        // We'll mutate a working list of fleets (kept canonical sorted by id after each event).
        var fleets = fleetsIn.map { it.canonical() }.sortedBy { it.id }

        var curTime = stepStartTimeMicros

        while (curTime < stepEndTimeMicros && fleets.isNotEmpty()) {
            val remainingMicros = stepEndTimeMicros - curTime
            val evt = findNextEvent(
                fleets = fleets,
                graph = graph,
                curTimeMicros = curTime,
                remainingMicros = remainingMicros
            )

            if (evt == null) {
                // No events: advance everybody to end of step and finish.
                fleets = advanceAllFleets(fleets, graph, remainingMicros)
                break
            }

            val advanceMicros = evt.eventTimeMicros - curTime
            if (advanceMicros > 0L) {
                fleets = advanceAllFleets(fleets, graph, advanceMicros)
                curTime += advanceMicros
            } else {
                // Event at current instant; no time advance.
                curTime = evt.eventTimeMicros
            }

            // Resolve event at this instant.
            fleets = when (evt) {
                is FleetEvent.Collision -> resolveCollisionAtInstant(
                    fleets = fleets,
                    graph = graph,
                    collision = evt
                )
                is FleetEvent.Arrival -> resolveArrivalAtInstant(
                    fleets = fleets,
                    planetsMutable = planetsMutable,
                    planetIndexById = planetIndexById,
                    arrival = evt
                )
            }.sortedBy { it.id }

            // Safety: if event resolution didn't progress time and didn't change fleets, avoid infinite loop.
            // This should not happen in normal cases, but determinism > crashing.
            if (advanceMicros == 0L && evt.eventTimeMicros == curTime) {
                // If the same event would repeat, nudge time forward by 1 micro to guarantee progress.
                // (Still deterministic.)
                if (curTime < stepEndTimeMicros) curTime += 1L
            }
        }

        return fleets.map { it.canonical() }.sortedBy { it.id }
    }

    /**
     * Advance all fleets forward for a given duration (micros), without resolving any collisions/arrivals.
     * Any fleet that would reach progress >= 1 is clamped to 1.0 (arrival events should be resolved earlier).
     */
    private fun advanceAllFleets(
        fleets: List<Fleet>,
        graph: MapGraph,
        advanceMicros: Long
    ): List<Fleet> {
        if (advanceMicros <= 0L) return fleets
        val dtSeconds = advanceMicros.toDouble() / 1_000_000.0
        val speed = FLEET_SPEED_WORLD_UNITS_PER_SEC

        val out = ArrayList<Fleet>(fleets.size)
        for (f in fleets) {
            val e = graph.edge(f.edgeId)
            if (e == null) {
                out.add(f)
                continue
            }
            val len = e.lengthWorldUnits
            if (len <= 0.0) {
                // Zero-length edges are "instant" — arrival should be handled as an arrival event.
                // Here: clamp to 1.0 to avoid NaNs.
                out.add(f.copy(progress = 1.0))
                continue
            }
            val deltaProgress = (speed * dtSeconds) / len
            val newProgress = f.progress + deltaProgress
            out.add(f.copy(progress = if (newProgress >= 1.0) 1.0 else newProgress))
        }
        return out
    }

    /**
     * Find the next event (collision or arrival) within the remaining time of this step.
     * Returns null if no event occurs before step end.
     */
    private fun findNextEvent(
        fleets: List<Fleet>,
        graph: MapGraph,
        curTimeMicros: Long,
        remainingMicros: Long
    ): FleetEvent? {
        if (remainingMicros <= 0L) return null

        var best: FleetEvent? = null

        // 1) Arrival events
        for (f in fleets) {
            val e = graph.edge(f.edgeId) ?: continue
            val len = e.lengthWorldUnits
            val tMicros = if (len <= 0.0) {
                0L
            } else {
                val speed = FLEET_SPEED_WORLD_UNITS_PER_SEC
                val vProgPerSec = speed / len
                if (vProgPerSec <= 0.0) continue

                val remainingProg = 1.0 - f.progress
                if (remainingProg <= ARRIVAL_EPS_PROGRESS) 0L
                else {
                    val tSec = remainingProg / vProgPerSec
                    val t = floor(tSec * 1_000_000.0).toLong()
                    max(0L, t)
                }
            }

            if (tMicros <= remainingMicros) {
                val evtTime = curTimeMicros + tMicros
                val evt = FleetEvent.Arrival(
                    eventTimeMicros = evtTime,
                    fleetId = f.id
                )
                best = pickEarlier(best, evt)
            }
        }

        // 2) Collision events (same edge, opposite directions)
        // Group fleets by edgeId deterministically
        val byEdge = fleets.groupBy { it.edgeId }
        for ((edgeId, flist) in byEdge) {
            if (flist.size < 2) continue
            val edge = graph.edge(edgeId) ?: continue
            val len = edge.lengthWorldUnits
            if (len <= 0.0) continue // zero-length is handled as arrivals

            val speed = FLEET_SPEED_WORLD_UNITS_PER_SEC
            val dtSec = remainingMicros.toDouble() / 1_000_000.0
            val deltaProgThisWindow = (speed * dtSec) / len

            // Precompute scalar positions (0..1 along edge a->b) and scalar velocities for each fleet
            data class K(
                val fleet: Fleet,
                val pos: Double,
                val vel: Double // scalar units per "window" (not per second): pos += vel * tFrac, where tFrac in [0,1]
            )

            val ks = ArrayList<K>(flist.size)
            for (f in flist) {
                val (pos, vel) = scalarPosAndVel(edge, f, deltaProgThisWindow)
                ks.add(K(f, pos, vel))
            }

            // Consider pairs: collision when two fleets are approaching and their scalar positions cross (or start within tolerance)
            // We'll compute collision time fraction t in [0,1] of the remaining window.
            for (i in 0 until ks.size) {
                for (j in i + 1 until ks.size) {
                    val a = ks[i]
                    val b = ks[j]

                    // Ensure a starts "left" of b in scalar position
                    var left = a
                    var right = b
                    if (left.pos > right.pos) {
                        left = b
                        right = a
                    }

                    // Relative closing speed must be positive
                    val rel = left.vel - right.vel
                    if (rel <= 0.0) continue

                    val sep0 = right.pos - left.pos
                    val tFrac: Double = if (sep0 <= COLLISION_TOLERANCE_PROGRESS) {
                        0.0
                    } else {
                        sep0 / rel
                    }

                    if (!tFrac.isFinite()) continue
                    if (tFrac < 0.0 || tFrac > 1.0) continue

                    // Convert to micros (floor) for deterministic ordering.
                    val tMicros = floor(tFrac * remainingMicros.toDouble()).toLong()
                    val evtTime = curTimeMicros + tMicros

                    val id1 = min(left.fleet.id, right.fleet.id)
                    val id2 = max(left.fleet.id, right.fleet.id)

                    val evt = FleetEvent.Collision(
                        eventTimeMicros = evtTime,
                        fleetIdA = id1,
                        fleetIdB = id2
                    )

                    best = pickEarlier(best, evt)
                }
            }
        }

        // If best occurs after the window, ignore.
        return best?.takeIf { it.eventTimeMicros <= curTimeMicros + remainingMicros }
    }

    private data class EventKey(
        val time: Long,
        val typeOrder: Int,
        val a: Int,
        val b: Int
    ) : Comparable<EventKey> {
        override fun compareTo(other: EventKey): Int {
            val t = time.compareTo(other.time)
            if (t != 0) return t
            val ty = typeOrder.compareTo(other.typeOrder)
            if (ty != 0) return ty
            val aa = a.compareTo(other.a)
            if (aa != 0) return aa
            return b.compareTo(other.b)
        }
    }

    private fun pickEarlier(a: FleetEvent?, b: FleetEvent): FleetEvent {
        if (a == null) return b
        val ka = a.sortKey()
        val kb = b.sortKey()
        return if (ka <= kb) a else b
    }

    private fun FleetEvent.sortKey(): EventKey {
        return when (this) {
            is FleetEvent.Collision -> EventKey(eventTimeMicros, 0, fleetIdA, fleetIdB)
            is FleetEvent.Arrival -> EventKey(eventTimeMicros, 1, fleetId, Int.MAX_VALUE)
        }
    }


    /**
     * Compute scalar position and scalar velocity along canonical edge direction (a->b).
     *
     * - scalarPos is in [0,1] where 0 at a, 1 at b.
     * - scalarVel is per-window (not per second): scalarPos += scalarVel * tFrac, tFrac in [0,1] of the window.
     *
     * Fleet progress always increases along its own travel direction, but scalar position
     * increases if fleet travels a->b, decreases if fleet travels b->a.
     */
    private fun scalarPosAndVel(edge: Edge, f: Fleet, deltaProgThisWindow: Double): Pair<Double, Double> {
        val a = edge.aPlanetId
        val b = edge.bPlanetId

        val isAToB = (f.fromPlanetId == a && f.toPlanetId == b)
        val isBToA = (f.fromPlanetId == b && f.toPlanetId == a)

        // If fleet endpoints don't match edge endpoints (shouldn't happen, but stay deterministic):
        // treat as a->b.
        val dirAToB = if (isAToB) true else if (isBToA) false else true

        val scalarPos = if (dirAToB) {
            f.progress
        } else {
            1.0 - f.progress
        }

        val scalarVel = if (dirAToB) {
            deltaProgThisWindow
        } else {
            -deltaProgThisWindow
        }

        return scalarPos to scalarVel
    }

    /**
     * Resolve a collision event at the current instant.
     * Fleets interact only on collision; use spec fleet-fleet combat.
     */
    private fun resolveCollisionAtInstant(
        fleets: List<Fleet>,
        graph: MapGraph,
        collision: FleetEvent.Collision
    ): List<Fleet> {
        // Find the two fleets by id (fleets is sorted by id).
        val idxA = fleets.binarySearchBy(collision.fleetIdA) { it.id }
        val idxB = fleets.binarySearchBy(collision.fleetIdB) { it.id }
        if (idxA < 0 || idxB < 0) return fleets

        val fA = fleets[idxA]
        val fB = fleets[idxB]

        // They must be on same edge and opposite directions to be a valid collision.
        if (fA.edgeId != fB.edgeId) return fleets
        val e = graph.edge(fA.edgeId) ?: return fleets

        val a = e.aPlanetId
        val b = e.bPlanetId

        val aAToB = (fA.fromPlanetId == a && fA.toPlanetId == b)
        val aBToA = (fA.fromPlanetId == b && fA.toPlanetId == a)
        val bAToB = (fB.fromPlanetId == a && fB.toPlanetId == b)
        val bBToA = (fB.fromPlanetId == b && fB.toPlanetId == a)

        val opposite = (aAToB && bBToA) || (aBToA && bAToB)
        if (!opposite) return fleets

        val profA = ShipProfiles.forOwner(fA.owner)
        val profB = ShipProfiles.forOwner(fB.owner)

        val (outA, outB) = FleetFleetCombat.resolveFleets(
            f1 = fA,
            airToAir1 = profA.airToAir,
            f2 = fB,
            airToAir2 = profB.airToAir
        )

        val out = ArrayList<Fleet>(fleets.size)
        for (f in fleets) {
            when (f.id) {
                fA.id -> if (outA != null) out.add(outA)
                fB.id -> if (outB != null) out.add(outB)
                else -> out.add(f)
            }
        }
        return out
    }

    /**
     * Resolve an arrival event at the current instant: apply Fleet->Planet combat and consume the fleet.
     */
    private fun resolveArrivalAtInstant(
        fleets: List<Fleet>,
        planetsMutable: MutableList<Planet>,
        planetIndexById: Map<Int, Int>,
        arrival: FleetEvent.Arrival
    ): List<Fleet> {
        val idx = fleets.binarySearchBy(arrival.fleetId) { it.id }
        if (idx < 0) return fleets

        val f = fleets[idx]
        resolveFleetArrivesAtPlanet(f, planetsMutable, planetIndexById)

        val out = ArrayList<Fleet>(fleets.size - 1)
        for (ff in fleets) {
            if (ff.id != f.id) out.add(ff)
        }
        return out
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

    // --- Milestone 7 event model ---

    private sealed class FleetEvent(open val eventTimeMicros: Long) {
        data class Collision(
            override val eventTimeMicros: Long,
            val fleetIdA: Int,
            val fleetIdB: Int
        ) : FleetEvent(eventTimeMicros)

        data class Arrival(
            override val eventTimeMicros: Long,
            val fleetId: Int
        ) : FleetEvent(eventTimeMicros)
    }

    companion object {
        const val DEFAULT_DT_MICROS: Long = 16_666L // ~60 Hz

        // Milestone 3: base fleet speed (world-units / second).
        const val FLEET_SPEED_WORLD_UNITS_PER_SEC: Double = 10.0

        // Milestone 7: tolerances
        private const val COLLISION_TOLERANCE_PROGRESS: Double = 1e-9
        private const val ARRIVAL_EPS_PROGRESS: Double = 1e-12

        private const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325uL
        private const val FNV_PRIME: ULong = 0x100000001b3uL
    }
}
