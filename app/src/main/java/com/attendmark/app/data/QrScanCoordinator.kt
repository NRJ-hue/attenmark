package com.attendmark.app.data

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class ScanState {
    IDLE,
    VALIDATING,
    SUCCESS,
    ERROR
}

/**
 * Thread-safe coordinator for the single-scan QR attendance lifecycle.
 * Prevents duplicate in-flight network validation requests, rapid-fire camera frame retries,
 * duplicate navigation events, and uncontrolled error loops.
 */
class QrScanCoordinator {
    private val currentState = AtomicReference(ScanState.IDLE)
    private val activeToken = AtomicReference<String?>(null)
    private val hasNavigated = AtomicBoolean(false)
    private var lastErrorMessage: String? = null

    val state: ScanState get() = currentState.get()
    val errorMessage: String? get() = lastErrorMessage
    val currentToken: String? get() = activeToken.get()

    /**
     * Attempt to claim the scan slot for a detected QR payload.
     * Returns true if the coordinator transitioned from IDLE to VALIDATING.
     * Returns false if an attempt is already active, in error, or has succeeded.
     */
    fun startScanAttempt(rawPayload: String): Boolean {
        if (currentState.compareAndSet(ScanState.IDLE, ScanState.VALIDATING)) {
            activeToken.set(rawPayload)
            lastErrorMessage = null
            return true
        }
        return false
    }

    /**
     * Mark validation as successful.
     * Advances state to SUCCESS.
     * Returns true only for the first call (single-navigation guard).
     */
    fun onValidationSuccess(): Boolean {
        currentState.set(ScanState.SUCCESS)
        return hasNavigated.compareAndSet(false, true)
    }

    /**
     * Mark validation as failed with an error message.
     * Transitions state to ERROR and stores the error message.
     * Scanning remains blocked until [retry] is deliberately invoked.
     */
    fun onValidationFailure(message: String) {
        lastErrorMessage = message
        currentState.set(ScanState.ERROR)
    }

    /**
     * Deliberately allow a retry after an error.
     * Transitions from ERROR back to IDLE.
     * Returns true if transition succeeded, false if not in ERROR state.
     */
    fun retry(): Boolean {
        if (currentState.compareAndSet(ScanState.ERROR, ScanState.IDLE)) {
            activeToken.set(null)
            lastErrorMessage = null
            return true
        }
        return false
    }

    /**
     * Check if a scan can be initiated (state is IDLE).
     */
    fun canScan(): Boolean = currentState.get() == ScanState.IDLE

    /**
     * Reset coordinator to initial state (e.g., when screen is reset).
     */
    fun reset() {
        currentState.set(ScanState.IDLE)
        activeToken.set(null)
        lastErrorMessage = null
        hasNavigated.set(false)
    }
}
