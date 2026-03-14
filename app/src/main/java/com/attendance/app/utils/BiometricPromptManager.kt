package com.attendance.app.utils

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.*
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class BiometricResult {
    data object Success : BiometricResult()
    data object Failed : BiometricResult()
    data object Cancelled : BiometricResult()
    data class Error(val errorCode: Int, val message: String) : BiometricResult()
    data object HardwareUnavailable : BiometricResult()
    data object NoEnrolledBiometrics : BiometricResult()
    data object NoBiometricHardware : BiometricResult()
}

@Singleton
class BiometricPromptManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val resultChannel = Channel<BiometricResult>()
    val resultFlow = resultChannel.receiveAsFlow()

    /**
     * Vérifie si la biométrie est disponible sur l'appareil
     */
    fun checkBiometricAvailability(): BiometricAvailability {
        val biometricManager = BiometricManager.from(context)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        }
        return when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NoHardware
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.HardwareUnavailable
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NotEnrolled
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricAvailability.SecurityUpdateRequired
            else -> BiometricAvailability.Unknown
        }
    }

    /**
     * Lance le prompt biométrique
     * @param activity FragmentActivity requise par l'API biométrique Android
     * @param title    Titre affiché dans le dialogue
     * @param subtitle Sous-titre (optionnel)
     */
    fun showBiometricPrompt(
        activity: FragmentActivity,
        title: String = "Vérification d'identité",
        subtitle: String = "Utilisez votre empreinte ou reconnaissance faciale",
        description: String = "Requis pour valider votre présence"
    ) {
        val availability = checkBiometricAvailability()
        if (availability != BiometricAvailability.Available) {
            val result = when (availability) {
                BiometricAvailability.NoHardware -> BiometricResult.NoBiometricHardware
                BiometricAvailability.HardwareUnavailable -> BiometricResult.HardwareUnavailable
                BiometricAvailability.NotEnrolled -> BiometricResult.NoEnrolledBiometrics
                else -> BiometricResult.Error(-1, "Biométrie non disponible")
            }
            resultChannel.trySend(result)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                resultChannel.trySend(BiometricResult.Success)
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Pas de trySend ici : Android gère les tentatives multiples automatiquement
                // onAuthenticationError est appelé après épuisement des tentatives
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                val result = when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> BiometricResult.Cancelled
                    BiometricPrompt.ERROR_HW_UNAVAILABLE -> BiometricResult.HardwareUnavailable
                    BiometricPrompt.ERROR_NO_BIOMETRICS -> BiometricResult.NoEnrolledBiometrics
                    BiometricPrompt.ERROR_HW_NOT_PRESENT -> BiometricResult.NoBiometricHardware
                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricResult.Error(errorCode, "Biométrie verrouillée. Essayez plus tard.")
                    else -> BiometricResult.Error(errorCode, errString.toString())
                }
                resultChannel.trySend(result)
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            BIOMETRIC_WEAK
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setDescription(description)
            .setAllowedAuthenticators(authenticators)
            .apply {
                // Sur Android < 11, on doit définir un bouton négatif si pas de DEVICE_CREDENTIAL
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    setNegativeButtonText("Annuler")
                }
            }
            .build()

        activity.runOnUiThread {
            prompt.authenticate(promptInfo)
        }
    }
}

enum class BiometricAvailability {
    Available,
    NoHardware,
    HardwareUnavailable,
    NotEnrolled,
    SecurityUpdateRequired,
    Unknown
}
