package fr.hobbitdecheval.acestream

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional account sign-in protected by an authentication-per-use Android Keystore
 * AES-GCM key and BIOMETRIC_STRONG. Only encrypted credentials are persisted.
 * Biometric recognition is performed by Android, not by this application.
 */
class BiometricLogin(private val activity: FragmentActivity) {
    private val prefs = activity.getSharedPreferences("acestream_biometric", Context.MODE_PRIVATE)
    private val alias = "acestream_tv_sign_in_key_v1"
    private val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG
    private val cipherName = "AES/GCM/NoPadding"

    fun available(): Boolean =
        BiometricManager.from(activity).canAuthenticate(auth) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun savedUser(baseUrl: String): String? {
        if (prefs.getString("server", "") != baseUrl) return null
        if (!prefs.contains("ciphertext") || !prefs.contains("iv")) return null
        return prefs.getString("username", null)?.takeIf { it.isNotBlank() }
    }

    fun forget() {
        prefs.edit().clear().apply()
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(alias)
        } catch (_: Exception) { /* Credential metadata is already removed. */ }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            spec.setUserAuthenticationValidityDurationSeconds(-1)
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(spec.build())
        return generator.generateKey()
    }

    private fun promptInfo(title: String): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("AceStream TV · authentification sur cet appareil")
            .setAllowedAuthenticators(auth)
            .setNegativeButtonText("Utiliser mon mot de passe")
            .build()

    fun enable(baseUrl: String, username: String, password: String,
               done: (Boolean, String) -> Unit) {
        if (!available()) {
            done(false, "Empreinte digitale forte non configurée dans Android.")
            return
        }
        if (!baseUrl.startsWith("https://", ignoreCase = true)) {
            done(false, "La connexion biométrique nécessite une adresse HTTPS.")
            return
        }
        try {
            val cipher = Cipher.getInstance(cipherName).apply {
                init(Cipher.ENCRYPT_MODE, key())
            }
            val prompt = BiometricPrompt(activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        try {
                            val authenticated = result.cryptoObject?.cipher
                                ?: throw IllegalStateException("Session biométrique indisponible")
                            val encrypted = authenticated.doFinal(
                                password.toByteArray(Charsets.UTF_8))
                            prefs.edit()
                                .putString("server", baseUrl)
                                .putString("username", username)
                                .putString("iv", Base64.encodeToString(
                                    authenticated.iv, Base64.NO_WRAP))
                                .putString("ciphertext", Base64.encodeToString(
                                    encrypted, Base64.NO_WRAP))
                                .apply()
                            done(true, "Connexion par empreinte activée.")
                        } catch (_: Exception) {
                            done(false, "Impossible d'enregistrer la connexion biométrique.")
                        }
                    }
                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        done(false, "Activation biométrique annulée ou indisponible.")
                    }
                })
            prompt.authenticate(
                promptInfo("Activer la connexion par empreinte"),
                BiometricPrompt.CryptoObject(cipher)
            )
        } catch (_: Exception) {
            done(false, "Le stockage sécurisé Android n'est pas disponible.")
        }
    }

    fun authenticate(baseUrl: String, authenticated: (String, String) -> Unit,
                     failed: (String) -> Unit) {
        val username = savedUser(baseUrl)
        if (username == null || !available()) {
            failed("Empreinte non configurée pour cette adresse.")
            return
        }
        try {
            val iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)
            val ciphertext = Base64.decode(
                prefs.getString("ciphertext", ""), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(cipherName).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
            val prompt = BiometricPrompt(activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        try {
                            val decrypted = result.cryptoObject?.cipher
                                ?: throw IllegalStateException()
                            val password = String(
                                decrypted.doFinal(ciphertext), Charsets.UTF_8)
                            authenticated(username, password)
                        } catch (_: Exception) {
                            forget()
                            failed("Empreinte réinitialisée. Reconnecte-toi avec ton mot de passe.")
                        }
                    }
                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        failed("Authentification biométrique annulée.")
                    }
                })
            prompt.authenticate(
                promptInfo("Déverrouiller AceStream TV"),
                BiometricPrompt.CryptoObject(cipher)
            )
        } catch (_: Exception) {
            // For example: an enrolled fingerprint was replaced or the phone
            // security configuration changed, invalidating the Keystore key.
            forget()
            failed("Identifiants biométriques invalidés. Utilise ton mot de passe.")
        }
    }
}
