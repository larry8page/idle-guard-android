package com.larry8page.idleguard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * 息屏执行前的语音倒计时提示(用打包进 App 的预录音频,彻底不依赖系统 TTS 引擎)。
 *
 * 流程:先播开场白"即将十秒息屏,倒计时开始"(约 3.3s),停顿到开场白结束后,
 * 每秒播放一个数字音频 10、9、…、1;数到 0 触发 [onDone]。任意时刻调用 [stop]
 * 即取消(用于用户触屏打断这一轮)。若某数字素材没加载上,退化为一次滴声,保证不静音。
 *
 * 走 USAGE_ASSISTANCE_ACCESSIBILITY 音频流:即使媒体被静音也仍能播出。
 * 所有回调在主线程。
 */
class Alert(ctx: Context) {

    private val app = ctx.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val introId = runCatching { pool.load(app, R.raw.alert_intro, 1) }.getOrDefault(0)
    private val digitIds = HashMap<Int, Int>()

    init {
        for (n in 1..10) {
            runCatching { pool.load(app, resFor(n), 1) }.getOrNull()?.let { digitIds[n] = it }
        }
    }

    private fun resFor(n: Int): Int = when (n) {
        1 -> R.raw.count_1
        2 -> R.raw.count_2
        3 -> R.raw.count_3
        4 -> R.raw.count_4
        5 -> R.raw.count_5
        6 -> R.raw.count_6
        7 -> R.raw.count_7
        8 -> R.raw.count_8
        9 -> R.raw.count_9
        else -> R.raw.count_10
    }

    /** 是否有可用音频素材(开场白或数字任一加载成功)。 */
    fun hasVoice(): Boolean = introId != 0 || digitIds.isNotEmpty()

    @Volatile private var active = false
    private var curStream = 0

    /**
     * 开始倒计时:播开场白 → 停顿 → 每秒念数字 → 数到 0 回调 [onDone]。
     * [stop] 会中断且不触发 [onDone]。
     */
    fun startCountdown(seconds: Int, onDone: () -> Unit) {
        stop()
        active = true
        runCatching { if (introId != 0) curStream = pool.play(introId, 1f, 1f, 1, 0, 1f) }
        var n = seconds
        val tick = object : Runnable {
            override fun run() {
                if (!active) return
                if (n <= 0) { active = false; onDone(); return }
                playDigit(n)
                n -= 1
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(tick, INTRO_DELAY_MS) // 等开场白(~3.3s)播完再开始念数字
    }

    private fun playDigit(n: Int) {
        val id = digitIds[n]
        if (id != null) {
            runCatching { curStream = pool.play(id, 1f, 1f, 1, 0, 1f) }
        } else {
            runCatching {
                val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 95)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                handler.postDelayed({ runCatching { tg.release() } }, 400)
            }
        }
    }

    /** 停止倒计时与播报(不触发 onDone)。 */
    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        if (curStream != 0) { runCatching { pool.stop(curStream) }; curStream = 0 }
    }

    fun shutdown() {
        stop()
        runCatching { pool.release() }
    }

    companion object { private const val INTRO_DELAY_MS = 3400L }
}
