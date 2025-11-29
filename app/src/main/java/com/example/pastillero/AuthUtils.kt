package com.pokkzdev.pastillapp

import android.content.Context

fun getSpanishErrorMessage(context: Context, exception: Exception): String {
    val errorMessage = exception.message?.lowercase() ?: ""
    val errorCode = (exception as? com.google.firebase.auth.FirebaseAuthException)?.errorCode

    return when {
        errorCode == "ERROR_WRONG_PASSWORD" ||
        errorCode == "ERROR_INVALID_CREDENTIAL" ||
        errorMessage.contains("invalid login credentials") ||
        errorMessage.contains("invalid credentials") ||
        errorMessage.contains("invalid email or password") ->
            context.getString(R.string.error_invalid_credentials)

        errorCode == "ERROR_EMAIL_NOT_VERIFIED" ||
        errorMessage.contains("email not confirmed") ||
        errorMessage.contains("email confirmation") ->
            context.getString(R.string.error_email_not_confirmed)

        errorCode == "ERROR_USER_NOT_FOUND" ||
        errorMessage.contains("user not found") ||
        errorMessage.contains("user does not exist") ->
            context.getString(R.string.error_user_not_found)

        errorCode == "ERROR_INVALID_EMAIL" ||
        errorMessage.contains("invalid email") ||
        errorMessage.contains("email format") ->
            context.getString(R.string.error_invalid_email)

        errorCode == "ERROR_WEAK_PASSWORD" ||
        (errorMessage.contains("password") && errorMessage.contains("short")) ||
        errorMessage.contains("weak password") ->
            context.getString(R.string.error_weak_password)

        errorCode == "ERROR_TOO_MANY_REQUESTS" ||
        errorMessage.contains("too many requests") ||
        errorMessage.contains("rate limit") ->
            context.getString(R.string.error_too_many_requests)

        errorCode == "ERROR_EMAIL_ALREADY_IN_USE" ||
        errorMessage.contains("user already registered") ||
        errorMessage.contains("already exists") ||
        errorMessage.contains("already registered") ->
            context.getString(R.string.error_user_already_exists)

        errorCode == "ERROR_NETWORK_REQUEST_FAILED" ||
        errorMessage.contains("network") ||
        errorMessage.contains("connection") ||
        errorMessage.contains("timeout") ->
            context.getString(R.string.error_network)

        else -> context.getString(R.string.error_auth_failed)
    }
}
