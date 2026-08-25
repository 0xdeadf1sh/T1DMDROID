package com.t1dm.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.t1dm.core.design.ThemeIds
import timber.log.Timber

/**
 * Class names stay namespace-qualified: AGP expands `android:name=".Launcher*"` against the manifest
 * namespace, not the applicationId. Enable before disable, or the launcher is left with no entry.
 */
object LauncherIconManager {

    private const val NS = "com.t1dm.app"

    private val aliasForTheme = mapOf(
        ThemeIds.TRON to "$NS.LauncherTron",
        ThemeIds.UMBRELLA to "$NS.LauncherUmbrella",
        ThemeIds.HELLO_KITTY to "$NS.LauncherKitty",
    )

    private fun aliasForThemeId(themeId: String?): String =
        aliasForTheme[themeId] ?: aliasForTheme.getValue(ThemeIds.TRON)

    /** Pass the alias that launched the task as [keepEnabledAlias]; disabling it can strand the task. */
    fun apply(context: Context, themeId: String?, keepEnabledAlias: String? = null) {
        val pm = context.packageManager
        val pkg = context.packageName
        val wanted = aliasForThemeId(themeId)

        val current = runCatching {
            aliasForTheme.values.firstOrNull { alias ->
                pm.getComponentEnabledSetting(ComponentName(pkg, alias)) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
        }.getOrNull()
        if (current == wanted) return

        runCatching {
            pm.setComponentEnabledSetting(
                ComponentName(pkg, wanted),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            aliasForTheme.values.filter { it != wanted && it != keepEnabledAlias }.forEach { alias ->
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, alias),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }.onFailure { Timber.w(it, "launcher-icon swap to %s failed", wanted) }
    }

    /**
     * Component state persists in `package-restrictions.xml`, surviving reinstall and overriding
     * `android:enabled`, so a retired alias can leave zero `category.LAUNCHER` components. Only Tron
     * ships enabled in the manifest, so only for it does DEFAULT count as enabled.
     */
    fun ensureLauncherEntry(context: Context) {
        val pm = context.packageManager
        val pkg = context.packageName
        val fallback = ComponentName(pkg, aliasForTheme.getValue(ThemeIds.TRON))
        runCatching {
            val tron = pm.getComponentEnabledSetting(fallback)
            if (tron == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
                tron == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ) {
                return@runCatching
            }
            val anyEnabled = aliasForTheme.values.any { alias ->
                pm.getComponentEnabledSetting(ComponentName(pkg, alias)) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            if (!anyEnabled) {
                Timber.w("no launcher alias enabled — restoring %s", fallback.className)
                pm.setComponentEnabledSetting(
                    fallback,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }.onFailure { Timber.w(it, "launcher-entry recovery failed") }
    }
}
