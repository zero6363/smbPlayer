package com.smbplayer.app.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.TrackOutput
import com.smbplayer.app.util.logInfo

/**
 * 给 TS 视频轨补默认色彩范围的 [ExtractorsFactory]。
 *
 * 背景：广播/录制 TS 的 H.264/H.265 SPS 常缺少 VUI 的 `video_full_range_flag`，
 * Media3 解析出 `colorRange = FORMAT_UNSET`。解码器遇到未知范围时按 full range(0-255)
 * 渲染，而 TS 内容实际是 limited range(16-235)，黑位被抬升、对比度压缩，画面就"发灰"。
 *
 * 处理：只对视频轨、且 [Format.colorInfo] 的取值范围为 [Format.NO_VALUE]（未标注）时，
 * 补成 [C.COLOR_RANGE_LIMITED]；码流里明确写了 full/limited 的一律不动，避免误伤真正
 * 的 full range 源。mp4/mkv 等容器不用这个工厂（它们有 colr/容器级色彩元数据，正常）。
 *
 * 用委托转发 [Extractor]/[ExtractorOutput]/[TrackOutput] 的全部方法，只拦截 [TrackOutput.format]。
 */
@OptIn(markerClass = [UnstableApi::class])
class TsColorAwareExtractorsFactory : ExtractorsFactory {

    private val delegate = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor = object : Extractor by extractor {
        override fun init(output: ExtractorOutput) {
            extractor.init(object : ExtractorOutput by output {
                override fun track(id: Int, type: @C.TrackType Int): TrackOutput {
                    val upstream = output.track(id, type)
                    if (type != C.TRACK_TYPE_VIDEO) return upstream
                    return object : TrackOutput by upstream {
                        override fun format(format: Format) {
                            upstream.format(fixColorRange(format))
                        }
                    }
                }
            })
        }
    }

    private fun fixColorRange(format: Format): Format {
        val info = format.colorInfo
        // 只补"未标注"的；明确写了 full/limited 或不带色彩信息的都不动
        if (info == null || info.colorRange != Format.NO_VALUE) return format
        val fixed = info.buildUpon().setColorRange(C.COLOR_RANGE_LIMITED).build()
        logInfo("TS 视频轨色彩范围未标注，按 limited range 渲染")
        return format.buildUpon().setColorInfo(fixed).build()
    }
}
