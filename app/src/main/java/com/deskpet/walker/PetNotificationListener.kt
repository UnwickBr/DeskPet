package com.deskpet.walker

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class PetNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        petCallback?.invoke()
    }

    companion object {
        var petCallback: (() -> Unit)? = null
    }
}
