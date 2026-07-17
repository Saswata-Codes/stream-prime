package com.stream.prime.screen

/**
 * Keeps microphone mute and slider state atomic. A muted microphone is represented by a locked
 * zero-percent slider while [restorePercent] remembers the exact user level to restore.
 */
internal object MicrophoneMuteVolumePolicy {

    data class State(
        val volumePercent: Int,
        val restorePercent: Int,
        val muted: Boolean
    ) {
        val sliderEnabled: Boolean get() = !muted
    }

    fun fromStored(
        volumePercent: Int,
        restorePercent: Int?,
        muted: Boolean
    ): State {
        val volume = volumePercent.coerceIn(0, 100)
        val restore = (restorePercent ?: volume).coerceIn(0, 100)
        return if (muted) {
            // Migrate the old representation, where mute could be true while the saved slider
            // still contained a non-zero value, without losing that value.
            State(
                volumePercent = 0,
                restorePercent = if (restorePercent == null) volume else restore,
                muted = true
            )
        } else {
            State(volumePercent = volume, restorePercent = volume, muted = false)
        }
    }

    fun setMuted(state: State, muted: Boolean): State {
        if (state.muted == muted) return state
        return if (muted) {
            State(volumePercent = 0, restorePercent = state.volumePercent, muted = true)
        } else {
            State(
                volumePercent = state.restorePercent,
                restorePercent = state.restorePercent,
                muted = false
            )
        }
    }

    fun setVolume(state: State, volumePercent: Int): State {
        if (state.muted) return state
        val volume = volumePercent.coerceIn(0, 100)
        return State(volumePercent = volume, restorePercent = volume, muted = false)
    }
}
