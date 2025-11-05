package com.example.pastillero

import android.content.Context

fun getSpanishErrorMessage(context: Context, exception: Exception): String {
    val errorMessage = exception.message?.lowercase() ?: ""

    return when {
        errorMessage.contains("invalid login credentials") ||
        errorMessage.contains("invalid credentials") ||
        errorMessage.contains("invalid email or password") ->
            context.getString(R.string.error_invalid_credentials)

        errorMessage.contains("email not confirmed") ||
        errorMessage.contains("email confirmation") ->
            context.getString(R.string.error_email_not_confirmed)

        errorMessage.contains("user not found") ||
        errorMessage.contains("user does not exist") ->
            context.getString(R.string.error_user_not_found)

        errorMessage.contains("invalid email") ||
        errorMessage.contains("email format") ->
            context.getString(R.string.error_invalid_email)

        errorMessage.contains("password") && errorMessage.contains("short") ||
        errorMessage.contains("weak password") ->
            context.getString(R.string.error_weak_password)

        errorMessage.contains("too many requests") ||
        errorMessage.contains("rate limit") ->
            context.getString(R.string.error_too_many_requests)

        errorMessage.contains("user already registered") ||
        errorMessage.contains("already exists") ||
        errorMessage.contains("already registered") ->
            context.getString(R.string.error_user_already_exists)

        errorMessage.contains("network") ||
        errorMessage.contains("connection") ||
        errorMessage.contains("timeout") ->
            context.getString(R.string.error_network)

        else -> context.getString(R.string.error_auth_failed)
    }
}
