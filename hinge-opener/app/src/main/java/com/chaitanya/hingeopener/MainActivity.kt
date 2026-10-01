package com.chaitanya.hingeopener

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    /** Bumped on every resume so permission rows re-check after returning from Settings. */
    private var resumeTick by mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }

    private val projectionConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                BubbleService.start(this, r.resultCode, data)
                toast("Bubble is on. Open Hinge.")
            } else {
                toast("Screen capture permission is needed to capture profiles.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFB57BEE),
                    background = Color(0xFF121016),
                    surface = Color(0xFF121016),
                ),
            ) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    // ------------------------------------------------------------ permission checks

    private fun hasOverlay() = Settings.canDrawOverlays(this)

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName,
        )
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasNotifications() = NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun hingeInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    // ------------------------------------------------------------ permission requests

    private fun requestOverlay() = startActivity(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
    )

    private fun requestUsageAccess() {
        toast("Find \"Hinge Opener\" in the list and allow usage access.")
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun requestNotifications() {
        val flags = getSharedPreferences("onboarding", Context.MODE_PRIVATE)
        val askedBefore = flags.getBoolean("asked_notifications", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedBefore &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            flags.edit { putBoolean("asked_notifications", true) }
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Already denied once (Android won't show the dialog again) or pre-13: open settings.
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
    }

    private fun startBubble() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Force "entire screen" so the bubble can capture Hinge while our app is in the background.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionConsent.launch(intent)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ UI

    @Composable
    private fun Screen() {
        // Reading resumeTick re-runs the permission checks below every time the app resumes.
        @Suppress("UNUSED_VARIABLE") val refresh = resumeTick
        val running by BubbleService.running.collectAsStateWithLifecycle()

        var savedKey by remember { mutableStateOf(prefs.apiKey) }
        var hingePkg by remember { mutableStateOf(prefs.hingePackage) }

        val overlay = hasOverlay()
        val usage = hasUsageAccess()
        val notif = hasNotifications()
        val hasKey = savedKey.isNotBlank()
        val ready = overlay && usage && notif && hasKey

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text("Hinge Opener", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "Tap the bubble to capture, long-press to get openers.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(20.dp))

            SectionTitle("1. Setup")
            SetupRow("Draw over other apps", "Shows the bubble and result card.", overlay, ::requestOverlay)
            SetupRow("Usage access", "Detects when Hinge is open, so the bubble only shows there.", usage, ::requestUsageAccess)
            SetupRow("Notifications", "Required for the always-on service notification.", notif, ::requestNotifications)
            SetupRow("Anthropic API key", "Set it in Settings below.", hasKey, null)
            if (!hingeInstalled(hingePkg)) {
                Text(
                    "Hinge ($hingePkg) isn't installed on this phone yet.",
                    color = Color(0xFFFFB86C), fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.size(16.dp))
            if (running) {
                Text("● Bubble is running", color = Color(0xFF7CE38B), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { BubbleService.stop(this@MainActivity) }, Modifier.fillMaxWidth()) {
                    Text("Stop bubble")
                }
            } else {
                Button(onClick = ::startBubble, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    Text("Start bubble")
                }
                Text(
                    if (ready) "Android will ask to record your screen. Choose \"Entire screen\" if asked."
                    else "Finish the setup steps above first.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.size(28.dp))
            HorizontalDivider()
            Spacer(Modifier.size(16.dp))
            SectionTitle("2. Settings")
            ApiKeyField(savedKey) { savedKey = it }
            Spacer(Modifier.size(20.dp))
            RunnerUpsField()
            Spacer(Modifier.size(20.dp))
            StyleField()
            Spacer(Modifier.size(20.dp))
            OutlinedTextField(
                value = hingePkg,
                onValueChange = { hingePkg = it; prefs.hingePackage = it },
                label = { Text("Hinge package name") },
                supportingText = { Text("Leave as co.hinge.app unless Hinge changes it.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(32.dp))
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
    }

    @Composable
    private fun SetupRow(title: String, desc: String, ok: Boolean, onFix: (() -> Unit)?) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B24)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ok) "✓" else "✗",
                    color = if (ok) Color(0xFF7CE38B) else Color(0xFFFF6B6B),
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Medium)
                    Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!ok && onFix != null) {
                    TextButton(onClick = onFix) { Text("Grant") }
                }
            }
        }
    }

    @Composable
    private fun ApiKeyField(savedKey: String, onSaved: (String) -> Unit) {
        var input by remember { mutableStateOf("") }
        Text("Anthropic API key", fontWeight = FontWeight.Medium)
        Text(
            if (savedKey.isBlank()) "Not set. Get one at console.anthropic.com."
            else "Saved (…${savedKey.takeLast(4)}). Stored encrypted on this phone.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text("sk-ant-…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = {
                prefs.apiKey = input
                onSaved(prefs.apiKey)
                input = ""
                toast("API key saved")
            }, enabled = input.isNotBlank()) { Text("Save key") }
            if (savedKey.isNotBlank()) {
                OutlinedButton(onClick = { prefs.apiKey = ""; onSaved("") }) { Text("Remove") }
            }
        }
    }

    @Composable
    private fun RunnerUpsField() {
        var n by remember { mutableIntStateOf(prefs.runnerUps) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Runner-ups", fontWeight = FontWeight.Medium)
                Text("Extra options shown under the best reply.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { n = (n - 1).coerceAtLeast(0); prefs.runnerUps = n }, enabled = n > 0) { Text("−") }
            Text("$n", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 14.dp))
            OutlinedButton(
                onClick = { n = (n + 1).coerceAtMost(Prefs.MAX_RUNNER_UPS); prefs.runnerUps = n },
                enabled = n < Prefs.MAX_RUNNER_UPS,
            ) { Text("+") }
        }
    }

    @Composable
    private fun StyleField() {
        var style by remember { mutableStateOf(prefs.style) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("My style", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = { style = Prompts.DEFAULT_STYLE; prefs.style = style }) { Text("Reset to default") }
        }
        Text("Saved automatically. Sent to Claude with every request.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = style,
            onValueChange = { style = it; prefs.style = it },
            minLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
