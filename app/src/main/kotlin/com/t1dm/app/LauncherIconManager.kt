package com.t1dm.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.t1dm.core.design.ThemeIds
import timber.log.Timber

/**
 * Swaps the home-screen launcher icon to match the active theme (issues 2/6) by enabling exactly one
 * `<activity-alias>` and disabling the others. Component class names are namespace-qualified
 * (`com.t1dm.app.Launcher*`) even in the `.pub` flavour, since AGP expands `android:name=".Launcher*"`
 * against the manifest *namespace*, not the applicationId — so the [ComponentName] pairs the runtime
 * package ([Context.getPackageName], = applicationId) with the fixed namespace class name.
 *
 * The new alias is enabled *before* the stale ones are disabled, so the launcher is never left with
 * zero enabled entries (which would blank the icon). `DONT_KILL_APP` keeps the running process alive
 * across the swap.
 */
object LauncherIconManager {

    private const val NS = "com.t1dm.app"

    private val aliasForTheme = mapOf(
        ThemeIds.TRON to "$NS.LauncherTron",
        ThemeIds.UMBRELLA to "$NS.LauncherUmbrella",
        ThemeIds.HELLO_KITTY to "$NS.LauncherKitty",
        ThemeIds.WINDOWS_XP to "$NS.LauncherXp",
        ThemeIds.TETO to "$NS.LauncherTeto",
    )

    /** A custom theme borrows the Tron launcher geometry (matches [iconStyleForTheme]). */
    private fun aliasForThemeId(themeId: String?): String =
        aliasForTheme[themeId] ?: aliasForTheme.getValue(ThemeIds.TRON)

    /**
     * @param keepEnabledAlias an alias to leave enabled regardless — pass the alias that launched the
     * current task (Option-B guard) so a swap can never disable the very component the launcher/recents
     * is holding a handle to, which on HyperOS can otherwise strand or evict the task.
     */
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
        if (current == wanted) return // already correct — avoid a needless launcher churn.

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
}
