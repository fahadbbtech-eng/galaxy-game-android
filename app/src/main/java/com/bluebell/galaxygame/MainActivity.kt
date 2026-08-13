package com.bluebell.galaxygame

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * The "entry point" of the app -- Android launches this class first.
 * All it does is hand the whole screen over to our GameView, which
 * owns the actual game loop (see GameView.kt for the real logic).
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(GameView(this))
    }
}
