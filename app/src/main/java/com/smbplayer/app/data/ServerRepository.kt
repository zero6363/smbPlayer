package com.smbplayer.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * SMB 服务器配置的本地持久化。
 *
 * 优先使用 EncryptedSharedPreferences 加密存储账号密码，初始化失败时降级为普通存储。
 */
object ServerRepository {

    private const val PREFS_NAME = "smb_servers"
    private const val KEY_LIST = "servers"

    private val lock = Any()

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(lock) {
            if (prefs != null) return
            prefs = createPrefs(context.applicationContext)
        }
    }

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            // 某些设备 AndroidKeyStore 不可用时降级
            context.getSharedPreferences("${PREFS_NAME}_plain", Context.MODE_PRIVATE)
        }
    }

    private fun store(): SharedPreferences =
        prefs ?: throw IllegalStateException("ServerRepository.init() 未调用")

    fun getAll(): List<SmbServer> {
        val raw = store().getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(o.toServer())
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun get(id: String): SmbServer? = getAll().firstOrNull { it.id == id }

    /** 新增或覆盖更新 */
    fun save(server: SmbServer) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) list[idx] = server else list.add(server)
        writeAll(list)
    }

    fun delete(id: String) {
        writeAll(getAll().filterNot { it.id == id })
    }

    private fun writeAll(list: List<SmbServer>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        store().edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun SmbServer.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("share", share)
        put("startPath", startPath)
        put("username", username)
        put("password", password)
        put("anonymous", anonymous)
    }

    private fun JSONObject.toServer(): SmbServer = SmbServer(
        id = optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
        name = optString("name"),
        host = optString("host"),
        port = optInt("port", 445),
        share = optString("share"),
        startPath = optString("startPath"),
        username = optString("username"),
        password = optString("password"),
        anonymous = optBoolean("anonymous", true),
    )
}
