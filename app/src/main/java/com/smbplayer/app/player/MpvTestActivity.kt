package com.smbplayer.app.player

import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import dev.jdtech.mpv.MPVLib

/**
 * [mpv 实验分支] 最小 mpv 播放验证页。
 *
 * 通过 adb 启动并传入播放地址：
 *   am start -n com.smbplayer.app/.player.MpvTestActivity -e url <视频URL>
 *
 * 目的：验证 libmpv AAR 能在本项目编译/加载/渲染，观察 TS 播放与色彩表现。
 * 不涉及 SMB（mpv 无 libsmbclient），先用 HTTP 或本地文件源验证内核。
 */
class MpvTestActivity : AppCompatActivity() {

    private var mpv: MPVLib? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url").orEmpty()
        Log.i(TAG, "MpvTestActivity url=$url")

        val surfaceView = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.i(TAG, "surfaceCreated -> attachSurface")
                    mpv?.attachSurface(holder.surface)
                    if (url.isNotEmpty()) {
                        Log.i(TAG, "loadfile $url")
                        mpv?.command(arrayOf("loadfile", url, "replace"))
                    }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    mpv?.detachSurface()
                }
            })
        }
        setContentView(FrameLayout(this).apply { addView(surfaceView) })

        try {
            val instance = MPVLib.create(this)!!
            mpv = instance
            instance.apply {
                setOptionString("hwdec", "auto-safe")
                setOptionString("keep-open", "yes")
                init()
                addObserver(object : MPVLib.EventObserver {
                    override fun eventProperty(property: String) {
                        Log.d(TAG, "eventProperty $property = ${mpv?.getPropertyString(property)}")
                    }

                    override fun eventProperty(property: String, value: Long) {
                        Log.d(TAG, "eventProperty $property = $value")
                    }

                    override fun eventProperty(property: String, value: Boolean) {
                        Log.d(TAG, "eventProperty $property = $value")
                    }

                    override fun eventProperty(property: String, value: Double) {
                        Log.d(TAG, "eventProperty $property = $value")
                    }

                    override fun eventProperty(property: String, value: String) {
                        Log.d(TAG, "eventProperty $property = $value")
                    }

                    override fun event(event: Int) {
                        Log.d(TAG, "event $event")
                    }
                })
            }
            Log.i(TAG, "mpv initialized")
        } catch (t: Throwable) {
            Log.e(TAG, "mpv init failed", t)
        }
    }

    override fun onDestroy() {
        runCatching { mpv?.destroy() }
        mpv = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MpvTest"
    }
}
