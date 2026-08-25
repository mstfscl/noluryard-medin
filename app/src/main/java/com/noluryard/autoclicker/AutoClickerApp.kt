package com.noluryard.autoclicker

import android.app.Application
import android.content.Context
import com.noluryard.autoclicker.data.SettingsRepository
import com.noluryard.autoclicker.util.Notifications

class AutoClickerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }

    companion object {
        @Volatile
        private var repository: SettingsRepository? = null

        /**
         * Tek DataStore ornegi. AccessibilityService ve OverlayService, Application
         * yaratilmadan once baglanabilecegi icin (process yeniden dogdugunda)
         * lateinit yerine tembel/atomik erisim kullaniliyor.
         */
        fun settingsRepository(context: Context): SettingsRepository =
            repository ?: synchronized(this) {
                repository ?: SettingsRepository(context.applicationContext).also { repository = it }
            }
    }
}
