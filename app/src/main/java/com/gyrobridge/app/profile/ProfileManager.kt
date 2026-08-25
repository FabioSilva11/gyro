package com.gyrobridge.app.profile

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.gyrobridge.app.data.repository.ProfileRepository
import com.gyrobridge.app.domain.model.ControlProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(val label: String, val packageName: String, val icon: android.graphics.drawable.Drawable)

class ProfileManager(private val context: Context, private val repository: ProfileRepository) {
    suspend fun launchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        resolved.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            InstalledApp(info.loadLabel(pm).toString(), pkg, info.loadIcon(pm))
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    suspend fun profileForPackage(packageName: String): ControlProfile? = repository.findByPackage(packageName)

    fun launch(profile: ControlProfile): Boolean {
        val intent = profile.packageName?.let(context.packageManager::getLaunchIntentForPackage) ?: Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
}
