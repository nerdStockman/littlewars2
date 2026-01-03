package com.example.littlewarsinthestars

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.simcore.Edge
import com.example.simcore.GameState
import com.example.simcore.Owner
import com.example.simcore.Planet
import com.example.simcore.SendFraction
import com.example.simcore.Vec2
import com.google.android.material.button.MaterialButton
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var simText: TextView
    private lateinit var gameView: GameView

    private lateinit var btn25: MaterialButton
    private lateinit var btn50: MaterialButton
    private lateinit var btn100: MaterialButton

    private var state: GameState = buildScenario()

    // Milestone 9: Tap-Tap volley state
    private var selectedSourcePlanetId: Int? = null
    private var selectedFraction: SendFraction = SendFraction.PCT_50

    private val dtMicros: Long = GameState.DEFAULT_DT_MICROS
    private val stepsPerFrame: Int = 1 // bump to 2/3 if you want it to run faster visually

    private val tickRunnable = object : Runnable {
        override fun run() {
            repeat(stepsPerFrame) {
                state = state.step(dtMicros = dtMicros)
            }

            gameView.setState(state)

            // If selected source disappeared (shouldn't) or is no longer owned by itself, keep selection by id;
            // user can re-tap to change. (We only clear if planet is missing.)
            if (selectedSourcePlanetId != null && state.planets.none { it.id == selectedSourcePlanetId }) {
                selectedSourcePlanetId = null
                gameView.setSelectedPlanetId(null)
            }

            simText.text =
                "tick=${state.tick}  timeMicros=${state.simTimeMicros}  fleets=${state.fleets.size}\n" +
                        "hash=${state.stateHash()}\n" +
                        "fraction=${fractionLabel(selectedFraction)}  source=${selectedSourcePlanetId ?: "-"}"

            handler.postDelayed(this, 16L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        simText = findViewById(R.id.simText)
        gameView = findViewById(R.id.gameView)

        btn25 = findViewById(R.id.btn25)
        btn50 = findViewById(R.id.btn50)
        btn100 = findViewById(R.id.btn100)

        btn25.setOnClickListener { setFraction(SendFraction.PCT_25) }
        btn50.setOnClickListener { setFraction(SendFraction.PCT_50) }
        btn100.setOnClickListener { setFraction(SendFraction.PCT_100) }
        updateFractionButtons()

        gameView.setOnPlanetTappedListener { planetId ->
            handlePlanetTap(planetId)
        }

        gameView.setState(state)
        gameView.setSelectedPlanetId(selectedSourcePlanetId)
    }

    override fun onStart() {
        super.onStart()
        handler.post(tickRunnable)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tickRunnable)
    }

    private fun setFraction(f: SendFraction) {
        selectedFraction = f
        updateFractionButtons()
    }

    private fun updateFractionButtons() {
        fun style(btn: MaterialButton, active: Boolean) {
            btn.alpha = if (active) 1.0f else 0.45f
        }
        style(btn25, selectedFraction == SendFraction.PCT_25)
        style(btn50, selectedFraction == SendFraction.PCT_50)
        style(btn100, selectedFraction == SendFraction.PCT_100)
    }

    /**
     * Milestone 9: Tap source -> tap neighbor => enqueue VolleySend command
     * at current sim time snapped to tick boundary.
     */
    private fun handlePlanetTap(tappedId: Int) {
        val tapped = state.planets.firstOrNull { it.id == tappedId } ?: return

        val srcId = selectedSourcePlanetId
        if (srcId == null) {
            // Selecting a source: only allow non-neutral planets (you can change this later).
            if (tapped.owner == Owner.NEUTRAL) return
            selectedSourcePlanetId = tappedId
            gameView.setSelectedPlanetId(tappedId)
            return
        }

        // If tapping the same planet again: deselect
        if (srcId == tappedId) {
            selectedSourcePlanetId = null
            gameView.setSelectedPlanetId(null)
            return
        }

        val src = state.planets.firstOrNull { it.id == srcId }
        if (src == null) {
            selectedSourcePlanetId = null
            gameView.setSelectedPlanetId(null)
            return
        }

        // Must be adjacent (per spec). If not adjacent, treat this tap as "select new source" if it's non-neutral.
        val graph = state.mapGraph()
        val isNeighbor = graph.neighborPlanetIds(srcId).contains(tappedId)

        if (!isNeighbor) {
            if (tapped.owner != Owner.NEUTRAL) {
                selectedSourcePlanetId = tappedId
                gameView.setSelectedPlanetId(tappedId)
            }
            return
        }

        // Enqueue command owned by the current source owner (supports “two humans on one device” naturally).
        val snappedTime = snapToTickBoundary(state.simTimeMicros, dtMicros)
        state = state.enqueueVolleySend(
            simTimeMicros = snappedTime,
            playerId = src.owner,
            sourcePlanetId = srcId,
            targetPlanetId = tappedId,
            fraction = selectedFraction
        )

// NEW: always deselect after an action
        selectedSourcePlanetId = null
        gameView.setSelectedPlanetId(null)
    }

    private fun snapToTickBoundary(timeMicros: Long, dtMicros: Long): Long {
        if (dtMicros <= 0L) return timeMicros
        return (timeMicros / dtMicros) * dtMicros
    }

    private fun fractionLabel(f: SendFraction): String = when (f) {
        SendFraction.PCT_25 -> "25%"
        SendFraction.PCT_50 -> "50%"
        SendFraction.PCT_100 -> "100%"
    }

    /**
     * Same small demo map as Milestone 8, but WITHOUT scripted commands.
     * You play by tapping.
     */
    private fun buildScenario(): GameState {
        val p1 = Planet(
            id = 1,
            pos = Vec2(-40.0, 0.0),
            owner = Owner.P1,
            unitsFloat = 30.0,
            spawnRate = 2.0,
            adjacencyEdgeIds = listOf(10)
        )
        val p2 = Planet(
            id = 2,
            pos = Vec2(0.0, -30.0),
            owner = Owner.NEUTRAL,
            unitsFloat = 10.0,
            spawnRate = 0.0,
            adjacencyEdgeIds = listOf(10, 11)
        )
        val p3 = Planet(
            id = 3,
            pos = Vec2(0.0, 30.0),
            owner = Owner.NEUTRAL,
            unitsFloat = 10.0,
            spawnRate = 0.0,
            adjacencyEdgeIds = listOf(10, 12)
        )
        val p4 = Planet(
            id = 4,
            pos = Vec2(40.0, 0.0),
            owner = Owner.P2,
            unitsFloat = 30.0,
            spawnRate = 2.0,
            adjacencyEdgeIds = listOf(11, 12)
        )

        fun len(a: Vec2, b: Vec2): Double = hypot(a.x - b.x, a.y - b.y)

        val e10 = Edge(id = 10, aPlanetId = 1, bPlanetId = 2, lengthWorldUnits = len(p1.pos, p2.pos))
        val e11 = Edge(id = 11, aPlanetId = 2, bPlanetId = 4, lengthWorldUnits = len(p2.pos, p4.pos))
        val e12 = Edge(id = 12, aPlanetId = 3, bPlanetId = 4, lengthWorldUnits = len(p3.pos, p4.pos))

        return GameState(
            planets = listOf(p1, p2, p3, p4).sortedBy { it.id }.map { it.canonical() },
            edges = listOf(e10, e11, e12).sortedBy { it.id }
        )
    }
}
