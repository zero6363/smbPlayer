package com.smbplayer.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * 收藏路径的本地持久化。
 *
 * 与 [ServerRepository] 分开存储，因为收藏路径不含敏感信息，且生命周期独立。
 */
object FavoriteRepository {

    private const val PREFS_NAME = "smb_favorites"
    private const val KEY_LIST = "favorites"

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
            context.getSharedPreferences("${PREFS_NAME}_plain", Context.MODE_PRIVATE)
        }
    }

    private fun store(): SharedPreferences =
        prefs ?: throw IllegalStateException("FavoriteRepository.init() 未调用")

    fun getAll(): List<FavoritePath> {
        val raw = store().getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(o.toFavorite())
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun get(id: String): FavoritePath? = getAll().firstOrNull { it.id == id }

    /** 同一服务器 + 共享 + 路径视为重复 */
    fun exists(serverId: String, shareName: String, path: String): Boolean =
        getAll().any {
            it.serverId == serverId && it.shareName == shareName && it.path == path
        }

    fun save(favorite: FavoritePath) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == favorite.id }
        if (idx >= 0) list[idx] = favorite else list.add(favorite)
        writeAll(list)
    }

    fun delete(id: String) {
        writeAll(getAll().filterNot { it.id == id })
    }

    private fun writeAll(list: List<FavoritePath>) {
        val arr = JSONArray()
        list.sortedByDescending { it.createdAt }.forEach { arr.put(it.toJson()) }
        store().edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun FavoritePath.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("serverId", serverId)
        put("serverName", serverName)
        put("shareName", shareName)
        put("path", path)
        put("label", label)
        put("createdAt", createdAt)
    }

    private fun JSONObject.toFavorite(): FavoritePath = FavoritePath(
        id = optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
        serverId = optString("serverId"),
        serverName = optString("serverName"),
        shareName = optString("shareName"),
        path = optString("path"),
        label = optString("label"),
        createdAt = optLong("createdAt", System.currentTimeMillis()),
    )
}
