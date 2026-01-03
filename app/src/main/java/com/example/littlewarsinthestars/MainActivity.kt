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
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var simText: TextView
    private lateinit var gameView: GameView

    private var state: GameState = buildDemoScenario()

    // “UI speed settings scale simulation time” (viewer version: just a constant)
    private val dtMicros: Long = GameState.DEFAULT_DT_MICROS
    private val stepsPerFrame: Int = 1 // bump to 2/3 if you want it to run faster visually

    private val tickRunnable = object : Runnable {
        override fun run() {
            repeat(stepsPerFrame) {
                state = state.step(dtMicros = dtMicros)
            }

            // Push state into renderer
            gameView.setState(state)

            // Debug overlay
            simText.text =
                "tick=${state.tick}  timeMicros=${state.simTimeMicros}  fleets=${state.fleets.size}\n" +
                        "hash=${state.stateHash()}"

            handler.postDelayed(this, 16L) // ~60Hz draw
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        simText = findViewById(R.id.simText)
        gameView = findViewById(R.id.gameView)

        // Render initial state immediately
        gameView.setState(state)
    }

    override fun onStart() {
        super.onStart()
        handler.post(tickRunnable)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tickRunnable)
    }

    /**
     * Milestone 8: a hardcoded demo scenario with:
     * - a small map (planets + edges)
     * - a couple scripted volley commands at deterministic sim times
     *
     * This is intentionally "dumb" and self-contained so the viewer is easy to verify.
     */
    private fun buildDemoScenario(): GameState {
        // Layout: diamond / square-ish
        // (world coordinates; renderer auto-fits bounds)
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

        // IMPORTANT: GameState requires canonical ordering (sorted by id).
        var s = GameState(
            planets = listOf(p1, p2, p3, p4).sortedBy { it.id }.map { it.canonical() },
            edges = listOf(e10, e11, e12).sortedBy { it.id }
        )

        // Scripted commands:
        // - At t=0: P1 sends 50% to planet 2
        // - At t=0.5s: P2 sends 25% to planet 2
        // - At t=1.2s: P1 sends 100% to planet 3
        s = s.enqueueVolleySend(
            simTimeMicros = 0L,
            playerId = Owner.P1,
            sourcePlanetId = 1,
            targetPlanetId = 2,
            fraction = SendFraction.PCT_50
        )
        s = s.enqueueVolleySend(
            simTimeMicros = 500_000L,
            playerId = Owner.P2,
            sourcePlanetId = 4,
            targetPlanetId = 2,
            fraction = SendFraction.PCT_25
        )
        s = s.enqueueVolleySend(
            simTimeMicros = 1_200_000L,
            playerId = Owner.P1,
            sourcePlanetId = 1,
            targetPlanetId = 2,
            fraction = SendFraction.PCT_100
        )

        return s
    }
}
