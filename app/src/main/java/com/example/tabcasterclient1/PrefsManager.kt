package com.example.tabcasterclient1

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity


// This is a singleton, and uses context injection
class PrefsManager private constructor(context: Context) {
        private val prefs = context.applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        private lateinit var lastConnectedIP : String

        fun setLastIP(lastValidIP: String) {
            lastConnectedIP = lastValidIP
            // Save IP Address To Preferences
            prefs.edit().putString("lastConnectedIP", lastValidIP).apply() // Get the editor, put the string in and apply all in one line.
        }

        fun getLastIP(): String? = prefs.getString("lastConnectedIP", null)

    companion object {
        @Volatile

        private var INSTANCE: PrefsManager? = null

        fun getInstance(context: Context): PrefsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PrefsManager(context).also { INSTANCE = it }
            }
        }
    }
}