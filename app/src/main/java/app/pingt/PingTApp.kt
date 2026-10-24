package app.pingt

import android.app.Application

class PingTApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.migrate(this) // before any screen or the service reads settings
    }
}
