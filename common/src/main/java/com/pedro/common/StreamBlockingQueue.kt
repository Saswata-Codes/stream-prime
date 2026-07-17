package com.pedro.common

import com.pedro.common.frame.MediaFrame
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class StreamBlockingQueue(private val capacity: Int) {

    data class OfferResult(
        val accepted: Boolean,
        val evictedAudioFrames: Int = 0,
        val evictedVideoFrames: Int = 0
    )

    companion object {
        // A live sender must recover by sacrificing stale inter-frames, not by allowing its
        // audio latency to grow without limit behind old video.
        private const val MAX_LIVE_QUEUE_DURATION_US = 750_000L
    }

    init {
        require(capacity > 0) { "Queue capacity must be greater than zero" }
    }

    private val queue = PriorityBlockingQueue<MediaFrame>(capacity) { p0, p1 ->
        p0.info.timestamp.compare(p1.info.timestamp)
    }
    private var cacheQueue = PriorityBlockingQueue<MediaFrame>(200) { p0, p1 ->
        p0.info.timestamp.compare(p1.info.timestamp)
    }
    private var cacheTimeFilled = AtomicBoolean(false)
    private var cacheTime = 0L
    private var startTs = 0L

    @Synchronized
    fun trySend(item: MediaFrame): OfferResult {
        if (cacheTime > 0 && !cacheTimeFilled.get()) {
            if (startTs == 0L) startTs = TimeUtils.getCurrentTimeMillis()
            val t = TimeUtils.getCurrentTimeMillis() - startTs
            if (t >= cacheTime) cacheTimeFilled.set(true)
        }
        return try {
            val queuedItem = if (cacheTime > 0) {
                cacheQueue.add(item)
                if (cacheTimeFilled.get()) cacheQueue.poll() else null
            } else item

            // The item is intentionally retained in the configured startup cache until its delay
            // has elapsed. It has not been dropped.
            if (queuedItem == null) return OfferResult(accepted = true)

            var evictedVideoFrames = 0
            var evictedAudioFrames = 0

            if (queuedItem.type == MediaFrame.Type.AUDIO) {
                val staleBeforeUs = queuedItem.info.timestamp - MAX_LIVE_QUEUE_DURATION_US
                if (staleBeforeUs > 0L) {
                    // Preserve keyframes so the remaining recent video is still decodable. Old
                    // inter-frames are the expensive backlog that makes live audio arrive late.
                    val staleVideoFrames = queue.filter {
                        it.type == MediaFrame.Type.VIDEO &&
                            !it.info.isKeyFrame &&
                            it.info.timestamp < staleBeforeUs
                    }
                    staleVideoFrames.forEach {
                        if (queue.remove(it)) evictedVideoFrames++
                    }
                }
            }

            if (queue.size >= capacity && queuedItem.type == MediaFrame.Type.AUDIO) {
                // Always make room for live audio by evicting the oldest disposable video frame.
                val videoVictim = queue
                    .asSequence()
                    .filter { it.type == MediaFrame.Type.VIDEO && !it.info.isKeyFrame }
                    .minByOrNull { it.info.timestamp }
                if (videoVictim != null && queue.remove(videoVictim)) evictedVideoFrames++
            }

            if (queue.size >= capacity && queuedItem.type == MediaFrame.Type.AUDIO) {
                // An audio-only backlog should not create many seconds of permanent latency.
                val audioVictim = queue
                    .asSequence()
                    .filter { it.type == MediaFrame.Type.AUDIO }
                    .minByOrNull { it.info.timestamp }
                if (audioVictim != null && queue.remove(audioVictim)) evictedAudioFrames++
            }

            if (queue.size >= capacity) {
                OfferResult(
                    accepted = false,
                    evictedAudioFrames = evictedAudioFrames,
                    evictedVideoFrames = evictedVideoFrames
                )
            } else {
                queue.add(queuedItem)
                OfferResult(
                    accepted = true,
                    evictedAudioFrames = evictedAudioFrames,
                    evictedVideoFrames = evictedVideoFrames
                )
            }
        } catch (e: IllegalStateException) {
            OfferResult(accepted = false)
        }
    }

    fun take(): MediaFrame {
        return queue.take()
    }

    fun remainingCapacity(): Int = (capacity - queue.size).coerceAtLeast(0)

    fun drainTo(destiny: StreamBlockingQueue) {
        queue.drainTo(destiny.queue)
        cacheQueue.drainTo(destiny.cacheQueue)
    }

    fun clear() {
        queue.clear()
        cacheQueue.clear()
        startTs = 0L
        cacheTimeFilled.set(false)
    }

    fun setCacheTime(cache: Long) {
        cacheTime = cache
        cacheQueue = PriorityBlockingQueue<MediaFrame>((cache / 5).toInt()) { p0, p1 ->
            p0.info.timestamp.compare(p1.info.timestamp)
        }
    }

    fun getSize() = queue.size
}
