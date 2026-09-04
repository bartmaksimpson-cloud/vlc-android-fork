/*
 * UpdateDelegate.kt — обновление из GitHub Releases своего репозитория.
 *
 * Скачивание и установку НЕ дублируем: в VLC уже есть AutoUpdate.downloadAndInstall,
 * он принимает произвольный URL и ставит через настроенный FileProvider.
 * Здесь только то, чего у VLC нет — поиск свежего релиза на GitHub.
 *
 * БЕЗОПАСНОСТЬ: подлинность обновления обеспечивает Android — она откажется ставить
 * APK, подписанный не тем ключом, что установленное приложение. Поэтому все сборки
 * обязаны подписываться ОДНИМ постоянным keystore. Потеря ключа = обновления
 * невозможны навсегда, только переустановка с потерей данных.
 */
package org.videolan.vlc.gui.helpers

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.videolan.vlc.R
import org.videolan.vlc.util.AutoUpdate
import java.net.HttpURLConnection
import java.net.URL

object UpdateDelegate {

    private const val TAG = "VLC/Update"

    // Ваш репозиторий. Тег релиза = versionCode числом, например 3070111.
    private const val REPO = "bartmaksimpson-cloud/vlc-android-fork"

    private class Release(val versionCode: Long, val name: String, val url: String, val mb: Long)

    fun check(activity: FragmentActivity) {
        if (REPO.contains("<")) { toast(activity, R.string.update_not_configured); return }
        toast(activity, R.string.update_checking)
        activity.lifecycleScope.launch {
            val rel = withContext(Dispatchers.IO) { fetchLatest() }
            when {
                rel == null -> toast(activity, R.string.update_check_failed)
                rel.versionCode <= installedVersion(activity) -> toast(activity, R.string.update_up_to_date)
                else -> confirm(activity, rel)
            }
        }
    }

    private fun installedVersion(ctx: Context): Long = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    private fun fetchLatest(): Release? = runCatching {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection()
                as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 15000; readTimeout = 20000
        }
        val json = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val o = JSONObject(json)
        // тег = versionCode, чтобы сравнение было числовым, а не строковым ("10" > "9")
        val vc = o.getString("tag_name").filter { it.isDigit() }.toLongOrNull() ?: return@runCatching null
        val assets = o.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (!a.getString("name").endsWith(".apk")) continue
            val url = a.getString("browser_download_url")
            // только https: по http обновление подменяется на лету
            if (!url.startsWith("https://")) return@runCatching null
            return@runCatching Release(vc, a.getString("name"), url, a.optLong("size") / 1024 / 1024)
        }
        null
    }.getOrElse { Log.e(TAG, "проверка обновления не удалась", it); null }

    private fun confirm(activity: FragmentActivity, rel: Release) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_available)
            .setMessage(activity.getString(R.string.update_details, rel.name, rel.mb))
            .setPositiveButton(R.string.update_install) { _, _ ->
                activity.lifecycleScope.launch {
                    // скачивание и установка — переиспользуем готовое из VLC
                    AutoUpdate.downloadAndInstall(activity.application, rel.url) { loading ->
                        if (loading) toast(activity, R.string.update_downloading)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(activity: Activity, res: Int) =
        Toast.makeText(activity, res, Toast.LENGTH_LONG).show()
}
