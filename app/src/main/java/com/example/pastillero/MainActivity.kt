package com.example.pastillero

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private val onNavigationItemSelectedListener = BottomNavigationView.OnNavigationItemSelectedListener { item ->
        when (item.itemId) {
            R.id.navigation_home -> {
                replaceFragment(HomeFragment())
                return@OnNavigationItemSelectedListener true
            }
            R.id.navigation_calendar -> {
                replaceFragment(CalendarFragment())
                return@OnNavigationItemSelectedListener true
            }
            R.id.navigation_connection -> {
                replaceFragment(ConnectionFragment())
                return@OnNavigationItemSelectedListener true
            }
            R.id.navigation_account -> {
                val email = intent.getStringExtra("USER_EMAIL")
                val accountFragment = AccountFragment().apply {
                    arguments = Bundle().apply {
                        putString("USER_EMAIL", email)
                    }
                }
                replaceFragment(accountFragment)
                return@OnNavigationItemSelectedListener true
            }
        }
        false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navView: BottomNavigationView = findViewById(R.id.nav_view)
        navView.setOnNavigationItemSelectedListener(onNavigationItemSelectedListener)

        if (savedInstanceState == null) {
            replaceFragment(HomeFragment())
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction().replace(R.id.nav_host_fragment, fragment).commit()
    }
}