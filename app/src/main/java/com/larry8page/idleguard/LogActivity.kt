package com.larry8page.idleguard

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Rolling event log viewer (newest first). */
class LogActivity : AppCompatActivity() {

    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        title = getString(R.string.title_log)

        logText = findViewById(R.id.logText)
        scroll = findViewById(R.id.logScroll)
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            Logger.clear(this)
            refresh()
        }
        findViewById<Button>(R.id.btnLogBack).setOnClickListener { finish() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val txt = Logger.readAll(this)
        logText.text = if (txt.isBlank()) "暂无日志。触发一次或放置到点后，这里会出现记录。" else txt
        scroll.post { scroll.fullScroll(View.FOCUS_UP) }
    }
}
