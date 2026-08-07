package dev.wuxie233.codecarry.domain.model

enum class ConnectionPhase {
    CheckingServer,
    LoadingWorkspace,
    SyncingSessions,
    RestoringActivity,
    OpeningLiveUpdates,
    WaitingToRetry,
}
