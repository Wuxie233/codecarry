package dev.minios.ocremote.domain.model

enum class ConnectionPhase {
    CheckingServer,
    LoadingWorkspace,
    SyncingSessions,
    RestoringActivity,
    OpeningLiveUpdates,
    WaitingToRetry,
}
