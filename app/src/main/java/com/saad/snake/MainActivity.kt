package com.saad.snake // <-- KEEP YOUR OWN package line from your project

import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ---------------------------------------------------------------------------
// TUNING KNOBS - change these to adjust how the game feels
// ---------------------------------------------------------------------------
private const val BASE_SPEED = 170f      // world units per second
private const val BOOST_SPEED = 300f
private const val BOT_TARGET = 14        // how many AI snakes are alive around you
private const val CHUNK = 500f           // size of one food chunk (world units)
private const val FOOD_PER_CHUNK = 40    // food density
private const val TAU = 6.2831855f
private const val PIF = 3.1415927f

private val PALETTE = listOf(
    Color(0xFFFF5252), Color(0xFFFFB74D), Color(0xFFFFEE58), Color(0xFF66BB6A),
    Color(0xFF26C6DA), Color(0xFF42A5F5), Color(0xFFAB47BC), Color(0xFFEC407A)
)
private val NAMES = listOf(
    "Viper", "Mamba", "Cobra", "Python", "Anaconda", "Asp", "Boa", "Taipan",
    "Adder", "Krait", "Rattler", "Racer", "Copperhead", "Garter", "Sidewinder", "Naga"
)
private val WHITE = Color.White.toArgb()
private val YELLOW = Color(0xFFFFD54F).toArgb()

private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SnakeGame() }
        // Full screen (hide status + navigation bars). Optional, so it must never crash the app.
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}

// ---------------------------------------------------------------------------
// Compose UI: one canvas + two invisible touch zones (left = joystick, right = boost)
// ---------------------------------------------------------------------------
@Composable
fun SnakeGame() {
    val ctx = LocalContext.current
    val store = remember { SettingsStore(ctx) }
    val sfx = remember { Sfx(ctx) }
    val game = remember { Game() }
    var frame by remember { mutableStateOf(0L) }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(store.speed) }
    var volume by remember { mutableStateOf(store.volume) }

    // push the current settings into the game (cheap, runs only when state changes)
    game.speedMul = speed
    game.paused = showSettings || showAbout
    game.onEat = { sfx.eat() }
    game.onDeath = { sfx.die() }
    sfx.volume = volume
    DisposableEffect(Unit) { onDispose { sfx.release() } }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    game.update(((now - last) / 1_000_000_000f).coerceAtMost(0.05f))
                }
                last = now
                frame = now
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                game.render(this, frame)
            }

            // LEFT HALF: floating joystick (appears wherever you touch)
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(0.5f)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (game.dead && game.deadTime > 0.8f) game.restart()
                            game.joyActive = true
                            game.joyBase = down.position
                            game.joyKnob = down.position
                            var pressed = true
                            while (pressed) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id }
                                if (c == null || !c.pressed) {
                                    pressed = false
                                } else {
                                    game.joyKnob = c.position
                                    c.consume()
                                }
                            }
                            game.joyActive = false
                        }
                    }
            )

            // RIGHT HALF: hold anywhere to boost
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.5f)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (game.dead && game.deadTime > 0.8f) game.restart()
                            game.boosting = true
                            var pressed = true
                            while (pressed) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id }
                                if (c == null || !c.pressed) pressed = false else c.consume()
                            }
                            game.boosting = false
                        }
                    }
            )

            // SETTINGS BUTTON (top centre, sits above the touch zones)
            Button(
                onClick = {
                    game.joyActive = false
                    game.boosting = false
                    showSettings = true
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            ) { Text("Settings") }
        }

        if (showSettings) {
            SettingsDialog(
                speed = speed,
                onSpeedChange = { speed = it },
                volume = volume,
                onVolumeChange = { volume = it },
                onSave = {
                    store.speed = speed
                    store.volume = volume
                    sfx.eat() // little preview blip so you can hear the volume
                },
                onRestart = {
                    game.restart()
                    showSettings = false
                },
                onAbout = {
                    showSettings = false
                    showAbout = true
                },
                onClose = { showSettings = false }
            )
        }
        if (showAbout) {
            AboutDialog(onClose = {
                showAbout = false
                showSettings = true
            })
        }
    }
}

// ---------------------------------------------------------------------------
// Data classes
// ---------------------------------------------------------------------------
class Seg(var x: Float, var y: Float)

class Food(
    var x: Float, var y: Float,
    val size: Float, val color: Color,
    val shape: Int,       // 0 circle, 1 square, 2 triangle, 3 diamond
    val phase: Float
)

