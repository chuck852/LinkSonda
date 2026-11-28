package io.github.chuck852.linksonda.updater

/** One in-flight PackageInstaller session; stale callbacks must not affect a newer one. */
internal class InstallSessionTracker {
    @Volatile
    private var activeSessionId: Int? = null

    @Synchronized
    fun begin(sessionId: Int): Boolean {
        if (activeSessionId != null) return false
        activeSessionId = sessionId
        return true
    }

    fun current(): Int? = activeSessionId

    fun isCurrent(sessionId: Int): Boolean = activeSessionId == sessionId

    @Synchronized
    fun finish(sessionId: Int) {
        if (activeSessionId == sessionId) activeSessionId = null
    }
}
