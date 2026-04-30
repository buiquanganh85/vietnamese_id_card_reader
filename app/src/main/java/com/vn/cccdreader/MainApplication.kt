package com.vn.cccdreader

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Register BouncyCastle – needed for 3DES, SHA-1 operations in BAC
        // Remove any existing BC provider first to avoid duplicates on Android
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
    }
}
