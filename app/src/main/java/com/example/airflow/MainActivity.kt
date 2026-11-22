package com.example.airflow

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
private const val PREFS = "airflow_prefs"

data class AppInfo(val name: String, val packageName: String, val icon: Drawable, var isSelected: Boolean)

class AppListAdapter(private val apps: List<AppInfo>) : RecyclerView.Adapter<AppListAdapter.VH>() {
    inner class VH(val row: LinearLayout, val check: CheckBox) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        }
        val icon = ImageView(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(42), ctx.dp(42)) }
        val name = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = ctx.dp(12) }
            textSize = 16f
        }
        val check = CheckBox(ctx)
        row.addView(icon); row.addView(name); row.addView(check)
        row.tag = arrayOf(icon, name)
        return VH(row, check)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = apps[position]
        val (icon, name) = holder.row.tag as Array<View>
        (icon as ImageView).setImageDrawable(app.icon)
        (name as TextView).text = app.name
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = app.isSelected
        holder.check.setOnCheckedChangeListener { _, checked -> app.isSelected = checked }
        holder.row.setOnClickListener { holder.check.isChecked = !holder.check.isChecked }
    }

    override fun getItemCount() = apps.size
    fun getSelectedPackages(): Set<String> = apps.filter { it.isSelected }.map { it.packageName }.toSet()
    fun updateSelection(selected: Set<String>) {
        apps.forEach { it.isSelected = selected.contains(it.packageName) }
        notifyDataSetChanged()
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: AppListAdapter
    private lateinit var timerSpinner: Spinner
    private lateinit var btnStart: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Airflow Focus"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        tvStatus = TextView(this).apply {
            text = "Status: Not Active"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(tvStatus)

        root.addView(Button(this).apply {
            text = "1. Enable Accessibility Permission"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(TextView(this).apply {
            text = "2. Select Apps to ALLOW (Only these will run):"
            setPadding(0, dp(16), 0, dp(8))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(rv)

        val timerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        timerLayout.addView(TextView(this).apply { text = "Select Time: "; textSize = 16f })
        timerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("15 Mins", "30 Mins", "1 Hour", "2 Hours", "3 Hours"))
        }
        timerLayout.addView(timerSpinner)
        root.addView(timerLayout)

        btnStart = Button(this).apply {
            text = "START FOCUS SESSION"
            setBackgroundColor(android.graphics.Color.parseColor("#1976D2"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener { startSession() }
        }
        root.addView(btnStart)

        setContentView(root)

        adapter = AppListAdapter(loadApps())
        rv.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        val selected = prefs.getStringSet("allowed_apps", emptySet()) ?: emptySet()
        adapter.updateSelection(selected)
        
        val endTime = prefs.getLong("session_end_time", 0L)
        if (System.currentTimeMillis() < endTime) {
            val remainingMins = (endTime - System.currentTimeMillis()) / 60000
            tvStatus.text = "Status: ACTIVE (Remaining: ~$remainingMins mins)"
            tvStatus.setTextColor(android.graphics.Color.RED)
            btnStart.text = "STOP SESSION"
            btnStart.setBackgroundColor(android.graphics.Color.RED)
        } else {
            tvStatus.text = "Status: Not Active"
            tvStatus.setTextColor(android.graphics.Color.GRAY)
            btnStart.text = "START FOCUS SESSION"
            btnStart.setBackgroundColor(android.graphics.Color.parseColor("#1976D2"))
        }
    }

    private fun startSession() {
        val endTime = prefs.getLong("session_end_time", 0L)
        if (System.currentTimeMillis() < endTime) {
            prefs.edit().putLong("session_end_time", 0L).apply()
            Toast.makeText(this, "Session Stopped", Toast.LENGTH_SHORT).show()
        } else {
            val selected = adapter.getSelectedPackages()
            if (selected.isEmpty()) {
                Toast.makeText(this, "Select at least 1 app to allow!", Toast.LENGTH_SHORT).show()
                return
            }
            
            val durationMinutes = when(timerSpinner.selectedItemPosition) {
                0 -> 15
                1 -> 30
                2 -> 60
                3 -> 120
                4 -> 180
                else -> 30
            }
            
            val newEndTime = System.currentTimeMillis() + (durationMinutes * 60 * 1000)
            prefs.edit()
                .putStringSet("allowed_apps", selected)
                .putLong("session_end_time", newEndTime)
                .apply()
                
            Toast.makeText(this, "Focus Session Started!", Toast.LENGTH_SHORT).show()
        }
        onResume()
    }

    private fun loadApps(): List<AppInfo> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val selected = prefs.getStringSet("allowed_apps", emptySet()) ?: emptySet()
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm), selected.contains(it.activityInfo.packageName))
            }.sortedBy { it.name.lowercase() }
    }
}

class AppBlockerService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val endTime = prefs.getLong("session_end_time", 0L)
        if (System.currentTimeMillis() > endTime) return 

        val allowed = prefs.getStringSet("allowed_apps", emptySet()) ?: emptySet()

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val launcherPkg = resolveInfo?.activityInfo?.packageName ?: ""

        // Added com.android.settings and com.android.phone so you don't get locked out of the system
        val safeApps = setOf(
            packageName,
            "com.android.systemui",
            "android",
            "com.android.settings",
            "com.android.phone",
            launcherPkg
        )

        if (pkg !in allowed && pkg !in safeApps) {
            startActivity(Intent(this, BlockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
    }
    override fun onInterrupt() {}
}

class BlockActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        root.addView(TextView(this).apply { text = "🛑"; textSize = 80f; gravity = Gravity.CENTER })
        root.addView(TextView(this).apply { 
            text = "App Blocked by Airflow"; textSize = 24f; setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })
        root.addView(TextView(this).apply { 
            text = "Focus session is active. Only selected apps are allowed right now."; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        root.addView(Button(this).apply {
            text = "Go to Home"
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener { 
                startActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                finish()
            }
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(32)
        })
        setContentView(root)
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        startActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        finish()
    }
}
