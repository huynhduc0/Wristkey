package app.wristkey

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import wristkey.R
import java.text.SimpleDateFormat
import java.util.*

class SettingsActivity : AppCompatActivity() {

    lateinit var mfaCodesTimer: Timer
    lateinit var utilities: Utilities

    private lateinit var clock: TextView

    lateinit var searchButton: SwitchMaterial
    lateinit var lockButton: SwitchMaterial
    lateinit var clockButton: SwitchMaterial
    lateinit var roundButton: SwitchMaterial
    lateinit var aboutButton: Button
    lateinit var backButton: Button

    var settingsChanged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        utilities = Utilities(applicationContext)
        mfaCodesTimer = Timer()

        initializeUI()
        startClock()

    }

    override fun onStop() {
        super.onStop()
        mfaCodesTimer.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        mfaCodesTimer.cancel()
        finish()

        if (settingsChanged) {
            finishAffinity()
            startActivity(Intent(applicationContext, MainActivity::class.java))
        }

    }

    override fun onStart() {
        super.onStart()
        mfaCodesTimer = Timer()
    }

    private fun initializeUI () {

        clock = findViewById(R.id.clock)

        searchButton = findViewById (R.id.searchButtonToggle)
        lockButton = findViewById (R.id.lockButton)
        clockButton = findViewById (R.id.clockButton)
        roundButton = findViewById (R.id.roundButton)
        aboutButton = findViewById (R.id.aboutButton)
        backButton = findViewById (R.id.backButton)

        searchButton.isChecked = utilities.vault.getBoolean(utilities.SETTINGS_SEARCH_ENABLED, true)
        searchButton.setOnCheckedChangeListener { _, isChecked ->
            settingsChanged = true
            utilities.vault.edit().remove(utilities.SETTINGS_SEARCH_ENABLED).apply()
            utilities.vault.edit().putBoolean(utilities.SETTINGS_SEARCH_ENABLED, isChecked).apply()
        }

        clockButton.isChecked = utilities.vault.getBoolean(utilities.SETTINGS_CLOCK_ENABLED, true)
        clockButton.setOnCheckedChangeListener { _, isChecked ->
            settingsChanged = true
            utilities.vault.edit().remove(utilities.SETTINGS_CLOCK_ENABLED).apply()
            utilities.vault.edit().putBoolean(utilities.SETTINGS_CLOCK_ENABLED, isChecked).apply()
            if (!isChecked) clock.visibility = View.GONE else clock.visibility = View.VISIBLE
        }

        val lockscreen = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!lockscreen.isKeyguardSecure) utilities.vault.edit().putBoolean(utilities.SETTINGS_LOCK_ENABLED, false).apply()
        lockButton.isChecked = utilities.vault.getBoolean(utilities.SETTINGS_LOCK_ENABLED, false)
        lockButton.setOnCheckedChangeListener { _, isChecked ->
            if (!lockscreen.isKeyguardSecure) {
                lockButton.isChecked = false
                Toast.makeText(this, "Enable screen lock in device settings first!", Toast.LENGTH_LONG).show()
                utilities.vault.edit().remove(utilities.SETTINGS_LOCK_ENABLED).apply()
                utilities.vault.edit().putBoolean(utilities.SETTINGS_LOCK_ENABLED, false).apply()
            } else {
                settingsChanged = true
                utilities.vault.edit().remove(utilities.SETTINGS_LOCK_ENABLED).apply()
                utilities.vault.edit().putBoolean(utilities.SETTINGS_LOCK_ENABLED, isChecked).apply()
            }
        }

        roundButton.isChecked = utilities.vault.getBoolean(utilities.CONFIG_SCREEN_ROUND, resources.configuration.isScreenRound)
        roundButton.setOnCheckedChangeListener { _, isChecked ->
            settingsChanged = true
            utilities.vault.edit().remove(utilities.CONFIG_SCREEN_ROUND).apply()
            utilities.vault.edit().putBoolean(utilities.CONFIG_SCREEN_ROUND, isChecked).apply()
        }

        aboutButton.setOnClickListener {
            startActivity(Intent(applicationContext, AboutActivity::class.java))
        }

        backButton.setOnClickListener {
            finish()
        }

    }

    private fun startClock () {
        if (!utilities.vault.getBoolean(utilities.SETTINGS_CLOCK_ENABLED, true)) clock.visibility = View.GONE

        try {
            mfaCodesTimer.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val hourType = if (android.text.format.DateFormat.is24HourFormat(applicationContext)) "hh" else "HH"
                    val currentHour = SimpleDateFormat(hourType, Locale.getDefault()).format(Date())
                    val currentMinute = SimpleDateFormat("mm", Locale.getDefault()).format(Date())
                    runOnUiThread { clock.text = "$currentHour:$currentMinute" }
                }
            }, 0, 1000)
        } catch (_: IllegalStateException) { }
    }
}