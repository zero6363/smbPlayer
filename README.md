# SMB 播放器

一个通过 SMB 协议直接播放局域网（NAS / 共享文件夹）中视频的 Android 播放器。

## 功能

- **服务器管理**：保存多台 SMB 服务器，支持匿名/访客登录和账号密码登录。
- **共享选择**：手动输入共享名，或扫描常用共享名（smbj 不提供共享枚举 API，故采用试连探测）。
- **目录浏览**：逐层进入文件夹，目录优先、其次按名称排序。
- **直接流式播放**：不落盘缓存，ExoPlayer 通过自定义 `SmbDataSource` 从 SMB 直接读取字节流。
- **播放页**：
  - 播放当前目录下的所有视频，自动把同目录视频作为播放列表。
  - 删除当前视频（删除后自动跳到下一个）。
  - 上一个 / 下一个、快进快退 10 秒、播放列表弹窗。
  - 播放结束自动进入下一个视频。
- **列表操作**：目录/文件条目长按或点右侧按钮即可删除（目录递归删除）。

## 技术栈

| 领域 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.0.21 |
| 构建 | Gradle 8.9 + AGP 8.7.3 |
| SDK | compileSdk / targetSdk 35，minSdk 26 |
| UI | 经典 View + XML + ViewBinding + Material 3 |
| 播放 | Media3 / ExoPlayer 1.4.1 |
| SMB | [smbj](https://github.com/hierynomus/smbj) 0.15.0 |
| 凭据存储 | `EncryptedSharedPreferences`（失败时降级为普通存储） |

## 目录结构

```
app/src/main/java/com/smbplayer/app/
├── App.kt                  Application，初始化 ServerRepository
├── data/
│   ├── SmbServer.kt        服务器配置模型
│   ├── ServerRepository.kt 配置持久化（加密存储）
│   └── VideoFormats.kt     视频后缀识别
├── smb/
│   ├── SmbSession.kt       阻塞式 SMB 连接层（含重连重试、目录列表、删除）
│   └── SmbGateway.kt       按服务器 ID 复用 SmbSession
├── player/
│   ├── SmbDataSource.kt    Media3 DataSource，直接从 SMB 流式读取
│   └── PlayerActivity.kt   播放页
├── ui/
│   ├── MainActivity.kt     服务器列表
│   ├── BrowseActivity.kt   目录浏览
│   └── *.kt                RecyclerView 适配器
└── util/Ui.kt              格式化与 Toast 工具
```

## 构建

一键构建：

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1            # assembleDebug
powershell -ExecutionPolicy Bypass -File build.ps1 -Task sdk  # 仅安装 SDK 组件
powershell -ExecutionPolicy Bypass -File build.ps1 -Task clean
```

APK 输出到 `app\build\outputs\apk\debug\app-debug.apk`。

## 注意事项

- 播放依赖设备的解码能力，`wmv` / `rmvb` 等 Media3 不支持的格式会被列出来但无法播放。
- 目录列表禁用了 SMB 目录租约缓存（`withDirectoryLeasingEnabled(false)`），换取最稳妥的
  open-enumerate-close 路径。
- `SmbSession` 的所有方法都是阻塞的，调用方必须放到 IO 协程里。