class Snake(
    val name: String,
    x: Float, y: Float,
    var angle: Float,
    val color: Color,
    var mass: Float
) {
    val segs = ArrayList<Seg>()
    val colorDark: Color = lerp(color, Color.Black, 0.2f)
    var boosting = false
    var dropTimer = 0f
    var thinkTimer = Random.nextFloat() * 0.2f
    var desired = angle
    var wander = 0f
    val aggressive = Random.nextFloat() < 0.35f

    val head: Seg get() = segs[0]
    val radius: Float get() = min(9f + sqrt(mass) * 0.9f, 45f)
    val spacing: Float get() = radius * 0.6f
    val wantedSegs: Int get() = min(8 + (mass * 0.8f).toInt(), 350)

    init {
        val sp = spacing
        for (i in 0 until wantedSegs) {
            segs.add(Seg(x - cos(angle) * sp * i, y - sin(angle) * sp * i))
        }
    }
}

// ---------------------------------------------------------------------------
// The game itself
// ---------------------------------------------------------------------------
class Game {
    // input (written by touch zones)
    var joyActive = false
    var joyBase = Offset.Zero
    var joyKnob = Offset.Zero
    var boosting = false

    // settings + hooks (set from the UI)
    var speedMul = 1f
    var paused = false
    var onEat: (() -> Unit)? = null
    var onDeath: (() -> Unit)? = null

    // state
    lateinit var player: Snake
    var dead = false
    var deadTime = 0f

    private val chunks = HashMap<Long, ArrayList<Food>>()
    private val bots = ArrayList<Snake>()
    private val deadBots = ArrayList<Snake>()
    private val rnd = Random(System.nanoTime())
    private val acc = FloatArray(2)
    private val path = Path()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var best = 0
    private var camX = 0f
    private var camY = 0f
    private var zoom = 1f
    private var time = 0f
    private var viewW = 1920f
    private var viewH = 1080f
    private var dens = 2.75f
    private var spawnTimer = 0f
    private var cleanTimer = 0f
    private var botCounter = 0

    init { restart() }

    fun restart() {
        bots.clear()
        player = Snake("You", camX, camY, 0f, Color(0xFF7CFC00), 10f)
        dead = false
        deadTime = 0f
        zoom = targetZoom()
        val half = hypot(viewW, viewH) / 2f / zoom
        repeat(BOT_TARGET) { spawnBot(300f, half * 1.9f) }
    }

    // ----------------------------------------------------------------- world
    private fun generate(cx: Int, cy: Int): ArrayList<Food> {
        val r = Random(cx * 73856093 xor cy * 19349663 xor 0x5bd1e995)
        val list = ArrayList<Food>()
        repeat(FOOD_PER_CHUNK) {
            list.add(
                Food(
                    cx * CHUNK + r.nextFloat() * CHUNK,
                    cy * CHUNK + r.nextFloat() * CHUNK,
                    4f + r.nextFloat() * 4f,
                    PALETTE[r.nextInt(PALETTE.size)],
                    r.nextInt(4),
                    r.nextFloat() * TAU
                )
            )
        }
        return list
    }

    private fun chunk(cx: Int, cy: Int): ArrayList<Food> =
        chunks.getOrPut(key(cx, cy)) { generate(cx, cy) }

    private fun addFood(x: Float, y: Float, size: Float, color: Color) {
        chunk(floor(x / CHUNK).toInt(), floor(y / CHUNK).toInt())
            .add(Food(x, y, size, color, rnd.nextInt(4), rnd.nextFloat() * TAU))
    }

    private fun ensureChunks() {
        val hw = viewW / 2f / zoom + CHUNK
        val hh = viewH / 2f / zoom + CHUNK
        for (cx in floor((camX - hw) / CHUNK).toInt()..floor((camX + hw) / CHUNK).toInt()) {
            for (cy in floor((camY - hh) / CHUNK).toInt()..floor((camY + hh) / CHUNK).toInt()) {
                chunk(cx, cy)
            }
        }
    }

