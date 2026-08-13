package com.bluebell.galaxygame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.random.Random

/**
 * ============================================================
 * STAGE 1 + 2 + 3 of the "Galaxy Game" plan:
 *   Stage 1 - forked from Dodge Game (same loop/thread/surface setup).
 *   Stage 2 - swapped the paddle's left/right-only drag for a full
 *             2D "joystick" style: the plane follows your finger
 *             wherever you drag it on screen, with a little easing
 *             so it glides instead of teleporting.
 *   Stage 3 - two-layer PARALLAX starfield (far layer = small, slow,
 *             dim; near layer = bigger, faster, brighter). Same trick
 *             Alto's Odyssey uses for its background mountains -- two
 *             flat layers scrolling at different speeds read as depth
 *             even though everything is still 2D.
 *
 * Everything else (spawn/update/draw loop, collision detection,
 * score, restart-on-tap) is the same shape as Dodge Game -- only
 * the control scheme and the visual theme (starfield + plane +
 * asteroids instead of paddle + blocks) have changed so far.
 *
 * Later stages (not yet added): shooting, enemy patterns, lives,
 * difficulty ramp, particle effects.
 * ============================================================
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private var gameThread: Thread? = null
    private var isPlaying = false

    // --- Plane (the player) ---
    // planeX/planeY are where the plane is actually drawn.
    // targetX/targetY are where the finger currently is.
    // Each frame the plane eases toward the target instead of
    // snapping straight to it -- that's what makes it feel like
    // flying rather than dragging a cutout around.
    private var planeX = 0f
    private var planeY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var touching = false
    private val planeSize = 70f
    private val easing = 0.18f // higher = snappier, lower = floatier

    // --- Falling obstacles (asteroids / enemies) ---
    data class Obstacle(var x: Float, var y: Float, val size: Float, var speed: Float)
    private val obstacles = mutableListOf<Obstacle>()
    private var framesSinceLastObstacle = 0

    // --- Parallax starfield background ---
    // Two layers moving at different speeds create the illusion of depth
    // (the same trick Alto's Odyssey uses for its background mountains),
    // even though every star is still just a flat 2D dot.
    data class Star(var x: Float, var y: Float, val size: Float, var speed: Float, val brightness: Int)
    private val farStars = mutableListOf<Star>()
    private val nearStars = mutableListOf<Star>()

    private var score = 0
    private var gameOver = false
    private var initialized = false

    private val paint = Paint().apply { isAntiAlias = true }

    init {
        holder.addCallback(this)
    }

    // --- SurfaceHolder.Callback: Android calls these automatically ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        isPlaying = true
        gameThread = Thread(this)
        gameThread!!.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isPlaying = false
        gameThread?.join()
    }

    override fun run() {
        while (isPlaying) {
            update()
            draw()
            Thread.sleep(16)
        }
    }

    /** Set up the plane position and starfield once we know the screen size. */
    private fun setupIfNeeded() {
        if (initialized || width == 0 || height == 0) return
        planeX = width / 2f
        planeY = height - 250f
        targetX = planeX
        targetY = planeY

        farStars.clear()
        repeat(50) {
            farStars.add(
                Star(
                    x = Random.nextFloat() * width,
                    y = Random.nextFloat() * height,
                    size = Random.nextFloat() * 2f + 1f,
                    speed = Random.nextFloat() * 2f + 1f, // slow = feels distant
                    brightness = 110
                )
            )
        }

        nearStars.clear()
        repeat(35) {
            nearStars.add(
                Star(
                    x = Random.nextFloat() * width,
                    y = Random.nextFloat() * height,
                    size = Random.nextFloat() * 3.5f + 2f,
                    speed = Random.nextFloat() * 7f + 6f, // fast = feels close
                    brightness = 255
                )
            )
        }
        initialized = true
    }

    private fun update() {
        setupIfNeeded()
        if (!initialized || gameOver) return

        // Scroll both parallax layers -- purely cosmetic, gives a sense of
        // speed and depth. Far layer drifts slowly, near layer rushes by.
        for (star in farStars) {
            star.y += star.speed
            if (star.y > height) {
                star.y = 0f
                star.x = Random.nextFloat() * width
            }
        }
        for (star in nearStars) {
            star.y += star.speed
            if (star.y > height) {
                star.y = 0f
                star.x = Random.nextFloat() * width
            }
        }

        // Ease the plane toward wherever the finger currently is. This is
        // the "joystick feel": planeX/planeY chase targetX/targetY a
        // fraction of the remaining distance every frame.
        if (touching) {
            planeX += (targetX - planeX) * easing
            planeY += (targetY - planeY) * easing
            planeX = planeX.coerceIn(planeSize, (width - planeSize).coerceAtLeast(planeSize))
            planeY = planeY.coerceIn(planeSize, (height - planeSize).coerceAtLeast(planeSize))
        }

        // Spawn a new obstacle every ~35 frames (a bit faster than Dodge Game).
        framesSinceLastObstacle++
        if (framesSinceLastObstacle > 35) {
            framesSinceLastObstacle = 0
            val size = Random.nextInt(50, 100).toFloat()
            val x = Random.nextFloat() * (width - size).coerceAtLeast(1f)
            val speed = Random.nextInt(12, 24).toFloat()
            obstacles.add(Obstacle(x, -size, size, speed))
        }

        val planeRect = RectF(
            planeX - planeSize / 2, planeY - planeSize / 2,
            planeX + planeSize / 2, planeY + planeSize / 2
        )

        val iterator = obstacles.iterator()
        while (iterator.hasNext()) {
            val obstacle = iterator.next()
            obstacle.y += obstacle.speed

            val obstacleRect = RectF(
                obstacle.x, obstacle.y,
                obstacle.x + obstacle.size, obstacle.y + obstacle.size
            )

            if (RectF.intersects(planeRect, obstacleRect)) {
                gameOver = true
            }

            if (obstacle.y > height) {
                iterator.remove()
                score++
            }
        }
    }

    private fun draw() {
        if (!holder.surface.isValid) return
        val canvas: Canvas = holder.lockCanvas()

        // Deep space background.
        canvas.drawColor(Color.rgb(8, 8, 20))

        // Parallax starfield -- far layer drawn first (behind), near layer
        // drawn second (in front), each at its own brightness/size/speed.
        for (star in farStars) {
            paint.color = Color.rgb(star.brightness, star.brightness, star.brightness)
            canvas.drawCircle(star.x, star.y, star.size, paint)
        }
        for (star in nearStars) {
            paint.color = Color.rgb(star.brightness, star.brightness, star.brightness)
            canvas.drawCircle(star.x, star.y, star.size, paint)
        }

        // Score text.
        paint.color = Color.WHITE
        paint.textSize = 60f
        canvas.drawText("Score: $score", 40f, 100f, paint)

        // Asteroids/enemies.
        paint.color = Color.rgb(200, 90, 70)
        for (obstacle in obstacles) {
            canvas.drawOval(
                obstacle.x, obstacle.y,
                obstacle.x + obstacle.size, obstacle.y + obstacle.size,
                paint
            )
        }

        // Plane -- a simple triangle pointing up, easy to swap for a
        // sprite/bitmap later without touching the movement logic.
        if (initialized) {
            paint.color = Color.rgb(90, 200, 230)
            val path = Path().apply {
                moveTo(planeX, planeY - planeSize / 2)                 // nose
                lineTo(planeX - planeSize / 2, planeY + planeSize / 2) // left wing
                lineTo(planeX, planeY + planeSize / 4)                 // tail notch
                lineTo(planeX + planeSize / 2, planeY + planeSize / 2) // right wing
                close()
            }
            canvas.drawPath(path, paint)
        }

        if (gameOver) {
            paint.color = Color.WHITE
            paint.textSize = 80f
            canvas.drawText("GAME OVER", width / 2f - 260f, height / 2f, paint)
            paint.textSize = 45f
            canvas.drawText("Tap to restart", width / 2f - 170f, height / 2f + 70f, paint)
        }

        holder.unlockCanvasAndPost(canvas)
    }

    /**
     * Touch input -- this is the whole "joystick" control scheme.
     * Wherever your finger is (on ACTION_DOWN or ACTION_MOVE) becomes
     * the target the plane eases toward, in both X and Y at once.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gameOver) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                obstacles.clear()
                score = 0
                gameOver = false
                planeX = width / 2f
                planeY = height - 250f
                targetX = planeX
                targetY = planeY
            }
            return true
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touching = true
                targetX = event.x
                targetY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
            }
        }
        return true
    }
}
