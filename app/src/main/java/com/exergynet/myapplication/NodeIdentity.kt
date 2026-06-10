package com.exergynet.myapplication

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest

/**
 * NodeIdentity — THE single source of truth for this node's cryptographic id.
 *
 * Historically two derivations disagreed: ConfigManager.getMinerId() used
 * ANDROID_ID (hex) for the UI/Node Book/contacts, while DLTNMessenger used
 * Base64(SHA-256(pubkey)) for mesh envelopes/routing — and the latter
 * non-deterministically fell back to ANDROID_ID when the keystore read threw.
 * The two never matched, so messages/calls could not be routed and D2 signature
 * verification (which binds node-id == Base64(SHA-256(pubkey))) rejected them.
 *
 * This object derives the id ONCE from the EC public key (provisioning the key if
 * absent), persists it, and returns the cached value forever after — identical for
 * the UI, contacts, AND every mesh rail. D2 verification holds because the id IS
 * the pubkey hash.
 */
object NodeIdentity {
    const val KEY_ALIAS = "exergynet_edge_witness_key"
    private const val PREF = "exergynet_identity"
    private const val PREF_KEY = "node_id"

    @Volatile private var cached: String? = null

    /** Stable 16-char Base64(SHA-256(EC pubkey)) node id. */
    fun get(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            prefs.getString(PREF_KEY, null)?.let { cached = it; return it }
            val id = derive()
            prefs.edit().putString(PREF_KEY, id).apply()
            cached = id
            return id
        }
    }

    private fun derive(): String {
        ensureKey()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cert = ks.getCertificate(KEY_ALIAS)
            ?: throw IllegalStateException("node identity key missing after provisioning")
        val hash = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return Base64.encodeToString(hash, Base64.NO_WRAP).take(16)
    }

    /** Provision the node's permanent EC keypair once. MUST NOT be rotated. */
    fun ensureKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) return
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).setDigests(KeyProperties.DIGEST_SHA256).build()
        )
        kpg.generateKeyPair()
    }
}
