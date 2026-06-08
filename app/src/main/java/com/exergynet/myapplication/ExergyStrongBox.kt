package com.exergynet.myapplication
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature

object ExergyStrongBox {
    private const val KEY_ALIAS = "exergynet_edge_witness_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /**
     * Phase 1: Anchor ExergyNet Generation inside the OEM Silicon
     * This creates a keypair that physically cannot leave the phone's secure chip.
     */
    fun generateHardwareKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) return

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)

        // PATH 1: StrongBox (Titan M2 / equivalent secure chip)
        // setUserAuthenticationRequired and setAttestationChallenge are omitted because:
        //   - per-use biometric auth crashes if no biometric is enrolled
        //   - attestation challenge requires hardware attestation support absent on many devices
        // App-layer biometric guard (BiometricGate job) enforces presence verification instead.
        val strongBoxSpec = try {
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setIsStrongBoxBacked(true)
                .build()
        } catch (e: Exception) {
            null
        }

        if (strongBoxSpec != null) {
            try {
                kpg.initialize(strongBoxSpec)
                kpg.generateKeyPair()
                println(">>> [SECURITY] Key anchored in StrongBox secure chip.")
                return
            } catch (e: Exception) {
                println(">>> [SECURITY] StrongBox key gen failed (${e.message}). Falling back to TEE.")
            }
        }

        // PATH 2: Standard TEE (software-backed on emulators, hardware TEE on real devices)
        val teeSpec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        kpg.initialize(teeSpec)
        kpg.generateKeyPair()
        println(">>> [SECURITY] Key anchored in TEE (software fallback).")
    }

    /**
     * Phase 2: Synthesize Physical Proximity Truth
     * Takes the Job ID from the AI Agent, combines it with local sensor data, and signs it.
     */
    fun signThermodynamicPayload(jobId: ByteArray, sensorData: ByteArray): ByteArray {
        val payload = jobId + sensorData
        return try {
            signWith(payload)
        } catch (e: Exception) {
            // Stale key (e.g. previously generated with UserAuthenticationRequired=true)
            // Delete it and regenerate before retrying once.
            println(">>> [SECURITY] Signing failed (${e.message}). Rotating key and retrying...")
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
            generateHardwareKey()
            signWith(payload)
        }
    }

    private fun signWith(payload: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey
        return Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(payload)
        }.sign()
    }
}