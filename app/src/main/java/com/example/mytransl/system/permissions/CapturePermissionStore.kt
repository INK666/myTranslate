package com.example.mytransl.system.permissions

import android.content.Intent

object CapturePermissionStore {
    @Volatile
    var resultCode: Int = 0

    @Volatile
    var data: Intent? = null

    fun set(resultCode: Int, data: Intent?) {
        this.resultCode = resultCode
        this.data = data
    }

    fun clear() {
        resultCode = 0
        data = null
    }
}

