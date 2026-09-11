package com.xueti.learn.update

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xueti.learn.R
import kotlinx.coroutines.launch

/** 发现新版本弹窗（首页与设置页共用） */
fun AppCompatActivity.showUpdateDialog(info: UpdateChecker.ReleaseInfo) {
    val message = buildString {
        append(getString(R.string.update_found, info.version, UpdateChecker.currentVersion()))
        if (info.notes.isNotBlank()) {
            append("\n\n")
            append(info.notes.take(1500))
        }
    }
    AlertDialog.Builder(this)
        .setTitle(R.string.update_title)
        .setMessage(message)
        .setPositiveButton(R.string.update_now) { _, _ -> downloadAndInstall(info) }
        .setNeutralButton(R.string.update_open_page) { _, _ -> openReleasePage(info.pageUrl) }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/** 下载更新 APK 并调起系统安装器 */
fun AppCompatActivity.downloadAndInstall(info: UpdateChecker.ReleaseInfo) {
    if (info.apkUrl.isBlank()) {
        openReleasePage(info.pageUrl)
        return
    }
    val progressDialog = AlertDialog.Builder(this)
        .setTitle(R.string.downloading_update)
        .setMessage("0%")
        .setCancelable(false)
        .create()
    progressDialog.show()

    lifecycleScope.launch {
        runCatching {
            ApkDownloader.download(
                context = this@downloadAndInstall,
                url = info.apkUrl,
                fileName = "XueTi-v${info.version}.apk"
            ) { percent ->
                runOnUiThread { progressDialog.setMessage("$percent%") }
            }
        }.onSuccess { file ->
            progressDialog.dismiss()
            runCatching { ApkDownloader.install(this@downloadAndInstall, file) }
                .onFailure {
                    Toast.makeText(
                        this@downloadAndInstall,
                        R.string.install_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
        }.onFailure {
            progressDialog.dismiss()
            Toast.makeText(this@downloadAndInstall, R.string.download_failed, Toast.LENGTH_LONG).show()
        }
    }
}

fun AppCompatActivity.openReleasePage(url: String) {
    if (url.isBlank()) return
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
