package io.github.chuck852.linksonda.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import io.github.chuck852.linksonda.BuildConfig
import io.github.chuck852.linksonda.settings.AppSettings
import io.github.chuck852.linksonda.settings.SettingsStore
import io.github.chuck852.linksonda.settings.UpdateChannel
import io.github.chuck852.linksonda.settings.UpdateCheckStatus
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val ACTION_INSTALL_STATUS = "io.github.chuck852.linksonda.updater.INSTALL_STATUS"
internal const val UPDATE_NOTIFICATION_CHANNEL = "updates"
internal const val UPDATE_NOTIFICATION_ID = 4001

internal class Updater(
    context: Context,
    private val settingsStore: SettingsStore,
    private val apiUrl: String = GITHUB_RELEASES_URL,
    private val testSource: Boolean = false,
    private val http: UpdaterHttpClient = HttpUpdaterHttpClient(testSource),
    private val installed: InstalledApp? = readInstalledApp(context),
    private val clock: () -> Instant = Instant::now,
    private val debugBuild: Boolean = BuildConfig.DEBUG,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private val notifier = UpdateNotifier(appContext)
    private val verifier = ApkVerifier(appContext)
    private val initialSettings = settingsStore.current()
    private val _state = MutableStateFlow(
        stateFromSettings(
            settings = initialSettings,
            base = UpdaterUiState(
                currentVersionName = BuildConfig.VERSION_NAME,
                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            ),
        ),
    )
    val state: StateFlow<UpdaterUiState> = _state.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var installJob: Job? = null
    @Volatile
    private var readyFile: File? = null
    @Volatile
    private var downloadGeneration = 0L
    @Volatile
    private var installGeneration = 0L
    private val downloadLock = Any()

    init {
        scope.launch {
            settingsStore.settings.collect { settings ->
                applySettings(settings)
            }
        }
    }

    fun checkNow() {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch {
            runCheck(requireAutoConsent = false)
        }
    }

    suspend fun checkNowAwait(requireAutoConsent: Boolean = false): UpdateCheckStatus {
        runCheck(requireAutoConsent = requireAutoConsent)
        return _state.value.status
    }

    fun download() {
        val update = _state.value.available ?: return
        if (downloadJob?.isActive == true) return
        if (synchronized(downloadLock) { readyFile?.isFile == true }) {
            _state.update { it.copy(readyToInstall = true, download = null, error = null) }
            return
        }
        val generation = synchronized(downloadLock) { ++downloadGeneration }
        val destination = cacheFile(update, generation)
        downloadJob = scope.launch {
            var completed = false
            fun isCurrent() = generation == downloadGeneration
            fun updateIfCurrent(block: (UpdaterUiState) -> UpdaterUiState) {
                if (isCurrent()) _state.update(block)
            }
            try {
                destination.parentFile?.mkdirs()
                updateIfCurrent {
                    it.copy(
                        download = DownloadProgress(0, update.metadata.apk.size),
                        readyToInstall = false,
                        error = null,
                    )
                }
                when (val result = http.download(update.downloadUrl, destination) { downloaded, total ->
                    updateIfCurrent { it.copy(download = DownloadProgress(downloaded, total)) }
                }) {
                    is UpdaterDownloadResult.Failure -> {
                        updateIfCurrent {
                            it.copy(download = null, error = result.error, readyToInstall = false)
                        }
                    }

                    is UpdaterDownloadResult.Success -> {
                        val verifyError = verifier.verify(
                            file = destination,
                            update = update,
                            installedSignerSha256 = _state.value.installedSigner(),
                            installedVersionCode = installed?.versionCode ?: 0,
                        )
                        if (!isCurrent()) {
                            destination.delete()
                        } else if (verifyError != null) {
                            destination.delete()
                            _state.update {
                                it.copy(download = null, error = verifyError, readyToInstall = false)
                            }
                        } else {
                            synchronized(downloadLock) {
                                if (isCurrent()) {
                                    readyFile = destination
                                    completed = true
                                } else {
                                    destination.delete()
                                }
                            }
                            if (completed) {
                                _state.update {
                                    it.copy(download = null, readyToInstall = true, error = null)
                                }
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                updateIfCurrent {
                    it.copy(download = null, error = UpdaterError.NETWORK, readyToInstall = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateIfCurrent {
                    it.copy(download = null, error = UpdaterError.DOWNLOAD_FAILED, readyToInstall = false)
                }
            } finally {
                if (!completed || !isCurrent()) {
                    destination.delete()
                }
                updateIfCurrent { it.copy(download = null) }
            }
        }
    }

    fun cancelDownload() {
        synchronized(downloadLock) {
            downloadGeneration++
            downloadJob?.cancel()
            downloadJob = null
            readyFile?.delete()
            readyFile = null
        }
        _state.update { it.copy(download = null, error = null) }
    }

    fun install() {
        if (installJob?.isActive == true) return
        val update = _state.value.available ?: return
        val file = synchronized(downloadLock) { readyFile?.takeIf { it.isFile } } ?: return
        val generation = ++installGeneration
        fun isCurrent() = generation == installGeneration
        _state.update {
            it.copy(
                installState = InstallState.INSTALLING,
                installPermissionRequired = false,
                error = null,
            )
        }
        installJob = scope.launch {
            var sessionId: Int? = null
            var committed = false
            try {
                if (!appContext.packageManager.canRequestPackageInstalls()) {
                    if (isCurrent()) {
                        _state.update {
                            it.copy(
                                installPermissionRequired = true,
                                installState = InstallState.WAITING_PERMISSION,
                            )
                        }
                    }
                    return@launch
                }
                val verifyError = verifier.verify(
                    file = file,
                    update = update,
                    installedSignerSha256 = _state.value.installedSigner(),
                    installedVersionCode = installed?.versionCode ?: 0,
                )
                if (verifyError != null) {
                    if (isCurrent()) {
                        _state.update { it.copy(error = verifyError, installState = InstallState.FAILED) }
                    }
                    return@launch
                }
                val installer = appContext.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(appContext.packageName)
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("LinkSonda-update.apk", 0, file.length()).use { output ->
                        file.inputStream().use { input -> input.copyTo(output) }
                        session.fsync(output)
                    }
                    if (!isCurrent() ||
                        _state.value.available?.versionCode != update.versionCode ||
                        _state.value.available?.downloadUrl != update.downloadUrl
                    ) {
                        return@use
                    }
                    session.commit(installPendingIntent().intentSender)
                    committed = true
                }
                if (committed && isCurrent()) {
                    _state.update { it.copy(readyToInstall = true, installState = InstallState.INSTALLING) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                if (isCurrent()) {
                    _state.update {
                        it.copy(
                            installPermissionRequired = true,
                            installState = InstallState.WAITING_PERMISSION,
                        )
                    }
                }
            } catch (_: Exception) {
                if (isCurrent()) {
                    _state.update { it.copy(error = UpdaterError.INSTALL_FAILED, installState = InstallState.FAILED) }
                }
            } finally {
                if (!committed) {
                    sessionId?.let { id ->
                        runCatching { appContext.packageManager.packageInstaller.abandonSession(id) }
                    }
                }
            }
        }
    }

    fun onInstallResult(status: Int) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                synchronized(downloadLock) {
                    readyFile?.delete()
                    readyFile = null
                }
                _state.update {
                    it.copy(installState = InstallState.IDLE, readyToInstall = false)
                }
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> _state.update {
                it.copy(installState = InstallState.INSTALLING)
            }
            else -> _state.update {
                it.copy(
                    installState = InstallState.FAILED,
                    readyToInstall = readyFile?.isFile == true,
                    error = UpdaterError.INSTALL_FAILED,
                )
            }
        }
    }

    private suspend fun runCheck(requireAutoConsent: Boolean): Unit = checkMutex.withLock {
        if (debugBuild && !testSource) {
            _state.update { it.copy(status = UpdateCheckStatus.INVALID_RELEASE, error = UpdaterError.INVALID_RELEASE) }
            return@withLock
        }
        val settings = try {
            settingsStore.initialize()
            settingsStore.current()
        } catch (_: Exception) {
            _state.update { it.copy(checking = false, status = UpdateCheckStatus.NETWORK_ERROR, error = UpdaterError.NETWORK) }
            return@withLock
        }
        if (requireAutoConsent && !settings.updateChecksEnabled) return@withLock
        val retryAt = settings.lastUpdateRetryAt
        if (retryAt != null && retryAt.isAfter(clock())) {
            _state.update {
                it.copy(checking = false, status = UpdateCheckStatus.RATE_LIMITED, retryAt = retryAt, error = UpdaterError.RATE_LIMITED)
            }
            return@withLock
        }
        _state.update { it.copy(checking = true, error = null) }
        try {
            when (val response = http.get(apiUrl, settings.updateEtag)) {
                is UpdaterHttpResult.NotModified -> finish(
                    status = UpdateCheckStatus.NOT_MODIFIED,
                    settings = settings,
                    etag = settings.updateEtag,
                )

                is UpdaterHttpResult.Failure -> finish(
                    status = when (response.error) {
                        UpdaterError.RATE_LIMITED -> UpdateCheckStatus.RATE_LIMITED
                        UpdaterError.NO_RELEASE -> UpdateCheckStatus.NO_RELEASE
                        UpdaterError.INVALID_RELEASE -> UpdateCheckStatus.INVALID_RELEASE
                        else -> UpdateCheckStatus.NETWORK_ERROR
                    },
                    settings = settings,
                    etag = settings.updateEtag,
                    error = response.error,
                    retryAt = response.retryAt,
                )

                is UpdaterHttpResult.Response -> {
                    val releases = runCatching {
                        updaterJson.decodeFromString<List<GitHubRelease>>(response.body.orEmpty())
                    }.getOrNull()
                        ?: return@withLock finish(
                            UpdateCheckStatus.INVALID_RELEASE,
                            settings,
                            etag = null,
                            error = UpdaterError.INVALID_RELEASE,
                        )
                    val loaded = mutableListOf<LoadedRelease>()
                    var metadataFailure = false
                    val channelReleases = releases.filter { release ->
                        !release.draft &&
                            (settings.updateChannel == UpdateChannel.PREVIEW || !release.prerelease)
                    }
                    val scan = channelReleases.take(MAX_METADATA_CANDIDATES)
                    val truncated = channelReleases.size > scan.size
                    val deadline = System.nanoTime() + CHECK_BUDGET_MS * 1_000_000
                    for (release in scan) {
                        if (System.nanoTime() > deadline) {
                            return@withLock finish(
                                UpdateCheckStatus.NETWORK_ERROR,
                                settings,
                                etag = null,
                                error = UpdaterError.NETWORK,
                            )
                        }
                        val metadataAsset = release.assets.firstOrNull { it.name == RELEASE_METADATA_ASSET } ?: continue
                        when (val metadataResponse = http.get(metadataAsset.browserDownloadUrl)) {
                            is UpdaterHttpResult.Response -> {
                                val metadata = runCatching {
                                    updaterJson.decodeFromString<ReleaseMetadata>(metadataResponse.body.orEmpty())
                                }.getOrNull()
                                if (metadata == null) {
                                    metadataFailure = true
                                } else {
                                    loaded += LoadedRelease(release, metadata)
                                }
                            }
                            is UpdaterHttpResult.Failure -> {
                                if (metadataResponse.error == UpdaterError.RATE_LIMITED) {
                                    return@withLock finish(
                                        UpdateCheckStatus.RATE_LIMITED,
                                        settings,
                                        etag = null,
                                        error = metadataResponse.error,
                                        retryAt = metadataResponse.retryAt,
                                    )
                                }
                                metadataFailure = true
                            }
                            UpdaterHttpResult.NotModified -> metadataFailure = true
                        }
                    }
                    val installed = installed ?: return@withLock finish(
                        UpdateCheckStatus.INVALID_RELEASE,
                        settings,
                        etag = null,
                        error = UpdaterError.INVALID_RELEASE,
                    )
                    when (val selection = ReleaseSelector.choose(
                        releases = loaded,
                        channel = settings.updateChannel,
                        installedVersionCode = installed.versionCode,
                        installedSignerSha256 = installed.signerSha256,
                        installedSdk = installed.sdk,
                        applicationId = appContext.packageName,
                        allowTestSource = testSource,
                    )) {
                        is ReleaseSelection.Available -> finish(
                            status = UpdateCheckStatus.UPDATE_AVAILABLE,
                            settings = settings,
                            etag = if (metadataFailure || truncated) null else response.etag,
                            update = selection.update,
                        )
                        ReleaseSelection.UpToDate -> finish(
                            if (truncated) UpdateCheckStatus.NO_RELEASE else UpdateCheckStatus.UP_TO_DATE,
                            settings,
                            etag = null,
                        )
                        is ReleaseSelection.Invalid -> finish(
                            UpdateCheckStatus.INVALID_RELEASE,
                            settings,
                            etag = if (metadataFailure) null else response.etag,
                            UpdaterError.INVALID_RELEASE,
                        )
                        ReleaseSelection.NoMetadata -> finish(
                            if (metadataFailure) UpdateCheckStatus.INVALID_RELEASE else UpdateCheckStatus.NO_RELEASE,
                            settings,
                            etag = null,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            finish(
                status = UpdateCheckStatus.NETWORK_ERROR,
                settings = settings,
                etag = settings.updateEtag,
                error = UpdaterError.NETWORK,
            )
        } finally {
            _state.update { it.copy(checking = false) }
        }
    }

    private suspend fun finish(
        status: UpdateCheckStatus,
        settings: AppSettings,
        etag: String?,
        error: UpdaterError? = null,
        retryAt: Instant? = null,
        update: OfferedUpdate? = null,
    ) {
        val checkedAt = clock()
        val keepPrevious = update == null && status in setOf(
            UpdateCheckStatus.NOT_MODIFIED,
            UpdateCheckStatus.NETWORK_ERROR,
            UpdateCheckStatus.RATE_LIMITED,
        )
        val previous = if (keepPrevious) {
            settings.lastOfferedUpdateJson?.let { json ->
                runCatching { updaterJson.decodeFromString(OfferedUpdate.serializer(), json) }.getOrNull()
            }
        } else {
            null
        }
        val effective = update ?: previous
        val notificationSent = effective?.let { maybeNotify(it) } == true
        val offered = effective?.let {
            updaterJson.encodeToString(OfferedUpdate.serializer(), it)
        }
        if (effective == null && status in setOf(UpdateCheckStatus.UP_TO_DATE, UpdateCheckStatus.NO_RELEASE)) {
            notifier.cancel()
        }
        val stored = settingsStore.update { current ->
            current.copy(
                updateEtag = etag,
                lastUpdateCheckAt = checkedAt,
                lastUpdateStatus = status,
                lastUpdateRetryAt = retryAt,
                lastOfferedVersionCode = effective?.versionCode,
                lastOfferedVersionName = effective?.versionName,
                lastOfferedReleaseNotes = effective?.notes?.takeIf { it.isNotBlank() },
                lastOfferedChannel = effective?.channel,
                lastOfferedUpdateJson = offered,
                lastNotifiedVersionCode = if (notificationSent) {
                    effective.versionCode
                } else {
                    current.lastNotifiedVersionCode
                },
            )
        }
        _state.update {
            stateFromSettings(
                settings = stored?.current ?: settings.copy(
                    lastUpdateCheckAt = checkedAt,
                    lastUpdateStatus = status,
                    lastUpdateRetryAt = retryAt,
                    lastOfferedVersionCode = effective?.versionCode,
                    lastOfferedVersionName = effective?.versionName,
                    lastOfferedReleaseNotes = effective?.notes?.takeIf { it.isNotBlank() },
                    lastOfferedChannel = effective?.channel,
                    lastOfferedUpdateJson = offered,
                ),
                base = it.copy(error = error),
            )
        }
    }

    private fun applySettings(settings: AppSettings) {
        val offered = settings.lastOfferedUpdateJson?.let { json ->
            runCatching { updaterJson.decodeFromString(OfferedUpdate.serializer(), json) }.getOrNull()
        }?.takeIf { it.versionCode > BuildConfig.VERSION_CODE && settings.updateChannel.accepts(it.channel) }
        val old = _state.value.available
        val discardDownload = offered?.versionCode != old?.versionCode ||
            offered?.downloadUrl != old?.downloadUrl ||
            offered?.metadata?.apk?.sha256 != old?.metadata?.apk?.sha256
        if (discardDownload) {
            synchronized(downloadLock) {
                downloadGeneration++
                downloadJob?.cancel()
                readyFile?.delete()
                readyFile = null
            }
            installGeneration++
            installJob?.cancel()
        }
        _state.update {
            val base = if (discardDownload) {
                it.copy(
                    available = offered,
                    download = null,
                    readyToInstall = false,
                    installPermissionRequired = false,
                    installState = InstallState.IDLE,
                )
            } else {
                it.copy(available = offered)
            }
            stateFromSettings(settings, base)
        }
    }

    private fun stateFromSettings(settings: AppSettings, base: UpdaterUiState): UpdaterUiState =
        base.copy(
            status = settings.lastUpdateStatus,
            lastCheckAt = settings.lastUpdateCheckAt,
            retryAt = settings.lastUpdateRetryAt,
            available = settings.lastOfferedUpdateJson?.let { json ->
                runCatching { updaterJson.decodeFromString(OfferedUpdate.serializer(), json) }.getOrNull()
            }?.takeIf { it.versionCode > BuildConfig.VERSION_CODE && settings.updateChannel.accepts(it.channel) },
        )

    private fun UpdaterUiState.installedSigner(): String =
        installed?.signerSha256 ?: ""

    private fun maybeNotify(update: OfferedUpdate): Boolean {
        val settings = settingsStore.current()
        if (settings.lastNotifiedVersionCode == update.versionCode) return false
        return notifier.show(update)
    }

    private fun cacheFile(update: OfferedUpdate, generation: Long): File {
        val name = File(update.metadata.apk.name).name.ifBlank { "LinkSonda-${update.versionName}.apk" }
        return File(appContext.cacheDir, "updates/$generation-$name")
    }

    private fun installPendingIntent(): PendingIntent {
        val intent = Intent(appContext, InstallResultReceiver::class.java)
            .setAction(ACTION_INSTALL_STATUS)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(appContext, 0, intent, flags)
    }

    private companion object {
        const val MAX_METADATA_CANDIDATES = 8
        const val CHECK_BUDGET_MS = 60_000L
    }
}
