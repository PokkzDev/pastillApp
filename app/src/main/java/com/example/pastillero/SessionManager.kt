package com.pokkzdev.pastillapp

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages user session persistence with a 1-hour inactivity timeout.
 * Session is preserved when app is minimized and only expires:
 * 1. When user explicitly logs out
 * 2. After 1 hour of inactivity (no user action)
 */
object SessionManager {
    private const val PREFS_NAME = "pastillapp_session"
    private const val KEY_LAST_ACTIVITY = "last_activity_timestamp"
    private const val SESSION_TIMEOUT_MS = 60 * 60 * 1000L // 1 hour in milliseconds

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * Updates the last activity timestamp to current time.
     * Should be called on user interactions.
     */
    fun updateLastActivity() {
        prefs?.edit()?.putLong(KEY_LAST_ACTIVITY, System.currentTimeMillis())?.apply()
    }

    /**
     * Checks if the session is still valid (within timeout window).
     * Returns true if the user was active within the last hour.
     */
    fun isSessionValid(): Boolean {
        val lastActivity = prefs?.getLong(KEY_LAST_ACTIVITY, 0L) ?: 0L
        if (lastActivity == 0L) {
            return false
        }
        val elapsed = System.currentTimeMillis() - lastActivity
        return elapsed < SESSION_TIMEOUT_MS
    }

    /**
     * Gets the remaining time in milliseconds before session expires.
     * Returns 0 if session is already expired or never started.
     */
    fun getRemainingSessionTime(): Long {
        val lastActivity = prefs?.getLong(KEY_LAST_ACTIVITY, 0L) ?: 0L
        if (lastActivity == 0L) {
            return 0L
        }
        val remaining = SESSION_TIMEOUT_MS - (System.currentTimeMillis() - lastActivity)
        return if (remaining > 0) remaining else 0L
    }

    /**
     * Clears session data. Called on explicit logout.
     */
    fun clearSession() {
        prefs?.edit()?.clear()?.apply()
    }

    /**
     * Checks if user has a valid Firebase session AND the timeout hasn't expired.
     * This is the main method to determine if user should stay logged in.
     */
    fun hasValidSession(): Boolean {
        val auth = FirebaseAuthClient.auth
        val hasFirebaseUser = auth.currentUser != null
        val sessionNotExpired = isSessionValid()
        
        // If Firebase user exists but session expired, we should log them out
        if (hasFirebaseUser && !sessionNotExpired) {
            return false
        }
        
        return hasFirebaseUser && sessionNotExpired
    }
}




