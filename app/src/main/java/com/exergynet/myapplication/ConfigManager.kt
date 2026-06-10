package com.exergynet.myapplication
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

class ConfigManager(context: Context) {
    private val appCtx: Context = context.applicationContext
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = try {
        createPrefs(context)
    } catch (e: Exception) {
        // KINEMATIC RECOVERY: If KeyStore/Tink state is corrupt, purge the file and restart session
        println(">>> [SECURITY] Corrupt Prefs detected. Purging storage.")
        val prefFile = File(context.filesDir.parent, "shared_prefs/ExergyNetSecurePrefs.xml")
        if (prefFile.exists()) prefFile.delete()
        createPrefs(context)
    }

    private fun createPrefs(context: Context): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            "ExergyNetSecurePrefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var aggregatorIp: String
        get() = prefs.getString("AGGREGATOR_IP", "") ?: ""
        set(value) = prefs.edit().putString("AGGREGATOR_IP", value).apply()

    var evmPayoutAddress: String
        get() = prefs.getString("EVM_PAYOUT_ADDRESS", "") ?: ""
        set(value) = prefs.edit().putString("EVM_PAYOUT_ADDRESS", value).apply()

    var passiveModeEnabled: Boolean
        get() = prefs.getBoolean("PASSIVE_MODE", true)
        set(value) = prefs.edit().putBoolean("PASSIVE_MODE", value).apply()

    var isOnboarded: Boolean
        get() = prefs.getBoolean("IS_ONBOARDED", false)
        set(value) = prefs.edit().putBoolean("IS_ONBOARDED", value).apply()

    var activeJobId: String
        get() = prefs.getString("ACTIVE_JOB_ID", "") ?: ""
        set(value) = prefs.edit().putString("ACTIVE_JOB_ID", value).apply()

    var activeJobOpcode: String
        get() = prefs.getString("ACTIVE_JOB_OPCODE", "") ?: ""
        set(value) = prefs.edit().putString("ACTIVE_JOB_OPCODE", value).apply()

    var passiveBatteryGuard: Int
        get() = prefs.getInt("PASSIVE_BATTERY_GUARD", 20)
        set(value) = prefs.edit().putInt("PASSIVE_BATTERY_GUARD", value).apply()

    var passiveScheduleStart: String
        get() = prefs.getString("PASSIVE_SCHEDULE_START", "06:00") ?: "06:00"
        set(value) = prefs.edit().putString("PASSIVE_SCHEDULE_START", value).apply()

    var passiveScheduleEnd: String
        get() = prefs.getString("PASSIVE_SCHEDULE_END", "22:00") ?: "22:00"
        set(value) = prefs.edit().putString("PASSIVE_SCHEDULE_END", value).apply()

    var profilePicturePath: String
        get() = prefs.getString("PROFILE_PICTURE_PATH", "") ?: ""
        set(value) = prefs.edit().putString("PROFILE_PICTURE_PATH", value).apply()

    var networkMode: String
        get() = prefs.getString("NETWORK_MODE", "mainnet") ?: "mainnet"
        set(value) = prefs.edit().putString("NETWORK_MODE", value).apply()

    // BLUE TEAM (H1): shared secret for the LNES-15 mobile↔desktop TCP HMAC seal.
    // Stored in EncryptedSharedPreferences (never hardcoded in the binary).
    // The placeholder default keeps dev builds working; production must provision
    // a real secret (e.g. pushed at onboarding) so it matches the desktop prover's
    // LNES03_TCP_SECRET. Both sides must derive the same UTF-8 bytes.
    var tcpSecret: String
        get() = prefs.getString("LNES03_TCP_SECRET", "LNES03_DEV_PLACEHOLDER_SECRET") ?: ""
        set(value) = prefs.edit().putString("LNES03_TCP_SECRET", value).apply()

    // Basic EIP-55 Hex Check (0x followed by 40 hex characters)
    fun isValidEvmAddress(address: String): Boolean {
        val regex = Regex("^0x[a-fA-F0-9]{40}\$")
        return regex.matches(address)
    }

    // Canonical node id == the mesh identity (Base64(SHA-256(pubkey))). Previously
    // this returned ANDROID_ID (hex), which disagreed with the mesh's pubkey-hash id
    // and broke all routing + D2 verification. Now both are the same value.
    fun getMinerId(): String = NodeIdentity.get(appCtx)
}