package dev.minios.ocremote.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val UPDATES_DIRECTORY_NAME = "app-updates"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

    fun getApkUri(context: Context, apkFile: File): Uri {
        val normalizedFile = apkFile.canonicalFile
        val updatesDirectory = File(context.cacheDir, UPDATES_DIRECTORY_NAME).canonicalFile
        require(normalizedFile.startsWith(updatesDirectory)) {
            "APK must be inside ${updatesDirectory.absolutePath}"
        }

        return FileProvider.getUriForFile(
            context,
            context.packageName + FILE_PROVIDER_SUFFIX,
            normalizedFile,
        )
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createManageUnknownSourcesIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun createInstallIntent(context: Context, apkFile: File): Intent {
        val apkUri = getApkUri(context, apkFile)
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun installApk(context: Context, apkFile: File) {
        val intent = if (canRequestPackageInstalls(context)) {
            createInstallIntent(context, apkFile)
        } else {
            createManageUnknownSourcesIntent(context)
        }
        context.startActivity(intent)
    }
}
