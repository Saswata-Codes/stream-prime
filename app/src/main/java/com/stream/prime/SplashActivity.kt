package com.stream.prime

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.stream.prime.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.versionText.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)

        // Hide action bar for full-screen experience
        supportActionBar?.hide()

        // Start animations
        startAnimations()

        // Navigate to MainActivity after delay
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }, 3000) // 3 seconds delay
    }

    private fun startAnimations() {
        // Logo animation - scale and fade in
        val logoScaleX = ObjectAnimator.ofFloat(binding.logoContainer, "scaleX", 0f, 1f)
        val logoScaleY = ObjectAnimator.ofFloat(binding.logoContainer, "scaleY", 0f, 1f)
        val logoAlpha = ObjectAnimator.ofFloat(binding.logoContainer, "alpha", 0f, 1f)

        // Title animation - slide up and fade in
        val titleTranslateY = ObjectAnimator.ofFloat(binding.appTitle, "translationY", 50f, 0f)
        val titleAlpha = ObjectAnimator.ofFloat(binding.appTitle, "alpha", 0f, 1f)

        // Subtitle animation - slide up and fade in
        val subtitleTranslateY = ObjectAnimator.ofFloat(binding.appSubtitle, "translationY", 30f, 0f)
        val subtitleAlpha = ObjectAnimator.ofFloat(binding.appSubtitle, "alpha", 0f, 1f)

        // Loading dots animation
        val dot1Alpha = ObjectAnimator.ofFloat(binding.dot1, "alpha", 0f, 1f)
        val dot2Alpha = ObjectAnimator.ofFloat(binding.dot2, "alpha", 0f, 1f)
        val dot3Alpha = ObjectAnimator.ofFloat(binding.dot3, "alpha", 0f, 1f)

        // Create animation sets
        val logoAnimator = AnimatorSet().apply {
            playTogether(logoScaleX, logoScaleY, logoAlpha)
            duration = 1000
            interpolator = AccelerateDecelerateInterpolator()
        }

        val textAnimator = AnimatorSet().apply {
            playTogether(titleTranslateY, titleAlpha, subtitleTranslateY, subtitleAlpha)
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
        }

        val dotsAnimator = AnimatorSet().apply {
            playSequentially(
                ObjectAnimator.ofFloat(binding.dot1, "alpha", 0f, 1f).apply { duration = 300 },
                ObjectAnimator.ofFloat(binding.dot2, "alpha", 0f, 1f).apply { duration = 300 },
                ObjectAnimator.ofFloat(binding.dot3, "alpha", 0f, 1f).apply { duration = 300 }
            )
        }

        // Start animations with delays
        logoAnimator.start()
        
        Handler(Looper.getMainLooper()).postDelayed({
            textAnimator.start()
        }, 500)

        Handler(Looper.getMainLooper()).postDelayed({
            dotsAnimator.start()
        }, 1200)

        // Continuous loading animation
        startContinuousLoadingAnimation()
    }

    private fun startContinuousLoadingAnimation() {
        val handler = Handler(Looper.getMainLooper())
        var currentDot = 0

        val runnable = object : Runnable {
            override fun run() {
                // Reset all dots
                binding.dot1.alpha = 0.3f
                binding.dot2.alpha = 0.3f
                binding.dot3.alpha = 0.3f

                // Highlight current dot
                when (currentDot) {
                    0 -> binding.dot1.alpha = 1.0f
                    1 -> binding.dot2.alpha = 1.0f
                    2 -> binding.dot3.alpha = 1.0f
                }

                currentDot = (currentDot + 1) % 3
                handler.postDelayed(this, 500)
            }
        }

        handler.postDelayed(runnable, 1500)
    }
} 
