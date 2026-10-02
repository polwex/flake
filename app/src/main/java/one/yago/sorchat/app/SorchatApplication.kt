package one.yago.sorchat.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class SorchatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications(this).createChannels()
        val repository = ChatRepository.get(this)
        // Live WebSocket while any screen is visible; in the background we rely on FCM pushes.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = repository.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = repository.setForeground(false)
        })
    }
}
