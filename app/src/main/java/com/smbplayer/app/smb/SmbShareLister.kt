package com.smbplayer.app.smb

import com.smbplayer.app.util.logError
import com.smbplayer.app.util.logInfo
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Properties

/**
 * 局域网扫描发现的服务器。
 *
 * @param host IP 地址
 * @param name 解析到的主机名（解析不到时回退为 IP）
 */
data class LanServer(val host: String, val name: String)

/**
 * 基于 jcifs-ng 的 SMB 辅助能力：真实共享枚举 + 局域网服务器发现。
 *
 * 为什么不用 smbj 做这些：
 * - smbj 没有共享枚举 API（列不出「服务器上有哪些共享」），只能拿一张常见共享名候选表
 *   逐个试连——共享名不在表里（自定义目录名、非英语名等）就「扫不到」。
 * - jcifs-ng 的 SmbFile 可以直接对 `smb://host/` 根路径 listFiles，服务器通过 MS-SRVS
 *   返回真实共享列表，不需要猜名字。
 *
 * 这里只承担「发现」职责：找到共享名后，浏览/播放/删除仍走 smbj 主引擎（[SmbSession]），
 * 两库各自只做自己擅长的部分。
 */
object SmbShareLister {

    private const val TAG = "SmbShareLister"

    /** 端口探测超时：局域网内 700ms 足够，批量扫描时总时长可控 */
    private const val PROBE_TIMEOUT_MS = 700

    /** 每批并行探测的 IP 数 */
    private const val SCAN_BATCH = 32

    /**
     * 枚举服务器上的真实共享名（对应 Windows 网络邻居里看到的那些共享）。
     *
     * @param anonymous true 时匿名访问；false 时用 username/password 认证
     * @return 共享名列表（已过滤 `.`/`..`/`$` 隐藏共享并去重排序）；
     *         失败或服务器不支持时返回空列表，由调用方回落到候选表扫描
     */
    suspend fun listShares(
        host: String,
        port: Int,
        username: String,
        password: String,
        anonymous: Boolean,
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val ctx = buildContext(username, password, anonymous)
            // 对 smb://host/ 根列目录：jcifs 会走 IPC$ + NetShareEnum 拿到真实共享列表
            val root = SmbFile("smb://$host:$port/", ctx)
            val shares = root.listFiles()
                ?.map { it.name.trimEnd('/') }
                ?.filter { it.isNotBlank() && it != "." && it != ".." && !it.endsWith("$") }
                ?.distinct()
                ?.sortedBy { it.lowercase() }
                ?: emptyList()
            logInfo("[$TAG] 真实共享枚举 $host:$port → ${shares.size} 个: $shares")
            shares
        } catch (e: Exception) {
            // 枚举失败很常见（服务器禁匿名、防火墙、SMB1-only 老设备等），
            // 不打扰用户，静默回落候选表扫描
            logError("[$TAG] 共享枚举失败 $host:$port，回落候选表", e)
            emptyList()
        }
    }

    /**
     * 扫描局域网内的 SMB 服务器。
     *
     * 做法：从本机 IPv4 地址推导 /24 网段 → 批量并行探测 445/139 端口 →
     * 命中后反查主机名（先 DNS，再 NetBIOS）。
     *
     * @param onProgress 进度回调（扫描百分比/已发现数量）
     * @param onFound 每发现一台服务器回调一次
     * @return 全部发现的服务器
     */
    suspend fun scanLan(
        onProgress: suspend (String) -> Unit = {},
        onFound: suspend (LanServer) -> Unit = {},
    ): List<LanServer> = withContext(Dispatchers.IO) {
        val subnets = localSubnets()
        if (subnets.isEmpty()) {
            onProgress("未检测到局域网 IP，请检查网络连接")
            return@withContext emptyList()
        }
        val selfIps = localIpAddresses()
        val allIps = subnets.flatMap { subnet -> (1..254).map { "$subnet.$it" } }
            .filter { it !in selfIps }

        val found = mutableListOf<LanServer>()
        var scanned = 0
        onProgress("开始扫描 ${allIps.size} 个地址（${subnets.joinToString()}）…")

        for (batch in allIps.chunked(SCAN_BATCH)) {
            val results = coroutineScope {
                batch.map { ip -> async { probeHost(ip) } }.awaitAll()
            }
            for (server in results) {
                if (server != null) {
                    found.add(server)
                    onFound(server)
                }
            }
            scanned += batch.size
            onProgress("扫描中 ${scanned * 100 / allIps.size}%（$scanned/${allIps.size}）· 已发现 ${found.size} 台")
        }
        onProgress("扫描完成，发现 ${found.size} 台服务器")
        logInfo("[$TAG] 局域网扫描完成，发现 ${found.size} 台: ${found.map { it.host }}")
        found
    }

    /** 探测单台主机：445 或 139 通即认为在提供 SMB 服务 */
    private suspend fun probeHost(ip: String): LanServer? = withContext(Dispatchers.IO) {
        val open = tryConnectPort(ip, 445) || tryConnectPort(ip, 139)
        if (!open) return@withContext null
        LanServer(host = ip, name = resolveHostName(ip))
    }

    private fun tryConnectPort(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
        true
    } catch (e: Exception) {
        false
    }

    /** 主机名反查：先 DNS，失败再用 jcifs 的 NetBIOS 名字服务 */
    private fun resolveHostName(ip: String): String = try {
        val name = InetAddress.getByName(ip).hostName
        if (!name.equals(ip, ignoreCase = true)) {
            name.removeSuffix(".lan").removeSuffix(".local")
        } else {
            try {
                val ctx = buildContext("", "", anonymous = true)
                val nbt = ctx.nameServiceClient.getByName(ip)
                (nbt.hostName ?: ip).removeSuffix(".lan").removeSuffix(".local")
            } catch (e: Exception) {
                ip
            }
        }
    } catch (e: Exception) {
        ip
    }

    /** 本机全部 IPv4 地址（含回退判断用） */
    private fun localIpAddresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    // 只要 IPv4 局域网地址：含 "." 且非 127 开头的回写、非 169.254 链路本地
                    if (host.contains(".") &&
                        !host.startsWith("127.") &&
                        !host.startsWith("169.254.")
                    ) {
                        result.add(host)
                    }
                }
            }
        } catch (e: Exception) {
            logError("[$TAG] 获取本机 IP 失败", e)
        }
        return result
    }

    /** 从本机 IP 推导要扫描的 /24 网段（去重） */
    private fun localSubnets(): List<String> =
        localIpAddresses().mapNotNull { ip ->
            val parts = ip.split(".")
            if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}" else null
        }.distinct()

    /**
     * 构建 jcifs 上下文。
     *
     * 不强制 SMB1（与参考项目不同）：现代 Windows/NAS 普遍只有 SMB2/3，
     * 锁死 SMB1 反而连不上；超时收紧保证枚举不会长时间挂起。
     */
    private fun buildContext(username: String, password: String, anonymous: Boolean): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.responseTimeout", "20000")
            setProperty("jcifs.smb.client.connTimeout", "8000")
            setProperty("jcifs.smb.client.soTimeout", "20000")
            // DFS 重定向在纯枚举场景只会添乱，关掉
            setProperty("jcifs.smb.client.dfs.disabled", "true")
        }
        val base = BaseContext(PropertyConfiguration(props))
        return when {
            anonymous -> base.withAnonymousCredentials()
            username.isNotEmpty() -> base.withCredentials(NtlmPasswordAuthenticator(username, password))
            else -> base.withAnonymousCredentials()
        }
    }
}
