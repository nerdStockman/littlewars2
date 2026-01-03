package com.example.littlewarsinthestars

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.simcore.Edge
import com.example.simcore.Fleet
import com.example.simcore.GameState
import com.example.simcore.Owner
import com.example.simcore.Planet
import com.example.simcore.Vec2
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Milestone 8: Minimal viewer.
 *
 * - Draw edges as lines
 * - Draw planets as circles (owner color) + int HP label (floor(unitsFloat))
 * - Draw fleets as triangles pointing along travel direction + unit label
 *
 * No input. MainActivity pushes GameState into this view each tick.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var state: GameState? = null

    // Paints
    private val paintEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private val paintPlanetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintPlanetStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private val paintFleetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = dp(14f)
    }

    private val planetRadius = dp(18f)
    private val fleetSize = dp(14f)

    fun setState(newState: GameState) {
        state = newState
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val s = state ?: return
        val planets = s.planets
        val edges = s.edges
        val fleets = s.fleets

        // Background
        canvas.drawColor(0xFF101018.toInt())

        if (planets.isEmpty()) return

        // World->screen transform based on planet bounds
        val bounds = worldBounds(planets)
        val pad = dp(30f)
        val availableW = width.toFloat() - pad * 2f
        val availableH = height.toFloat() - pad * 2f
        if (availableW <= 1f || availableH <= 1f) return

        val worldW = max(1e-6, (bounds.right - bounds.left).toDouble())
        val worldH = max(1e-6, (bounds.bottom - bounds.top).toDouble())
        val scale = min(availableW / worldW.toFloat(), availableH / worldH.toFloat())

        fun toScreen(v: Vec2): Pair<Float, Float> {
            val x = (v.x - bounds.left) * scale + pad
            val y = (v.y - bounds.top) * scale + pad
            return x.toFloat() to y.toFloat()
        }

        val planetById = planets.associateBy { it.id }

        // Edges
        paintEdge.color = 0xFF3A3A55.toInt()
        for (e in edges) {
            val a = planetById[e.aPlanetId] ?: continue
            val b = planetById[e.bPlanetId] ?: continue
            val (ax, ay) = toScreen(a.pos)
            val (bx, by) = toScreen(b.pos)
            canvas.drawLine(ax, ay, bx, by, paintEdge)
        }

        // Fleets (draw first so planets sit on top)
        for (f in fleets) {
            val from = planetById[f.fromPlanetId] ?: continue
            val to = planetById[f.toPlanetId] ?: continue
            drawFleet(canvas, f, from.pos, to.pos, ::toScreen)
        }

        // Planets
        for (p in planets) {
            val (x, y) = toScreen(p.pos)

            val (fill, stroke, text) = colorsForOwner(p.owner)
            paintPlanetFill.color = fill
            paintPlanetStroke.color = stroke
            paintText.color = text

            canvas.drawCircle(x, y, planetRadius, paintPlanetFill)
            canvas.drawCircle(x, y, planetRadius, paintPlanetStroke)

            // HP label
            val hp = p.intHP().toString()
            // center text vertically
            val baseline = y - (paintText.descent() + paintText.ascent()) / 2f
            canvas.drawText(hp, x, baseline, paintText)
        }
    }

    private fun drawFleet(
        canvas: Canvas,
        f: Fleet,
        from: Vec2,
        to: Vec2,
        toScreen: (Vec2) -> Pair<Float, Float>
    ) {
        val fx = lerp(from.x, to.x, f.progress)
        val fy = lerp(from.y, to.y, f.progress)

        val (sx, sy) = toScreen(Vec2(fx, fy))

        val angle = atan2((to.y - from.y), (to.x - from.x)) // radians
        val dirX = cos(angle)
        val dirY = sin(angle)

        // Triangle points: tip forward, two base points behind
        val tip = Pair(
            (sx + (dirX * fleetSize)).toFloat(),
            (sy + (dirY * fleetSize)).toFloat()
        )
        val backCenter = Pair(
            (sx - (dirX * fleetSize * 0.8)).toFloat(),
            (sy - (dirY * fleetSize * 0.8)).toFloat()
        )
        val perpX = -dirY
        val perpY = dirX
        val halfWidth = fleetSize * 0.6

        val left = Pair(
            (backCenter.first + (perpX * halfWidth)).toFloat(),
            (backCenter.second + (perpY * halfWidth)).toFloat()
        )
        val right = Pair(
            (backCenter.first - (perpX * halfWidth)).toFloat(),
            (backCenter.second - (perpY * halfWidth)).toFloat()
        )

        val (fill, stroke, text) = colorsForOwner(f.owner)
        paintFleetFill.color = fill
        paintPlanetStroke.color = stroke
        paintText.color = text

        val path = Path().apply {
            moveTo(tip.first, tip.second)
            lineTo(left.first, left.second)
            lineTo(right.first, right.second)
            close()
        }

        canvas.drawPath(path, paintFleetFill)
        canvas.drawPath(path, paintPlanetStroke)

        // Units label slightly above the fleet
        val labelOffset = dp(16f)
        val lx = (sx + (perpX * labelOffset)).toFloat()
        val ly = (sy + (perpY * labelOffset)).toFloat()
        canvas.drawText(f.unitsInt.toString(), lx, ly, paintText)
    }

    private fun colorsForOwner(owner: Int): Triple<Int, Int, Int> {
        // Fill, Stroke, Text
        return when (owner) {
            Owner.P1 -> Triple(0xFF7C4DFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
            Owner.P2 -> Triple(0xFF00BFA5.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
            Owner.P3 -> Triple(0xFFFFB300.toInt(), 0xFFFFFFFF.toInt(), 0xFF101018.toInt())
            Owner.P4 -> Triple(0xFFFF5252.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
            else     -> Triple(0xFF5C6BC0.toInt(), 0xFFCFD8DC.toInt(), 0xFFFFFFFF.toInt()) // neutral
        }
    }

    private fun worldBounds(planets: List<Planet>): RectF {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY

        for (p in planets) {
            minX = min(minX, p.pos.x)
            minY = min(minY, p.pos.y)
            maxX = max(maxX, p.pos.x)
            maxY = max(maxY, p.pos.y)
        }

        // If all planets are same point, expand a bit
        val dx = maxX - minX
        val dy = maxY - minY
        if (dx < 1e-6 && dy < 1e-6) {
            maxX += 1.0
            maxY += 1.0
            minX -= 1.0
            minY -= 1.0
        }

        return RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat())
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
