package com.vn.cccdreader.util

import android.content.Intent
import android.os.Build
import android.os.Parcelable

/**
 * Type-safe [Intent.getParcelableExtra] that works on both API < 33 and API 33+.
 *
 * On API 33+ [Intent.getParcelableExtra] without a class argument is deprecated
 * and may return null on some devices. This wrapper uses the new overload when
 * available and falls back to the old one on older APIs.
 */
inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
