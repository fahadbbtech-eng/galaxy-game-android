package com.bluebell.galaxygame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.sin
import kotlin.random.Random

/**
 * ============================================================
 * STAGE 1 + 2 + 3 + 4 + 5 + 6 of the "Galaxy Game" plan:
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
 *   Stage 4 - SHOOTING. Since your one finger is already busy steering
 *             the plane (stage 2's joystick drag), shooting is AUTO-FIRE
 *             rather than a second tap gesture: the plane fires straight
 *             up on a timer. Bullets that hit an obstacle destroy it and
 *             award bonus score, on top of the score you already get for
 *             successfully dodging obstacles that fall past you.
 *   Stage 5 - LIVES. One hit used to end the run instantly (like Dodge
 *             Game). Now the plane has 3 lives: getting hit costs one
 *             life, clears nearby obstacles so you're not hit again on
 *             the very next frame, and gives you a couple seconds of
 *             flashing invincibility to recover. Game over only happens
 *             once all lives are gone.
 *   Stage 6 - DIFFICULTY RAMP. Obstacles used to spawn at one constant
 *             rate and speed range for the whole run, which gets stale
 *             fast. Now both scale up smoothly the longer you survive:
 *             obstacles spawn more often and fall faster over time, up
 *             to a capped maximum so it never becomes unfair/impossible.
 *   Stage 7 - SOUND. Five short effects (shoot, explosion, hit, dodge
 *             "point", game over), synthesized as plain WAV files rather
 *             than pulled from anywhere external, played through a
 *             SoundPool so several can overlap without cutting each
 *             other off (e.g. a shot and an explosion at the same time).
 *   Stage 8 - ENEMY VARIETY. Obstacles used to be one shape that always
 *             fell straight down. Now there are three kinds: the plain
 *             asteroid from before, a DRIFTER that weaves side to side
 *             as it falls (needs real dodging, not just watching one
 *             axis), and a tougher TANK that takes two bullet hits to
 *             destroy instead of one. Which kinds can spawn widens as
 *             difficulty increases, so early runs stay simple.
 *   Stage 9 - PARTICLES. Destroying something or getting hit used to be
 *             silent-looking -- the shape just vanished. Now both spawn
 *             a small burst of fading debris/spark particles (orange
 *             for explosions, blue-white for the plane getting hit),
 *             which is what actually makes the sound from stage 7 feel
 *             connected to something happening on screen.
 *   Stage 10 - POWER-UPS. A glowing gold star spawns and falls every time
 *              your score crosses another 300-point milestone. Flying
 *              into it (no shooting needed) grants Rapid Fire for about
 *              8 seconds -- the auto-fire timer from stage 4 fires twice
 *              as often while it's active, shown by a countdown chip and
 *              a gold outline around the plane.
 *
 * Everything else (spawn/update/draw loop, collision detection,
 * score, restart-on-tap) is the same shape as Dodge Game -- only
 * the control scheme and the visual theme (starfield + plane +
 * asteroids instead of paddle + blocks) have changed so far.
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
    // Stage 8: three kinds instead of one plain shape.
    //  ASTEROID - the original: straight down, one bullet hit to destroy.
    //  DRIFTER   - weaves side to side while falling (real 2D dodging).
    //  TANK      - bigger and tougher, needs two bullet hits.
    enum class ObstacleType { ASTEROID, DRIFTER, TANK }
    data class Obstacle(
        var x: Float,
        var y: Float,
        val size: Float,
        var speed: Float,
        val type: ObstacleType,
        var hitsRemaining: Int,
        var driftPhase: Float = 0f
    )
    private val obstacles = mutableListOf<Obstacle>()
    private var framesSinceLastObstacle = 0
    private val driftAmplitude = 3.5f

    // --- Bullets (stage 4: auto-fire) ---
    data class Bullet(var x: Float, var y: Float)
    private val bullets = mutableListOf<Bullet>()
    private var framesSinceLastShot = 0
    private val fireIntervalFrames = 12 // ~5 shots/sec at 60fps
    private val bulletSpeed = 26f
    private val bulletWidth = 6f
    private val bulletHeight = 22f
    private val bulletScore = 5

    // --- Parallax starfield background ---
    // Two layers moving at different speeds create the illusion of depth
    // (the same trick Alto's Odyssey uses for its background mountains),
    // even though every star is still just a flat 2D dot.
    data class Star(var x: Float, var y: Float, val size: Float, var speed: Float, val brightness: Int)
    private val farStars = mutableListOf<Star>()
    private val nearStars = mutableListOf<Star>()

    // --- Lives (stage 5) ---
    private val startingLives = 3
    private var lives = startingLives
    private var invincibleFrames = 0
    private val invincibilityDurationFrames = 120 // ~2 seconds at 60fps

    // --- Difficulty ramp (stage 6) ---
    // framesSurvived counts up every frame the run is alive; difficulty
    // is derived from it so obstacles spawn faster and fall quicker the
    // longer you last, capped so it never becomes unfair.
    private var framesSurvived = 0
    private val rampDurationFrames = 3600 // ~60 seconds to reach max difficulty
    private val baseSpawnIntervalFrames = 35
    private val minSpawnIntervalFrames = 14
    private val baseSpeedRange = 12..24
    private val maxSpeedRange = 22..40

    // --- Particles (stage 9) ---
    // Small fading dots flung outward from wherever something just
    // happened. vx/vy are constant per particle (no gravity needed for
    // a space setting); life counts down each frame and drives both
    // removal and the fade-out alpha in draw().
    data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Int,
        val maxLife: Int,
        val color: Int
    )
    private val particles = mutableListOf<Particle>()

    private fun spawnBurst(x: Float, y: Float, color: Int, count: Int = 14, maxLife: Int = 26) {
        repeat(count) {
            val angle = Random.nextFloat() * 6.28f
            val speed = Random.nextFloat() * 6f + 2f
            particles.add(
                Particle(
                    x = x, y = y,
                    vx = kotlin.math.cos(angle) * speed,
                    vy = kotlin.math.sin(angle) * speed,
                    life = maxLife, maxLife = maxLife,
                    color = color
                )
            )
        }
    }

    // --- Power-ups (stage 10) ---
    // A pickup spawns every time score crosses another multiple of
    // pointsPerPowerUp. It falls like an obstacle but is harmless --
    // flying into it (or shooting it) collects it, no dodging needed.
    data class PowerUp(var x: Float, var y: Float)
    private val powerUps = mutableListOf<PowerUp>()
    private val pointsPerPowerUp = 300
    private var nextPowerUpScore = pointsPerPowerUp
    private val powerUpSize = 44f
    private val powerUpSpeed = 9f
    private var rapidFireFramesRemaining = 0
    private val rapidFireDurationFrames = 480 // ~8 seconds at 60fps

    private var score = 0
    private var gameOver = false
    private var initialized = false

    private val paint = Paint().apply { isAntiAlias = true }

    // --- Sound (stage 7) ---
    // A SoundPool (rather than MediaPlayer) is the right tool here because
    // it lets several short effects overlap -- e.g. a shot and an
    // explosion landing in the same frame don't cut each other off.
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val soundShoot = soundPool.load(context, R.raw.shoot, 1)
    private val soundExplosion = soundPool.load(context, R.raw.explosion, 1)
    private val soundHit = soundPool.load(context, R.raw.hit, 1)
    private val soundPoint = soundPool.load(context, R.raw.point, 1)
    private val soundGameOver = soundPool.load(context, R.raw.gameover, 1)
    private val soundPowerUp = soundPool.load(context, R.raw.powerup, 1)

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
        soundPool.release()
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

        // Move and age out particles. Removed the moment their life hits
        // zero; draw() fades their alpha down as life approaches zero.
        val particleIterator = particles.iterator()
        while (particleIterator.hasNext()) {
            val particle = particleIterator.next()
            particle.x += particle.vx
            particle.y += particle.vy
            particle.life--
            if (particle.life <= 0) particleIterator.remove()
        }

        // Count down invincibility after getting hit -- while this is
        // above zero the plane can't be hit again and flashes in draw().
        if (invincibleFrames > 0) invincibleFrames--

        // Count down rapid fire, granted by collecting a power-up below.
        if (rapidFireFramesRemaining > 0) rapidFireFramesRemaining--

        // Spawn a power-up every time score crosses another 300-point
        // milestone. A while loop (not if) so a big multi-kill frame that
        // jumps past more than one milestone still spawns each of them.
        while (score >= nextPowerUpScore) {
            val x = Random.nextFloat() * (width - powerUpSize).coerceAtLeast(1f)
            powerUps.add(PowerUp(x, -powerUpSize))
            nextPowerUpScore += pointsPerPowerUp
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

        // Difficulty ramps up smoothly from 0 (run just started) to 1
        // (max difficulty) over rampDurationFrames, then holds at 1.
        framesSurvived++
        val difficulty = (framesSurvived.toFloat() / rampDurationFrames).coerceIn(0f, 1f)

        // Spawn obstacles faster (shorter interval) and let them fall
        // quicker (wider/faster speed range) as difficulty climbs.
        val spawnIntervalFrames = (baseSpawnIntervalFrames -
            (baseSpawnIntervalFrames - minSpawnIntervalFrames) * difficulty).toInt()
        val speedMin = (baseSpeedRange.first + (maxSpeedRange.first - baseSpeedRange.first) * difficulty).toInt()
        val speedMax = (baseSpeedRange.last + (maxSpeedRange.last - baseSpeedRange.last) * difficulty).toInt()

        framesSinceLastObstacle++
        if (framesSinceLastObstacle > spawnIntervalFrames) {
            framesSinceLastObstacle = 0

            // Which enemy kinds can spawn widens as difficulty climbs, so
            // early runs only see the plain asteroid. Drifters unlock
            // fairly early (they're not harder, just need real dodging);
            // tanks only start appearing once things have ramped up some.
            val roll = Random.nextFloat()
            val type = when {
                difficulty > 0.35f && roll < 0.15f -> ObstacleType.TANK
                difficulty > 0.1f && roll < 0.45f -> ObstacleType.DRIFTER
                else -> ObstacleType.ASTEROID
            }

            val size = if (type == ObstacleType.TANK) {
                Random.nextInt(90, 130).toFloat()
            } else {
                Random.nextInt(50, 100).toFloat()
            }
            val x = Random.nextFloat() * (width - size).coerceAtLeast(1f)
            val speed = Random.nextInt(speedMin, speedMax + 1).toFloat()
            val hits = if (type == ObstacleType.TANK) 2 else 1
            obstacles.add(Obstacle(x, -size, size, speed, type, hits, driftPhase = Random.nextFloat() * 6.28f))
        }

        // Auto-fire: while the plane is on screen, it fires straight up on
        // a timer. No separate tap needed, since the one finger you have
        // is already busy steering (stage 2's joystick drag). Rapid Fire
        // (stage 10) halves the interval, so it fires twice as often.
        val currentFireInterval = if (rapidFireFramesRemaining > 0) fireIntervalFrames / 2 else fireIntervalFrames
        framesSinceLastShot++
        if (framesSinceLastShot >= currentFireInterval) {
            framesSinceLastShot = 0
            bullets.add(Bullet(planeX, planeY - planeSize / 2))
            soundPool.play(soundShoot, 0.5f, 0.5f, 0, 0, 1f)
        }

        // Power-ups fall straight down; flying into one collects it
        // immediately (unlike obstacles, there's nothing to dodge here).
        val planeRectForPowerUps = RectF(
            planeX - planeSize / 2, planeY - planeSize / 2,
            planeX + planeSize / 2, planeY + planeSize / 2
        )
        val powerUpIterator = powerUps.iterator()
        while (powerUpIterator.hasNext()) {
            val powerUp = powerUpIterator.next()
            powerUp.y += powerUpSpeed

            val powerUpRect = RectF(
                powerUp.x, powerUp.y,
                powerUp.x + powerUpSize, powerUp.y + powerUpSize
            )
            if (RectF.intersects(planeRectForPowerUps, powerUpRect)) {
                rapidFireFramesRemaining = rapidFireDurationFrames
                soundPool.play(soundPowerUp, 0.7f, 0.7f, 0, 0, 1f)
                spawnBurst(
                    powerUp.x + powerUpSize / 2, powerUp.y + powerUpSize / 2,
                    Color.rgb(255, 220, 90), count = 16, maxLife = 24
                )
                powerUpIterator.remove()
                continue
            }
            if (powerUp.y > height) {
                powerUpIterator.remove()
            }
        }

        // Move bullets upward and drop any that fly off the top of the screen.
        val bulletIterator = bullets.iterator()
        while (bulletIterator.hasNext()) {
            val bullet = bulletIterator.next()
            bullet.y -= bulletSpeed
            if (bullet.y + bulletHeight < 0) {
                bulletIterator.remove()
            }
        }

        val planeRect = RectF(
            planeX - planeSize / 2, planeY - planeSize / 2,
            planeX + planeSize / 2, planeY + planeSize / 2
        )

        val obstacleIterator = obstacles.iterator()
        while (obstacleIterator.hasNext()) {
            val obstacle = obstacleIterator.next()
            obstacle.y += obstacle.speed

            // Drifters weave side to side as they fall -- a horizontal
            // sine wave layered on top of the normal straight-down fall.
            if (obstacle.type == ObstacleType.DRIFTER) {
                obstacle.driftPhase += 0.06f
                obstacle.x += sin(obstacle.driftPhase) * driftAmplitude
                obstacle.x = obstacle.x.coerceIn(0f, (width - obstacle.size).coerceAtLeast(0f))
            }

            val obstacleRect = RectF(
                obstacle.x, obstacle.y,
                obstacle.x + obstacle.size, obstacle.y + obstacle.size
            )

            // Check every live bullet against this obstacle. A hit removes
            // the bullet and chips one hit point off the obstacle; only
            // once hitsRemaining reaches zero does it actually get
            // destroyed and award bonus score (tanks take two hits).
            var hitByBullet = false
            val hitBulletIterator = bullets.iterator()
            while (hitBulletIterator.hasNext()) {
                val bullet = hitBulletIterator.next()
                val bulletRect = RectF(
                    bullet.x - bulletWidth / 2, bullet.y,
                    bullet.x + bulletWidth / 2, bullet.y + bulletHeight
                )
                if (RectF.intersects(bulletRect, obstacleRect)) {
                    hitBulletIterator.remove()
                    hitByBullet = true
                    break
                }
            }
            if (hitByBullet) {
                val obstacleCenterX = obstacle.x + obstacle.size / 2
                val obstacleCenterY = obstacle.y + obstacle.size / 2
                obstacle.hitsRemaining--
                if (obstacle.hitsRemaining <= 0) {
                    obstacleIterator.remove()
                    score += bulletScore
                    soundPool.play(soundExplosion, 0.7f, 0.7f, 0, 0, 1f)
                    spawnBurst(obstacleCenterX, obstacleCenterY, Color.rgb(255, 150, 60))
                } else {
                    // Tank took a hit but survived -- a lighter thud and a
                    // smaller spark burst, clearly less than a full kill.
                    soundPool.play(soundExplosion, 0.3f, 0.3f, 0, 0, 1.3f)
                    spawnBurst(obstacleCenterX, obstacleCenterY, Color.rgb(255, 210, 120), count = 6, maxLife = 14)
                }
                continue
            }

            if (invincibleFrames <= 0 && RectF.intersects(planeRect, obstacleRect)) {
                lives--
                soundPool.play(soundHit, 0.8f, 0.8f, 0, 0, 1f)
                spawnBurst(planeX, planeY, Color.rgb(160, 220, 255), count = 20, maxLife = 30)
                if (lives <= 0) {
                    gameOver = true
                    soundPool.play(soundGameOver, 0.8f, 0.8f, 0, 0, 1f)
                } else {
                    // Invincibility alone (checked above) stops any further
                    // life loss for the next couple seconds, so there's no
                    // need to also clear nearby obstacles -- that would risk
                    // mutating this list while we're mid-iteration over it.
                    invincibleFrames = invincibilityDurationFrames
                }
                obstacleIterator.remove()
                continue
            }

            if (obstacle.y > height) {
                obstacleIterator.remove()
                score++
                soundPool.play(soundPoint, 0.4f, 0.4f, 0, 0, 1f)
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

        // Score and lives text.
        paint.color = Color.WHITE
        paint.textSize = 60f
        canvas.drawText("Score: $score", 40f, 100f, paint)
        paint.textSize = 40f
        canvas.drawText("Lives: " + "♥".repeat(lives.coerceAtLeast(0)), 40f, 155f, paint)
        if (rapidFireFramesRemaining > 0) {
            paint.color = Color.rgb(255, 220, 90)
            paint.textSize = 40f
            val secondsLeft = (rapidFireFramesRemaining / 60) + 1
            canvas.drawText("RAPID FIRE ${secondsLeft}s", 40f, 200f, paint)
        }

        // Asteroids/enemies -- color signals the type so the variety is
        // actually readable at a glance, not just different under the hood.
        for (obstacle in obstacles) {
            paint.color = when (obstacle.type) {
                ObstacleType.ASTEROID -> Color.rgb(200, 90, 70)  // rust red, as before
                ObstacleType.DRIFTER -> Color.rgb(210, 130, 230) // violet
                ObstacleType.TANK -> Color.rgb(140, 150, 160)    // gunmetal grey
            }
            canvas.drawOval(
                obstacle.x, obstacle.y,
                obstacle.x + obstacle.size, obstacle.y + obstacle.size,
                paint
            )
            // Tanks show a thin ring once they've taken their first hit,
            // so it's visible that the next shot will actually kill it.
            if (obstacle.type == ObstacleType.TANK && obstacle.hitsRemaining == 1) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.color = Color.rgb(255, 235, 120)
                canvas.drawOval(
                    obstacle.x + 6f, obstacle.y + 6f,
                    obstacle.x + obstacle.size - 6f, obstacle.y + obstacle.size - 6f,
                    paint
                )
                paint.style = Paint.Style.FILL
            }
        }

        // Particles -- fading debris/sparks from explosions and hits.
        // Alpha scales down with remaining life so they visibly dissolve
        // rather than just popping out of existence.
        for (particle in particles) {
            val alpha = (255 * (particle.life.toFloat() / particle.maxLife)).toInt().coerceIn(0, 255)
            paint.color = Color.argb(
                alpha,
                Color.red(particle.color), Color.green(particle.color), Color.blue(particle.color)
            )
            canvas.drawCircle(particle.x, particle.y, 5f, paint)
        }

        // Power-ups -- a glowing gold star (drawn as a simple 4-point
        // sparkle so it reads as "special" against the round obstacles).
        paint.color = Color.rgb(255, 220, 90)
        for (powerUp in powerUps) {
            val cx = powerUp.x + powerUpSize / 2
            val cy = powerUp.y + powerUpSize / 2
            val r = powerUpSize / 2
            canvas.drawCircle(cx, cy, r * 0.4f, paint)
            val star = Path().apply {
                moveTo(cx, cy - r)
                lineTo(cx + r * 0.18f, cy - r * 0.18f)
                lineTo(cx + r, cy)
                lineTo(cx + r * 0.18f, cy + r * 0.18f)
                lineTo(cx, cy + r)
                lineTo(cx - r * 0.18f, cy + r * 0.18f)
                lineTo(cx - r, cy)
                lineTo(cx - r * 0.18f, cy - r * 0.18f)
                close()
            }
            canvas.drawPath(star, paint)
        }

        // Bullets -- small glowing yellow slivers fired from the nose.
        paint.color = Color.rgb(255, 235, 120)
        for (bullet in bullets) {
            canvas.drawRoundRect(
                bullet.x - bulletWidth / 2, bullet.y,
                bullet.x + bulletWidth / 2, bullet.y + bulletHeight,
                bulletWidth / 2, bulletWidth / 2, paint
            )
        }

        // Plane -- a simple triangle pointing up, easy to swap for a
        // sprite/bitmap later without touching the movement logic. While
        // invincible it flashes by skipping every other frame's draw.
        val flashingHidden = invincibleFrames > 0 && (invincibleFrames / 4) % 2 == 0
        if (initialized && !flashingHidden) {
            val path = Path().apply {
                moveTo(planeX, planeY - planeSize / 2)                 // nose
                lineTo(planeX - planeSize / 2, planeY + planeSize / 2) // left wing
                lineTo(planeX, planeY + planeSize / 4)                 // tail notch
                lineTo(planeX + planeSize / 2, planeY + planeSize / 2) // right wing
                close()
            }
            // Rapid Fire active -- draw a glowing gold outline behind the
            // plane before filling it in, so the boost reads instantly.
            if (rapidFireFramesRemaining > 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 8f
                paint.color = Color.rgb(255, 220, 90)
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
            }
            paint.color = Color.rgb(90, 200, 230)
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
                bullets.clear()
                particles.clear()
                powerUps.clear()
                nextPowerUpScore = pointsPerPowerUp
                rapidFireFramesRemaining = 0
                framesSinceLastShot = 0
                lives = startingLives
                invincibleFrames = 0
                framesSurvived = 0
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
