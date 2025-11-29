package com.pokkzdev.pastillapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var navView: BottomNavigationView
    private var currentFragmentTag: String? = null
    private val fragmentCache = mutableMapOf<String, Fragment>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize session manager and update activity
        SessionManager.init(this)
        SessionManager.updateLastActivity()
        
        setContentView(R.layout.activity_main)

        navView = findViewById(R.id.nav_view)
        
        if (savedInstanceState == null) {
            // Set selected item before listener to avoid triggering it
            navView.selectedItemId = R.id.navigation_home
            // Initialize with home fragment
            navigateToFragment(HomeFragment::class.java.simpleName) {
                HomeFragment()
            }
        } else {
            currentFragmentTag = savedInstanceState.getString("current_fragment_tag") 
                ?: HomeFragment::class.java.simpleName
            
            // Restore fragment cache from saved state - only fragments with tags
            supportFragmentManager.fragments.forEach { fragment ->
                fragment.tag?.let { tag ->
                    fragmentCache[tag] = fragment
                }
            }
            
            // Restore the selected item based on current fragment tag
            when (currentFragmentTag) {
                HomeFragment::class.java.simpleName -> navView.selectedItemId = R.id.navigation_home
                CalendarFragment::class.java.simpleName -> navView.selectedItemId = R.id.navigation_calendar
                ConnectionFragment::class.java.simpleName -> navView.selectedItemId = R.id.navigation_connection
                AccountFragment::class.java.simpleName -> navView.selectedItemId = R.id.navigation_account
                else -> navView.selectedItemId = R.id.navigation_home
            }
            
            // Ensure fragment visibility is correct
            try {
                val transaction = supportFragmentManager.beginTransaction()
                var hasChanges = false
                supportFragmentManager.fragments.forEach { fragment ->
                    fragment.tag?.let { tag ->
                        if (tag == currentFragmentTag) {
                            if (fragment.isHidden) {
                                transaction.show(fragment)
                                hasChanges = true
                            }
                        } else {
                            if (!fragment.isHidden) {
                                transaction.hide(fragment)
                                hasChanges = true
                            }
                        }
                    }
                }
                if (hasChanges) {
                    transaction.commitAllowingStateLoss()
                }
            } catch (e: Exception) {
                // If restoration fails, initialize with home fragment
                currentFragmentTag = HomeFragment::class.java.simpleName
                navView.selectedItemId = R.id.navigation_home
                navigateToFragment(HomeFragment::class.java.simpleName) {
                    HomeFragment()
                }
            }
        }
        
        navView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    navigateToFragment(HomeFragment::class.java.simpleName) {
                        HomeFragment()
                    }
                    true
                }
                R.id.navigation_calendar -> {
                    navigateToFragment(CalendarFragment::class.java.simpleName) {
                        CalendarFragment()
                    }
                    true
                }
                R.id.navigation_connection -> {
                    navigateToFragment(ConnectionFragment::class.java.simpleName) {
                        ConnectionFragment()
                    }
                    true
                }
                R.id.navigation_account -> {
                    val email = intent.getStringExtra("USER_EMAIL")
                    navigateToFragment(AccountFragment::class.java.simpleName) {
                        AccountFragment().apply {
                            arguments = Bundle().apply {
                                putString("USER_EMAIL", email)
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentFragmentTag?.let {
            outState.putString("current_fragment_tag", it)
        }
    }

    override fun onResume() {
        super.onResume()
        
        // Check if session is still valid when resuming
        if (!SessionManager.hasValidSession()) {
            // Session expired, redirect to login
            navigateToLogin()
            return
        }
        
        // Session is valid, update last activity
        SessionManager.updateLastActivity()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // Update last activity on any user interaction
        SessionManager.updateLastActivity()
    }

    private fun navigateToLogin() {
        // Clear session and Firebase
        SessionManager.clearSession()
        FirebaseAuthClient.auth.signOut()
        SelectedDevice.device = null
        
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun navigateToFragment(tag: String, fragmentFactory: () -> Fragment) {
        // Prevent redundant navigation to the same fragment
        if (currentFragmentTag == tag) {
            return
        }

        val transaction = supportFragmentManager.beginTransaction()

        // Set custom animations for smooth transitions
        transaction.setCustomAnimations(
            android.R.anim.fade_in,
            android.R.anim.fade_out,
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )

        // Hide current fragment if exists
        currentFragmentTag?.let { currentTag ->
            fragmentCache[currentTag]?.let { fragment ->
                if (fragment.isAdded && !fragment.isHidden) {
                    transaction.hide(fragment)
                }
            }
        }

        // Get or create fragment
        val fragment = fragmentCache[tag] ?: fragmentFactory().also {
            fragmentCache[tag] = it
        }

        // Show or add fragment
        if (fragment.isAdded) {
            if (fragment.isHidden) {
                transaction.show(fragment)
            }
        } else {
            transaction.add(R.id.nav_host_fragment, fragment, tag)
        }

        transaction.commitAllowingStateLoss()

        currentFragmentTag = tag
    }
}