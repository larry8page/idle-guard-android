package com.larry8page.idleguard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 息屏执行前的语音倒计时提示(替代原来的全屏弹窗)。
 *
 * 用系统 TextToSpeech 播报"即将N秒息屏，倒计时开始",随后每秒念一个数字 N、N-1…1,
 * 数到 0 触发 [Countdown.onDone]。若设备没有可用 TTS 引擎,则退回 ToneGenerator 滴声,
 * 保证"有声音提示"这一诉求不因缺引擎而失效。
 *
 * 所有回调都在主线程。cancel()/stop() 随时可中断(用于用户触屏取消这一轮)。
 */
class Alert(ctx: Context) {

    fun interface Countdown { fun onDone() }

    private val app = ctx.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var active = false

    init {
        runCatching {
            tts = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = runCatching {
                        tts?.setLanguage(Locale.CHINA)
                        tts?.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        true
                    }.getOrDefault(false)
                }
            }
        }
    }

    /** 是否有可用的语音引擎(供 UI/日志提示用)。 */
    fun hasVoice(): Boolean = ttsReady

    /**
     * 开始 [seconds] 秒倒计时。开场白先播,随后每秒念数字;数到 0 → [onDone]。
     * 期间调用 [stop] 即取消(不会触发 onDone)。
     */
    fun startCountdown(seconds: Int, onDone: () -> Unit) {
        stop()
        active = true
        if (ttsReady) say("即将${seconds}秒息屏，倒计时开始")
        var n = seconds
        val tick = object : Runnable {
            override fun run() {
                if (!active) return
                if (n <= 0) {
                    active = false
                    onDone()
                    return
                }
                if (ttsReady) say(n.toString()) else beep()
                n -= 1
                handler.postDelayed(this, 1000)
            }
        }
        // 让开场白先说一部分,再开始念数字;无引擎时几乎立即开始滴声
        handler.postDelayed(tick, if (ttsReady) 1700L else 500L)
    }

    private fun say(text: String) {
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "idle_guard_alert") }
    }

    private fun beep() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 95)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            handler.postDelayed({ runCatching { tg.release() } }, 400)
        }
    }

    /** 停止倒计时与播报(不触发 onDone)。 */
    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }
}