    private fun unloadFar() {
        val limX = viewW / 2f / zoom + 3f * CHUNK
        val limY = viewH / 2f / zoom + 3f * CHUNK
        val it = chunks.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val mx = ((e.key shr 32).toInt() + 0.5f) * CHUNK
            val my = (e.key.toInt() + 0.5f) * CHUNK
            if (kotlin.math.abs(mx - camX) > limX || kotlin.math.abs(my - camY) > limY) it.remove()
        }
    }

    private fun targetZoom(): Float = (viewH / 540f) / (1f + (player.radius - 12f) * 0.02f)

    // ---------------------------------------------------------------- update
    fun update(dt: Float) {
        if (dt.isNaN() || dt <= 0f || paused) return
        time += dt

        if (!dead) {
            var target: Float? = null
            if (joyActive) {
                val v = joyKnob - joyBase
                if (v.getDistance() > 10f * dens) target = atan2(v.y, v.x)
            }
            player.boosting = boosting && player.mass > 12f
            moveSnake(player, dt, target, 1f)
            eat(player, dt)
        } else {
            deadTime += dt
        }

        for (b in bots) {
            think(b, dt)
            moveSnake(b, dt, b.desired, 0.8f)
            eat(b, dt)
        }

        // collisions: head touching another snake's body = death
        deadBots.clear()
        for (b in bots) {
            var d = !dead && hits(b, player)
            if (!d) {
                for (o in bots) {
                    if (hits(b, o)) { d = true; break }
                }
            }
            if (d) deadBots.add(b)
        }
        var playerDied = false
        if (!dead) {
            for (b in bots) {
                if (hits(player, b)) { playerDied = true; break }
            }
        }
        for (b in deadBots) { explode(b); bots.remove(b) }
        if (playerDied) {
            explode(player)
            dead = true
            onDeath?.invoke()
            deadTime = 0f
            best = max(best, (player.mass * 10).toInt())
        }

        // despawn far bots / spawn new ones just outside the screen
        val half = hypot(viewW, viewH) / 2f / zoom
        val ph = player.head
        val iter = bots.iterator()
        while (iter.hasNext()) {
            val b = iter.next()
            val dx = b.head.x - ph.x
            val dy = b.head.y - ph.y
            if (dx * dx + dy * dy > (half * 2.6f) * (half * 2.6f)) iter.remove()
        }
        spawnTimer -= dt
        if (bots.size < BOT_TARGET && spawnTimer <= 0f) {
            spawnBot(half * 1.15f, half * 1.9f)
            spawnTimer = 0.4f
        }

        // camera follows the player's head
        camX = ph.x
        camY = ph.y
        zoom += (targetZoom() - zoom) * min(1f, dt * 1.5f)
        zoom = zoom.coerceIn(0.3f, 8f)

        ensureChunks()
        cleanTimer += dt
        if (cleanTimer > 2f) { cleanTimer = 0f; unloadFar() }
    }

    private fun turnRate(s: Snake): Float = (5.5f - s.radius * 0.04f).coerceAtLeast(2.2f)

    private fun moveSnake(s: Snake, dt: Float, target: Float?, turnMul: Float) {
        if (target != null) {
            var diff = target - s.angle
            while (diff > PIF) diff -= TAU
            while (diff < -PIF) diff += TAU
            val maxTurn = turnRate(s) * turnMul * dt * speedMul
            s.angle += diff.coerceIn(-maxTurn, maxTurn)
        }
        val speed = (if (s.boosting) BOOST_SPEED else BASE_SPEED) * speedMul
        val h = s.segs[0]
        h.x += cos(s.angle) * speed * dt
        h.y += sin(s.angle) * speed * dt

        // boosting burns mass and leaves a trail of food
        if (s.boosting) {
            s.mass -= 5f * dt
            s.dropTimer += dt
            if (s.dropTimer > 0.25f) {
                s.dropTimer = 0f
                val t = s.segs[s.segs.size - 1]
                addFood(t.x, t.y, 5f, s.color)
            }
        }

        // each segment follows the one in front of it
        val sp = s.spacing
        for (i in 1 until s.segs.size) {
            val p = s.segs[i - 1]
            val c = s.segs[i]
            val dx = c.x - p.x
            val dy = c.y - p.y
            val d = sqrt(dx * dx + dy * dy)
            if (d > 0.001f) {
                c.x = p.x + dx / d * sp
                c.y = p.y + dy / d * sp
            }
        }

        // grow / shrink to match mass
        val want = s.wantedSegs
        while (s.segs.size < want) {
            val t = s.segs[s.segs.size - 1]
            s.segs.add(Seg(t.x, t.y))
        }
        while (s.segs.size > want && s.segs.size > 2) s.segs.removeAt(s.segs.size - 1)
    }

    private fun eat(s: Snake, dt: Float) {
        val h = s.head
        val eatR = s.radius + 6f
        val magR = s.radius + 55f
        val x0 = floor((h.x - magR) / CHUNK).toInt()
        val x1 = floor((h.x + magR) / CHUNK).toInt()
        val y0 = floor((h.y - magR) / CHUNK).toInt()
        val y1 = floor((h.y + magR) / CHUNK).toInt()
        for (cx in x0..x1) {
            for (cy in y0..y1) {
                val list = chunks[key(cx, cy)] ?: continue
                var i = list.size - 1
                while (i >= 0) {
                    val f = list[i]
                    val dx = h.x - f.x
                    val dy = h.y - f.y
                    val d2 = dx * dx + dy * dy
                    val er = eatR + f.size
                    if (d2 < er * er) {
                        s.mass += f.size * 0.16f
                        if (s === player) onEat?.invoke()
                        list[i] = list[list.size - 1]
                        list.removeAt(list.size - 1)
                    } else if (d2 < magR * magR) {
                        // food gets pulled toward the mouth
                        val d = sqrt(d2)
                        val step = min(260f * dt, d)
                        f.x += dx / d * step
                        f.y += dy / d * step
                    }
                    i--
                }
            }
        }
    }

    private fun hits(s: Snake, o: Snake): Boolean {
        if (s === o) return false
        val h = s.head
        val reach = o.segs.size * o.spacing + o.radius + s.radius
        val hx = h.x - o.head.x
        val hy = h.y - o.head.y
        if (hx * hx + hy * hy > reach * reach) return false
        val lim = o.radius + s.radius * 0.7f
        val lim2 = lim * lim
        for (g in o.segs) {
            val dx = h.x - g.x
            val dy = h.y - g.y
            if (dx * dx + dy * dy < lim2) return true
        }
        return false
    }

    private fun explode(s: Snake) {
        val step = if (s.segs.size > 240) 3 else 2
        var i = 0
        while (i < s.segs.size) {
            val g = s.segs[i]
            addFood(
                g.x + (rnd.nextFloat() - 0.5f) * s.radius,
                g.y + (rnd.nextFloat() - 0.5f) * s.radius,
                6f + rnd.nextFloat() * 3f,
                s.color
            )
            i += step
        }
    }

    // -------------------------------------------------------------------- AI
    private fun spawnBot(minD: Float, maxD: Float) {
        val a = rnd.nextFloat() * TAU
        val d = minD + rnd.nextFloat() * (maxD - minD)
        val ph = player.head
        val m = 10f + rnd.nextFloat() * 40f + player.mass * rnd.nextFloat() * 0.7f
        bots.add(
            Snake(
                NAMES[botCounter++ % NAMES.size],
                ph.x + cos(a) * d, ph.y + sin(a) * d,
                rnd.nextFloat() * TAU,
                PALETTE[rnd.nextInt(PALETTE.size)],
                m
            )
        )
    }

    private fun nearestFood(h: Seg, range: Float): Food? {
        var best: Food? = null
        var bd = range * range
        val x0 = floor((h.x - range) / CHUNK).toInt()
        val x1 = floor((h.x + range) / CHUNK).toInt()
        val y0 = floor((h.y - range) / CHUNK).toInt()
        val y1 = floor((h.y + range) / CHUNK).toInt()
        for (cx in x0..x1) {
            for (cy in y0..y1) {
                val list = chunks[key(cx, cy)] ?: continue
                for (f in list) {
                    val dx = f.x - h.x
                    val dy = f.y - h.y
                    val d2 = dx * dx + dy * dy
                    if (d2 < bd) { bd = d2; best = f }
                }
            }
        }
        return best
    }

    private fun repel(b: Snake, o: Snake) {
        val h = b.head
        val reach = o.segs.size * o.spacing + 200f
        val hx = h.x - o.head.x
        val hy = h.y - o.head.y
        if (hx * hx + hy * hy > reach * reach) return
        val r = 70f + o.radius + b.radius
        val r2 = r * r
        var i = 0
        while (i < o.segs.size) {
            val g = o.segs[i]
            val dx = h.x - g.x
            val dy = h.y - g.y
            val d2 = dx * dx + dy * dy
            if (d2 < r2) {
                val d = sqrt(d2).coerceAtLeast(1f)
                val w = (1f - d / r) * 4f
                acc[0] += dx / d * w
                acc[1] += dy / d * w
            }
            i += 2
        }
    }

    private fun think(b: Snake, dt: Float) {
        b.thinkTimer -= dt
        if (b.thinkTimer > 0f) return
        b.thinkTimer = 0.12f + rnd.nextFloat() * 0.1f

        val h = b.head
        var dx: Float
        var dy: Float

        // some bots try to cut off the player's path
        var chase = false
        if (b.aggressive && !dead && b.mass > player.mass * 0.7f) {
            val px = player.head.x - h.x
            val py = player.head.y - h.y
            chase = px * px + py * py < 450f * 450f
        }
        val food = if (chase) null else nearestFood(h, 320f)

        if (chase) {
            dx = player.head.x + cos(player.angle) * 160f - h.x
            dy = player.head.y + sin(player.angle) * 160f - h.y
        } else if (food != null) {
            dx = food.x - h.x
            dy = food.y - h.y
        } else {
            b.wander = (b.wander + (rnd.nextFloat() - 0.5f) * 0.8f).coerceIn(-1f, 1f)
            dx = cos(b.angle + b.wander)
            dy = sin(b.angle + b.wander)
        }
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        dx /= len
        dy /= len

        // steer away from other snakes' bodies
        acc[0] = 0f
        acc[1] = 0f
        if (!dead) repel(b, player)
        for (o in bots) if (o !== b) repel(b, o)
        b.desired = atan2(dy + acc[1], dx + acc[0])
    }

    // ---------------------------------------------------------------- render
    @Suppress("UNUSED_PARAMETER")
    fun render(ds: DrawScope, tick: Long) {
        ds.renderScene()
    }

    private fun sx(wx: Float) = (wx - camX) * zoom + viewW / 2f
    private fun sy(wy: Float) = (wy - camY) * zoom + viewH / 2f

    private fun DrawScope.renderScene() {
        if (size.width < 1f || size.height < 1f) return
        viewW = size.width
        viewH = size.height
        dens = density
        val hw = viewW / 2f / zoom
        val hh = viewH / 2f / zoom

        // background + scrolling dot pattern (this is what makes the world feel like it moves)
        drawRect(Color(0xFF11141D))
        val gs = 90f
        val ix0 = floor((camX - hw) / gs).toInt() - 1
        val ix1 = ceil((camX + hw) / gs).toInt() + 1
        val iy0 = floor((camY - hh) / gs).toInt() - 1
        val iy1 = ceil((camY + hh) / gs).toInt() + 1
        val dotColor = Color(0x2EFFFFFF)
        for (iy in iy0..iy1) {
            val off = if (iy % 2 != 0) 0.5f else 0f
            for (ix in ix0..ix1) {
                drawCircle(dotColor, 2.5f * zoom, Offset(sx((ix + off) * gs), sy(iy * gs)))
            }
        }

        // food
        val cx0 = floor((camX - hw - 20f) / CHUNK).toInt()
        val cx1 = floor((camX + hw + 20f) / CHUNK).toInt()
        val cy0 = floor((camY - hh - 20f) / CHUNK).toInt()
        val cy1 = floor((camY + hh + 20f) / CHUNK).toInt()
        for (cx in cx0..cx1) {
            for (cy in cy0..cy1) {
                val list = chunks[key(cx, cy)] ?: continue
                for (f in list) drawFood(f)
            }
        }

        // snakes (player on top)
        for (b in bots) drawSnake(b)
        if (!dead) drawSnake(player)

        drawHud()
        if (dead) drawGameOver()
    }

    private fun DrawScope.drawFood(f: Food) {
        val x = sx(f.x)
        val y = sy(f.y)
        val pulse = 1f + 0.15f * sin(time * 4f + f.phase)
        val s = f.size * zoom * pulse
        if (x < -s * 3 || x > viewW + s * 3 || y < -s * 3 || y > viewH + s * 3) return
        drawCircle(f.color.copy(alpha = 0.22f), s * 2f, Offset(x, y))
        when (f.shape) {
            0 -> drawCircle(f.color, s, Offset(x, y))
            1 -> drawRect(f.color, Offset(x - s * 0.9f, y - s * 0.9f), Size(s * 1.8f, s * 1.8f))
            2 -> {
                path.reset()
                path.moveTo(x, y - s * 1.3f)
                path.lineTo(x + s * 1.2f, y + s * 0.9f)
                path.lineTo(x - s * 1.2f, y + s * 0.9f)
                path.close()
                drawPath(path, f.color)
            }
            else -> {
                path.reset()
                path.moveTo(x, y - s * 1.3f)
                path.lineTo(x + s * 1.3f, y)
                path.lineTo(x, y + s * 1.3f)
                path.lineTo(x - s * 1.3f, y)
                path.close()
                drawPath(path, f.color)
            }
        }
    }

    private fun DrawScope.drawSnake(s: Snake) {
        val r = s.radius * zoom
        // draw tail first so the head ends up on top
        for (i in s.segs.size - 1 downTo 0) {
            val g = s.segs[i]
            val x = sx(g.x)
            val y = sy(g.y)
            if (x < -r * 2 || x > viewW + r * 2 || y < -r * 2 || y > viewH + r * 2) continue
            if (s.boosting && i % 4 == 0) drawCircle(s.color.copy(alpha = 0.2f), r * 1.7f, Offset(x, y))
            drawCircle(if ((i / 2) % 2 == 0) s.color else s.colorDark, r, Offset(x, y))
        }
        // eyes
        val h = s.head
        val wr = s.radius
        val fx = cos(s.angle)
        val fy = sin(s.angle)
        val px = -fy
        val py = fx
        for (side in -1..1 step 2) {
            val ex = h.x + fx * wr * 0.35f + px * wr * 0.5f * side
            val ey = h.y + fy * wr * 0.35f + py * wr * 0.5f * side
            drawCircle(Color.White, wr * 0.38f * zoom, Offset(sx(ex), sy(ey)))
            drawCircle(Color.Black, wr * 0.2f * zoom, Offset(sx(ex + fx * wr * 0.12f), sy(ey + fy * wr * 0.12f)))
        }
    }

    private fun DrawScope.label(
        t: String, x: Float, y: Float, px: Float,
        align: Paint.Align, argb: Int = WHITE, bold: Boolean = false
    ) {
        textPaint.textSize = px
        textPaint.textAlign = align
        textPaint.color = argb
        textPaint.isFakeBoldText = bold
        drawContext.canvas.nativeCanvas.drawText(t, x, y, textPaint)
    }

    private fun DrawScope.drawHud() {
        val d = dens

        // joystick
        val maxR = 60f * d
        val baseC = if (joyActive) joyBase else Offset(150f * d, viewH - 130f * d)
        drawCircle(Color.White.copy(alpha = 0.10f), maxR, baseC)
        drawCircle(Color.White.copy(alpha = 0.25f), maxR, baseC, style = Stroke(2f * d))
        val knob = if (joyActive) {
            val v = joyKnob - joyBase
            val len = v.getDistance()
            if (len > maxR) joyBase + v / len * maxR else joyKnob
        } else baseC
        drawCircle(Color.White.copy(alpha = if (joyActive) 0.55f else 0.25f), 26f * d, knob)

        // boost button
        val bc = Offset(viewW - 140f * d, viewH - 130f * d)
        drawCircle(Color(0xFFFF7043).copy(alpha = if (boosting) 0.6f else 0.22f), 48f * d, bc)
        label("BOOST", bc.x, bc.y + 5f * d, 14f * d, Paint.Align.CENTER, bold = true)

        // length
        label("Your length: ${(player.mass * 10).toInt()}", 24f * d, 40f * d, 18f * d, Paint.Align.LEFT, bold = true)

        // leaderboard
        val list = ArrayList<Pair<String, Int>>()
        if (!dead) list.add("You" to (player.mass * 10).toInt())
        for (b in bots) list.add(b.name to (b.mass * 10).toInt())
        list.sortByDescending { it.second }
        var y = 40f * d
        label("Leaderboard", viewW - 24f * d, y, 15f * d, Paint.Align.RIGHT, bold = true)
        for (i in 0 until min(6, list.size)) {
            y += 24f * d
            val e = list[i]
            label(
                "${i + 1}. ${e.first}  ${e.second}",
                viewW - 24f * d, y, 14f * d, Paint.Align.RIGHT,
                if (e.first == "You") YELLOW else WHITE
            )
        }
    }

    private fun DrawScope.drawGameOver() {
        val d = dens
        drawRect(Color.Black.copy(alpha = 0.55f))
        val cx = viewW / 2f
        val cy = viewH / 2f
        label("Game Over", cx, cy - 20f * d, 44f * d, Paint.Align.CENTER, bold = true)
        label("Your length: ${(player.mass * 10).toInt()}    Best: $best", cx, cy + 20f * d, 20f * d, Paint.Align.CENTER)
        if (deadTime > 0.8f) label("Tap to play again", cx, cy + 60f * d, 20f * d, Paint.Align.CENTER, YELLOW)
    }
}