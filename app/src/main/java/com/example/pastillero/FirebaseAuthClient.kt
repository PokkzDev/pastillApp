package com.pokkzdev.pastillapp

import com.google.firebase.auth.FirebaseAuth
import android.util.Log

object FirebaseAuthClient {
    private val TAG = "FirebaseAuthClient"
    
    val auth: FirebaseAuth by lazy {
        try {
            FirebaseAuth.getInstance().also {
                Log.d(TAG, "Firebase Auth initialized successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase Auth", e)
            throw e
        }
    }
}



