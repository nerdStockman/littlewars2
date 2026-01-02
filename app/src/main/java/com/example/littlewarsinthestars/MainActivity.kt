package com.example.littlewarsinthestars

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.simcore.GameState

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var state = GameState()

    private lateinit var simText: TextView

    private val tickRunnable = object : Runnable {
        override fun run() {
            // Step sim (deterministic core; UI drives timing for now)
            state = state.step()

            // Display
            simText.text = "tick=${state.tick}  hash=${state.stateHash()}"

            // Schedule next tick (~60 Hz)
            handler.postDelayed(this, 16L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)
        simText = findViewById(R.id.simText)
    }


    override fun onStart() {
        super.onStart()
        handler.post(tickRunnable)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tickRunnable)
    }
}
