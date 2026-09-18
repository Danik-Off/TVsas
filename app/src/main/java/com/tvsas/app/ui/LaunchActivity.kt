package com.tvsas.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.tvsas.app.data.Prefs

/**
 * Invisible entry point for launcher "Watch Next" cards (tvsas://watch/{videoUuid}).
 * Resumes the video straight away with the home screen underneath; falls back to the home
 * screen when the entry is no longer in the local history.
 */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val home = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val entry = WatchNext.videoUuidFrom(intent?.data)?.let { Prefs.historyFor(it) }
        if (entry != null && entry.percent < 96) {
            val player = PlayerActivity.intent(this, entry.topic, entry.videoUuid, entry.durationSec, entry.positionSec)
            startActivities(arrayOf(home, player))
        } else {
            startActivity(home)
        }
        finish()
    }
}
