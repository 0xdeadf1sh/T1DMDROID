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

    /**
     * Guarantee the package still owns a LAUNCHER entry, and re-enable the default one when it does not.
     *
     * The per-component state [apply] writes is not a manifest fact: PackageManager persists it per user
     * in `package-restrictions.xml`, where it SURVIVES a reinstall and OVERRIDES `android:enabled`. So a
     * build that retires the alias a device happened to be sitting on (the Windows XP and Teto themes
     * were removed with theirs) leaves that alias unresolvable while the survivors keep an explicit
     * DISABLED override — zero components match `category.LAUNCHER`, and since [MainActivity] carries no
     * LAUNCHER filter of its own the app drops off the home screen and out of the drawer entirely, with
     * only `am start` left to reach it. Called from [T1dmApplication.onCreate], which every wake-up path
     * runs through (Activity, BOOT_COMPLETED, a widget broadcast, the CgmWatchdog job), precisely because
     * recovery must not presuppose the user can still launch the app.
     *
     * Idempotent, and one binder round-trip on the settled path. DEFAULT on the Tron alias means the
     * manifest's `android:enabled="true"` is in force (a fresh install, or a state we reset); the others
     * ship disabled, so for them only an explicit ENABLED counts.
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
