/*
 * BugReportDelegate.kt — кнопка «Сообщить о проблеме» в меню плеера.
 * Собирает состояние файла, плеера, устройства и логи, отправляет на прокси,
 * который создаёт issue в GitHub. Токена GitHub в APK нет и быть не должно.
 */
package org.videolan.vlc.gui.helpers

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.tools.AppScope
import org.videolan.tools.Logcat
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object BugReportDelegate {

    private const val TAG = "VLC/BugReport"

    // ВАЖНО: адрес вашего Cloudflare Worker. Токен GitHub живёт ТАМ, не здесь.
    private const val ENDPOINT = "https://vlc-bugreport.waxelpacho-svc.workers.dev/report"

    /** Пункты списка «что не так» — соответствуют массиву R.array.bug_report_categories */
    private fun categories(ctx: Context): Array<String> =
        ctx.resources.getStringArray(R.array.bug_report_categories)

    fun show(activity: FragmentActivity, service: PlaybackService) {
        val cats = categories(activity)
        AlertDialog.Builder(activity)
            .setTitle(R.string.bug_report_title)
            .setItems(cats) { _, which -> confirm(activity, service, cats[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirm(activity: FragmentActivity, service: PlaybackService, category: String) {
        AlertDialog.Builder(activity)
            .setTitle(category)
            // пользователь должен видеть, что уедет в публичный трекер
            .setMessage(R.string.bug_report_privacy_notice)
            .setPositiveButton(R.string.bug_report_send) { _, _ -> send(activity, service, category) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun send(activity: FragmentActivity, service: PlaybackService, category: String) {
        val app = activity.applicationContext
        val title = "[${Build.MODEL}] $category"
        Toast.makeText(app, R.string.bug_report_sending, Toast.LENGTH_SHORT).show()
        // AppScope, а не lifecycleScope: привязка к экрану плеера отменяла корутину
        // при закрытии диалога и рвала сокет посреди запроса (SocketException: Socket closed)
        AppScope.launch(Dispatchers.IO) {
            val body = build(app, service, category)
            // Сохраняем всегда, до отправки: отчёт не должен пропадать из-за сбоя сети.
            // Файл достаётся через adb pull, даже если прокси недоступен.
            val saved = saveLocally(app, body)
            Log.i(TAG, "отчёт собран: ${body.length} символов, файл: $saved")
            val ok = post(title, category, body)
            withContext(Dispatchers.Main) {
                val msg = when {
                    ok -> app.getString(R.string.bug_report_sent)
                    saved != null -> app.getString(R.string.bug_report_saved_only, saved)
                    else -> app.getString(R.string.bug_report_failed)
                }
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Пишет отчёт в каталог приложения на общем хранилище. Возвращает путь или null. */
    private fun saveLocally(ctx: Context, body: String): String? = runCatching {
        val dir = ctx.getExternalFilesDir(null) ?: return@runCatching null
        val f = java.io.File(dir, "vlc-report-" +
                java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date()) + ".txt")
        f.writeText(body)
        f.absolutePath
    }.getOrElse { Log.e(TAG, "не удалось сохранить отчёт", it); null }

    // ---------- сбор ----------

    private fun StringBuilder.sec(name: String) = append("\n\n## ").append(name).append('\n')

    private fun build(ctx: Context, service: PlaybackService, category: String): String {
        val b = StringBuilder()
        b.append("**Что не так:** ").append(category)

        b.sec("Устройство")
        b.append("- Модель: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        b.append("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        b.append("- Сборка: ${Build.DISPLAY}\n")
        b.append("- Чип: ${Build.HARDWARE}, ABI: ${Build.SUPPORTED_ABIS.joinToString()}\n")
        b.append("- Android TV: ")
            .append(ctx.packageManager.hasSystemFeature("android.software.leanback"))
            .append('\n')

        b.sec("Экран")
        runCatching {
            @Suppress("DEPRECATION")
            val d = (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
            @Suppress("DEPRECATION")
            val pt = android.graphics.Point().also { d.getRealSize(it) }
            b.append("- Разрешение: ${pt.x}x${pt.y}\n")
            b.append("- Частота обновления: ${d.refreshRate} Гц\n")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                b.append("- HDR: ${d.hdrCapabilities?.supportedHdrTypes?.joinToString() ?: "нет"}\n")
            }
        }.onFailure { b.append("- недоступно: ${it.message}\n") }

        b.sec("Аудиовыход")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach {
                    b.append("- type=${it.type} ${it.productName}\n")
                }
            } else b.append("- API < 23, список недоступен\n")
        }.onFailure { b.append("- недоступно: ${it.message}\n") }

        b.sec("Медиа")
        val mw = service.currentMediaWrapper
        if (mw == null) b.append("- ничего не воспроизводится\n") else {
            b.append("- Файл: ${mw.uri?.lastPathSegment}\n")
            b.append("- Схема: ${mw.uri?.scheme}\n")   // file / smb / http — важно для сетевых багов
            b.append("- Длительность: ${mw.length} мс\n")
        }

        // Одна ссылка на медиа и для дорожек, и для статистики: getMedia()
        // увеличивает счётчик ссылок libvlc, поэтому её обязательно освободить —
        // иначе каждый отчёт удерживает медиа живым.
        val media = runCatching { service.mediaplayer.media }.getOrNull()
        try {
            appendTracks(b, media)
            appendStats(b, media, service)
        } finally {
            runCatching { media?.release() }
        }

        b.sec("Видеодекодеры")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Список резался первыми 20 записями, а начинается он с аудио —
                // видеодекодеры, ради которых секция и нужна, не попадали в отчёт
                // ни разу. Аудио отбрасываем, видео печатаем целиком: их десятки,
                // не сотни.
                val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { info -> !info.isEncoder && info.supportedTypes.any { it.startsWith("video/") } }
                if (decoders.isEmpty()) b.append("- не найдено\n")
                decoders.forEach { info ->
                    info.supportedTypes.filter { it.startsWith("video/") }.forEach { mime ->
                        b.append("- ${info.name} $mime")
                        // Заявленный максимум против фактического файла — ровно то,
                        // что нужно знать, когда декодер выгружается сам на 4K.
                        runCatching {
                            val vc = info.getCapabilitiesForType(mime).videoCapabilities
                            b.append(" до ${vc.supportedWidths.upper}x${vc.supportedHeights.upper}")
                            b.append(" @${vc.supportedFrameRates.upper} fps")
                        }
                        b.append('\n')
                    }
                }
            } else b.append("- API < 21\n")
        }.onFailure { b.append("- недоступны: ${it.message}\n") }

        // без права READ_LOGS сюда попадают только собственные строки VLC — этого достаточно
        b.sec("Логи VLC (последние 200 строк)")
        b.append("```\n")
        b.append(runCatching { trimLateSpam(Logcat.logcat.lines()).takeLast(200).joinToString("\n") }
            .getOrElse { "логи недоступны: ${it.message}\n" })
        b.append("\n```\n")
        return b.toString()
    }

    /**
     * Выбрасывает поток строк «picture is too late», оставляя первые пять и счётчик.
     *
     * Их прилетает по десятку в секунду, и в отчёте они вытесняли из окна логов
     * всё остальное — в том числе строки открытия декодера, ради которых лог и
     * читают. Сам факт опозданий не теряется: он и так виден по счётчику
     * потерянных кадров выше.
     */
    private fun trimLateSpam(lines: List<String>): List<String> {
        var late = 0
        val out = ArrayList<String>(lines.size)
        for (l in lines) {
            if (l.contains("picture is too late")) {
                late++
                if (late > 5) continue
            }
            out.add(l)
        }
        if (late > 5) out.add("… ещё ${late - 5} строк «picture is too late» вырезано")
        return out
    }

    /** Дорожки текущего медиа: кодеки, разрешение, частота кадров файла. */
    private fun appendTracks(b: StringBuilder, media: IMedia?) {
        b.sec("Дорожки")
        runCatching {
            if (media == null) b.append("- недоступны\n") else {
                for (i in 0 until media.trackCount) {
                    val t = media.getTrack(i) ?: continue
                    b.append("- type=${t.type} codec=${t.codec} bitrate=${t.bitrate}")
                    if (t.language != null) b.append(" lang=${t.language}")
                    (t as? IMedia.VideoTrack)?.let {
                        b.append(" ${it.width}x${it.height}")
                        // частота кадров файла против частоты экрана выше — главная причина рывков на TV
                        if (it.frameRateDen > 0)
                            b.append(" %.3f fps".format(it.frameRateNum.toFloat() / it.frameRateDen))
                    }
                    (t as? IMedia.AudioTrack)?.let { b.append(" ch=${it.channels} rate=${it.rate}") }
                    b.append('\n')
                }
            }
        }.onFailure { b.append("- недоступны: ${it.message}\n") }
    }

    /**
     * Счётчики воспроизведения — потерянные кадры и битрейты, ради которых отчёт
     * и существует.
     *
     * Раньше здесь стоял только `service.lastStats`, а это статистика ПРЕДЫДУЩЕГО
     * файла: `PlayerController` заполняет её при смене медиа. Пока играет первый
     * файл, там null — отчёт писал «недоступна» ровно тогда, когда цифры нужны.
     * Берём счётчики того, что играет сейчас; lastStats остаётся запасным на
     * случай, когда воспроизведение уже закончилось.
     */
    private fun appendStats(b: StringBuilder, media: IMedia?, service: PlaybackService) {
        b.sec("Статистика воспроизведения")
        val s = runCatching { media?.stats }.getOrNull() ?: service.lastStats
        if (s == null) {
            b.append("- недоступна (ничего не воспроизводится)\n")
            return
        }
        b.append("- Потеряно кадров: ${s.lostPictures}\n")
        b.append("- Показано кадров: ${s.displayedPictures}\n")
        b.append("- Повреждено блоков демуксера: ${s.demuxCorrupted}\n")
        b.append("- Разрывов потока: ${s.demuxDiscontinuity}\n")
        b.append("- Битрейт демуксера: ${s.demuxBitrate}\n")
        b.append("- Входной битрейт: ${s.inputBitrate}\n")
        b.append("- Проиграно аудиобуферов: ${s.playedAbuffers}\n")
        b.append("- Потеряно аудиобуферов: ${s.lostAbuffers}\n")
        // готовый вывод, чтобы не читать цифры вручную в каждом тикете
        if (s.lostPictures > 0 || s.demuxCorrupted > 0)
            b.append("\n> Потеряны кадры или повреждены блоки: битый файл, нехватка CPU либо сбой аппаратного декодера.\n")
        if (s.lostAbuffers > 0)
            b.append("\n> Потеряны аудиобуферы: заикание звука.\n")
    }

    // ---------- отправка ----------

    private fun post(title: String, category: String, body: String): Boolean {
        if (ENDPOINT.contains("<")) {
            Log.e(TAG, "ENDPOINT не настроен — укажите адрес своего Worker")
            return false
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20000
                readTimeout = 40000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            // JSONObject экранирует сам — руками кавычки не клеим
            val json = JSONObject()
                .put("title", title)
                .put("category", category)
                .put("body", body)
                .toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(json) }
            val code = conn.responseCode
            Log.i(TAG, "ответ прокси: $code")
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "отправка не удалась", e)
            false
        } finally {
            conn?.disconnect()
        }
    }
}
