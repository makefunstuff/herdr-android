package com.makefunstuff.herdr.guard

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.makefunstuff.herdr.data.Unlock
import com.makefunstuff.herdr.ui.design.Toasts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * [Unlock] on Android: androidx.biometric's prompt, which takes a fingerprint or a face, or the
 * phone's own PIN, pattern or password. It shows over whichever of the app's windows is in front
 * (the app, a floating chat, or the one a notification's button opens), so it follows them.
 */
class AndroidUnlock(private val app: Application) : Unlock {
    private val front = MutableStateFlow<FragmentActivity?>(null)

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is FragmentActivity) front.value = activity
            }
            override fun onActivityPaused(activity: Activity) {
                if (front.value === activity) front.value = null
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (front.value === activity) front.value = null
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    override fun canAsk(): Boolean = BiometricManager.from(app).canAuthenticate(WAYS) == BiometricManager.BIOMETRIC_SUCCESS

    override suspend fun ask(title: String, subtitle: String?): Boolean {
        // Asked as a window comes up: it is in front a moment later.
        val activity = withTimeoutOrNull(5_000) { front.filterNotNull().first() } ?: return false
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { done ->
                val prompt = BiometricPrompt(
                    activity, ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (done.isActive) done.resume(true)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            // Cancelled by the person or by the app leaving the screen says nothing; a lockout or a broken sensor does.
                            if (errorCode !in QUIET) Toasts.show(errString.toString(), error = true)
                            if (done.isActive) done.resume(false)
                        }
                        // A finger not recognised: the prompt stays up and says so itself.
                    },
                )
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .apply { if (!subtitle.isNullOrBlank()) setDescription(subtitle) }
                    .setAllowedAuthenticators(WAYS)
                    .setConfirmationRequired(false)
                    .build()
                prompt.authenticate(info)
                done.invokeOnCancellation { prompt.cancelAuthentication() }
            }
        }
    }

    override fun openSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, WAYS)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .recoverCatching { app.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private companion object {
        /** A fingerprint or face (any class), or the screen lock: "fingerprint or PIN". */
        const val WAYS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        val QUIET = setOf(
            BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        )
    }
}
