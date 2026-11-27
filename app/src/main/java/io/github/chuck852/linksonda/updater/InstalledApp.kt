package io.github.chuck852.linksonda.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

internal data class InstalledApp(
    val versionCode: Long,
    val versionName: String,
    val signerSha256: String,
    val sdk: Int,
    val debuggable: Boolean,
)

internal fun readInstalledApp(context: Context): InstalledApp? {
    val packageManager = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }
    val info = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(context.packageName, flags)
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    }
    val signer = info.signerSha256() ?: return null
    return InstalledApp(
        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        },
        versionName = info.versionName.orEmpty(),
        signerSha256 = signer,
        sdk = Build.VERSION.SDK_INT,
        debuggable = info.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
    )
}

internal class ApkVerifier(private val context: Context) {
    fun verify(
        file: File,
        update: OfferedUpdate,
        installedSignerSha256: String,
        installedVersionCode: Long,
    ): UpdaterError? {
        val metadata = update.metadata
        if (metadata.schemaVersion != 1 || metadata.testOnly) return UpdaterError.VERIFY_FAILED
        if (metadata.versionCode <= installedVersionCode) return UpdaterError.VERIFY_FAILED
        if (!file.isFile || file.length() != metadata.apk.size) return UpdaterError.VERIFY_FAILED
        if (file.sha256() != ReleaseSelector.normalizeSha256(metadata.apk.sha256)) {
            return UpdaterError.VERIFY_FAILED
        }
        if (metadata.versionCode <= 0) return UpdaterError.VERIFY_FAILED
        if (metadata.minSdk > Build.VERSION.SDK_INT) return UpdaterError.VERIFY_FAILED
        if (metadata.applicationId != context.packageName) return UpdaterError.VERIFY_FAILED
        if (ReleaseSelector.normalizeSha256(metadata.signerCertificateSha256) !=
            ReleaseSelector.normalizeSha256(installedSignerSha256)
        ) {
            return UpdaterError.VERIFY_FAILED
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        } catch (_: Exception) {
            null
        } ?: return UpdaterError.VERIFY_FAILED

        if (info.packageName != metadata.applicationId) return UpdaterError.VERIFY_FAILED
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        if (versionCode != metadata.versionCode) return UpdaterError.VERIFY_FAILED
        val application = info.applicationInfo ?: return UpdaterError.VERIFY_FAILED
        if (application.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            return UpdaterError.VERIFY_FAILED
        }
        if (application.minSdkVersion > Build.VERSION.SDK_INT) {
            return UpdaterError.VERIFY_FAILED
        }
        if (info.signerSha256() != ReleaseSelector.normalizeSha256(metadata.signerCertificateSha256)) {
            return UpdaterError.VERIFY_FAILED
        }
        return null
    }
}

internal fun PackageInfo.signerSha256(): String? {
    val signatures: List<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        signingInfo?.apkContentsSigners?.toList().orEmpty()
    } else {
        @Suppress("DEPRECATION")
        signatures?.toList().orEmpty()
    }
    val first = signatures.firstOrNull() ?: return null
    return MessageDigest.getInstance("SHA-256")
        .digest(first.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
