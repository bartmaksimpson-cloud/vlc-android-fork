/*
 * DecoderLimitDelegate.kt — кадр выше того, что берёт железо.
 *
 * Телевизионные декодеры объявляют максимальный кадр (на TCL/Realtek это
 * 4096x2176) и отказываются от того, что выше: раздача «открытым кадром»
 * 3840x2880 по ширине проходит, по высоте нет. MediaCodec принимает configure,
 * валится сразу после start с OMX_ErrorUndefined, VLC уходит в софтверный
 * avcodec, а он такой кадр на 32-битном SoC не тянет — четверть кадров в мусор.
 *
 * Сделать кадр меньше на телевизоре нельзя. Дешёвые режимы декодирования
 * (skiploopfilter=4, skip-frame=2, skip-idct=2) пробовали на живом файле
 * 3840x2880: рывки остались прежними, а картинка развалилась на блоки —
 * skip-idct выбрасывает обратное преобразование в B-кадрах. Убрано.
 *
 * Остаётся то, что реально помогает: сказать вслух, почему рвёт, — иначе
 * причину видно только в логе декодера. Лечится файл на стороне источника:
 * высота не больше того, что берёт железо.
 */
package org.videolan.vlc.gui.helpers

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R

object DecoderLimitDelegate {

    private const val TAG = "VLC/DecoderLimit"

    /** URI, про которые уже сказали: предупреждение — одно на файл. */
    private const val KEY_OVER_LIMIT = "decoder_over_limit_uris"

    /** Список не растёт бесконечно: помнить надо последние, а не все. */
    private const val REMEMBER_MAX = 50

    /** Уже предупреждали про этот файл — второй раз не мешаем. */
    private fun warned(context: Context, uri: String): Boolean =
        remembered(context).contains(uri)

    private fun remembered(context: Context): Set<String> =
        Settings.getInstance(context).getStringSet(KEY_OVER_LIMIT, emptySet()) ?: emptySet()

    private fun remember(context: Context, uri: String) {
        val kept = remembered(context).toMutableList()
        kept.remove(uri)
        kept.add(uri)
        while (kept.size > REMEMBER_MAX) kept.removeAt(0)
        Settings.getInstance(context).putSingle(KEY_OVER_LIMIT, kept.toSet())
    }

    /**
     * Вызывается, когда воспроизведение началось и дорожки уже известны.
     * Если кадр выше предела железа — один раз на файл говорит, почему рвёт.
     */
    fun check(activity: FragmentActivity, service: PlaybackService) {
        val uri = service.currentMediaWrapper?.uri?.toString() ?: return
        if (warned(activity, uri)) return // про этот файл уже сказали

        val track = runCatching { videoTrack(service) }.getOrNull() ?: return
        val limit = maxHardwareHeight(mimeOf(track.codec))
        if (limit <= 0 || track.height <= limit) return

        Log.i(TAG, "кадр ${track.width}x${track.height} выше предела железа $limit")
        remember(activity, uri)
        Toast.makeText(
            activity,
            activity.getString(R.string.decoder_over_limit, track.width, track.height, limit),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun videoTrack(service: PlaybackService): IMedia.VideoTrack? {
        val media = service.mediaplayer.media ?: return null
        try {
            for (i in 0 until media.trackCount) {
                (media.getTrack(i) as? IMedia.VideoTrack)?.let { return it }
            }
        } finally {
            media.release()
        }
        return null
    }

    /**
     * Максимальная высота кадра среди АППАРАТНЫХ декодеров этого формата.
     * 0 — формат неизвестен или аппаратного декодера нет: тогда лезть не во что,
     * и файл трогать не надо.
     */
    private fun maxHardwareHeight(mime: String): Int {
        if (mime.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return 0
        var best = 0
        runCatching {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder || !isHardware(info.name, info)) continue
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                val h = info.getCapabilitiesForType(mime).videoCapabilities?.supportedHeights?.upper ?: continue
                if (h > best) best = h
            }
        }.onFailure { Log.w(TAG, "не удалось опросить декодеры", it) }
        return best
    }

    /** Программные декодеры Android называются узнаваемо; на API 29+ есть прямой ответ. */
    private fun isHardware(name: String, info: android.media.MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
        val n = name.lowercase()
        return !n.startsWith("omx.google.") && !n.startsWith("c2.android.")
    }

    /** Описание кодека от libvlc («H264 - MPEG-4 AVC (part 10)») в mime для MediaCodec. */
    private fun mimeOf(codec: String?): String {
        val c = codec?.uppercase() ?: return ""
        return when {
            c.contains("HEVC") || c.contains("H265") -> "video/hevc"
            c.contains("H264") || c.contains("AVC") -> "video/avc"
            c.contains("AV1") -> "video/av01"
            c.contains("VP9") -> "video/x-vnd.on2.vp9"
            c.contains("MPEG-2") || c.contains("MPGV") -> "video/mpeg2"
            else -> ""
        }
    }
}
