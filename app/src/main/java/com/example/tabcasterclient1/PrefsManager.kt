package com.example.tabcasterclient1

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity


class PrefsManager(private val activity: AppCompatActivity) {

    private lateinit var lastConnectedIP : String

    private fun setLastIP(lastValidIP: String) {
        lastConnectedIP = lastValidIP
        // Save IP Address To Preferences
        val settings: SharedPreferences = activity.getSharedPreferences("lastConnectedIP", 0)
        val editor = settings.edit()
        editor.putString("lastConnectedIP", lastValidIP)
        editor.apply()
    }

    private fun getLastIP(){
        val settings: SharedPreferences = activity.getSharedPreferences("lastConnectedIP", 0)
        val homeScore = settings.getString("homeScore", "")
    }
}