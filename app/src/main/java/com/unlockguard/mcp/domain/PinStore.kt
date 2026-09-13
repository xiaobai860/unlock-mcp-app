package com.unlockguard.mcp.domain

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlin.random.Random

/**
 * PIN 与 Token 的本地加密存储。
 * - PIN：AES256_GCM 加密落盘（永不过 MCP 通道传输，仅在本地解锁时解密输入）。
 * - Token：随机生成，支持重生成吊销。
 * 依赖 Android Keystore 主密钥，allowBackup=false 防止云备份泄露。
 */
class PinStore(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ug_secured",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun hasPin(): Boolean = prefs.contains(KEY_PIN)
    fun setPin(pin: String) = prefs.edit().putString(KEY_PIN, pin).apply()
    fun getPin(): String? = prefs.getString(KEY_PIN, null)
    fun clearPin() = prefs.edit().remove(KEY_PIN).apply()

    fun getToken(): String = prefs.getString(KEY_TOKEN, null) ?: regenerateToken()

    fun regenerateToken(): String {
        val raw = Random.nextBytes(24)
        val t = "ug_" + raw.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_TOKEN, t).apply()
        return t
    }

    companion object {
        private const val KEY_PIN = "pin"
        private const val KEY_TOKEN = "token"
    }
}
