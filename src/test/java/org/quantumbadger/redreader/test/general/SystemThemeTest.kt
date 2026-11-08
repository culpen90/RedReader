/*******************************************************************************
 * This file is part of RedReader.
 *
 * RedReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RedReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RedReader.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package org.quantumbadger.redreader.test.general

import android.app.Application
import android.content.res.Configuration
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.settings.SettingsActivity
import org.quantumbadger.redreader.settings.SettingsFragment
import org.quantumbadger.redreader.settings.types.AppearanceTheme
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "notnight")
class SystemThemeTest {

	private val app: Application get() = RuntimeEnvironment.getApplication()
	private val prefs get() = General.getSharedPrefs(app)

	@Before
	fun initializePreferences() {
		// Avoid full application initialization and its network/background work.
		General::class.java.getDeclaredField("mPrefs").apply {
			isAccessible = true
			(get(null) as AtomicReference<*>).set(null)
		}
		mapOf(
			"mRes" to app.resources,
			"sharedPrefs" to prefs,
			"appContext" to app
		).forEach { (name, value) ->
			PrefsUtility::class.java.getDeclaredField(name).apply {
				isAccessible = true
				set(null, value)
			}
		}
	}

	private fun putTheme(key: Int, value: String) {
		prefs.edit().putString(app.getString(key), value).apply()
	}

	private fun followSystem(enabled: Boolean) {
		prefs.edit().putBoolean(
			app.getString(R.string.pref_appearance_theme_use_system_key), enabled
		).apply()
	}

	private fun configuredTheme(night: Boolean): AppearanceTheme {
		val configuration = Configuration(app.resources.configuration)
		configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
			if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
		return PrefsUtility.appearance_theme(app.createConfigurationContext(configuration).resources)
	}

	@Test
	fun manualThemeRemainsIndependentOfSystemMode() {
		putTheme(R.string.pref_appearance_theme_key, "green")
		assertFalse(PrefsUtility.appearance_theme_use_system())
		assertEquals(AppearanceTheme.GREEN, configuredTheme(false))
		assertEquals(AppearanceTheme.GREEN, configuredTheme(true))
	}

	@Test
	fun followingSystemUsesDefaultsThenCustomizedSlots() {
		followSystem(true)
		assertEquals(AppearanceTheme.RED, configuredTheme(false))
		assertEquals(AppearanceTheme.NIGHT, configuredTheme(true))
		putTheme(R.string.pref_appearance_theme_light_key, "blue")
		putTheme(R.string.pref_appearance_theme_dark_key, "ultrablack")
		assertEquals(AppearanceTheme.BLUE, configuredTheme(false))
		assertEquals(AppearanceTheme.ULTRABLACK, configuredTheme(true))
		// The saved manual selection is retained while following the system.
		putTheme(R.string.pref_appearance_theme_key, "orange")
		assertEquals(AppearanceTheme.ULTRABLACK, configuredTheme(true))
	}

	@Test
	fun applicationModeChangesUpdateGlobalThemeAndNightHelpers() {
		followSystem(true)
		RuntimeEnvironment.setQualifiers("notnight")
		assertEquals(AppearanceTheme.RED, PrefsUtility.appearance_theme())
		assertFalse(PrefsUtility.isNightMode())
		RuntimeEnvironment.setQualifiers("night")
		assertEquals(AppearanceTheme.NIGHT, PrefsUtility.appearance_theme())
		assertTrue(PrefsUtility.isNightMode())
		RuntimeEnvironment.setQualifiers("notnight")
		assertEquals(AppearanceTheme.RED, PrefsUtility.appearance_theme())
		assertFalse(PrefsUtility.isNightMode())
	}

	@Test
	fun allModePreferencesRequestActivityRestart() {
		for (key in intArrayOf(
			R.string.pref_appearance_theme_key,
			R.string.pref_appearance_theme_use_system_key,
			R.string.pref_appearance_theme_light_key,
			R.string.pref_appearance_theme_dark_key
		)) {
			assertTrue(PrefsUtility.isRestartRequired(app, app.getString(key)))
		}
	}

	@Test
	fun lightAndDarkSelectorsCoverEveryExistingThemeExactlyOnce() {
		val all = app.resources.getStringArray(R.array.pref_appearance_theme_return).toSet()
		val light = app.resources.getStringArray(R.array.pref_appearance_theme_light_return).toSet()
		val dark = app.resources.getStringArray(R.array.pref_appearance_theme_dark_return).toSet()
		assertTrue(light.intersect(dark).isEmpty())
		assertEquals(all, light + dark)
	}

	private fun settings(): SettingsFragment {
		val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
		activity.onPanelSelected("appearance")
		activity.supportFragmentManager.executePendingTransactions()
		return activity.supportFragmentManager.findFragmentById(R.id.single_fragment_container)
			as SettingsFragment
	}

	private fun toggle(fragment: SettingsFragment, enabled: Boolean) {
		val preference = fragment.findPreference<CheckBoxPreference>(
			app.getString(R.string.pref_appearance_theme_use_system_key)
		)!!
		assertTrue(preference.callChangeListener(enabled))
		preference.isChecked = enabled
	}

	@Test
	fun enablingFollowSystemPreservesManualLightThemeAndChangesVisibleSelectors() {
		putTheme(R.string.pref_appearance_theme_key, "green")
		val fragment = settings()
		val manual = fragment.findPreference<ListPreference>(
			app.getString(R.string.pref_appearance_theme_key)
		)!!
		val light = fragment.findPreference<ListPreference>(
			app.getString(R.string.pref_appearance_theme_light_key)
		)!!
		val dark = fragment.findPreference<ListPreference>(
			app.getString(R.string.pref_appearance_theme_dark_key)
		)!!
		assertTrue(manual.isVisible)
		assertFalse(light.isVisible)
		assertFalse(dark.isVisible)
		toggle(fragment, true)
		assertFalse(manual.isVisible)
		assertTrue(light.isVisible)
		assertTrue(dark.isVisible)
		assertEquals("green", light.value)
		assertEquals(AppearanceTheme.GREEN, configuredTheme(false))
		assertEquals(AppearanceTheme.NIGHT, configuredTheme(true))
	}

	@Test
	fun enablingFollowSystemPreservesManualDarkThemeInItsOwnSlot() {
		putTheme(R.string.pref_appearance_theme_key, "night_lowcontrast")
		val fragment = settings()
		toggle(fragment, true)
		assertEquals(AppearanceTheme.NIGHT_LOWCONTRAST, configuredTheme(true))
		assertEquals(AppearanceTheme.RED, configuredTheme(false))
	}

	@Test
	fun disablingFollowSystemCopiesTheCurrentlyActiveNightTheme() {
		RuntimeEnvironment.setQualifiers("night")
		followSystem(true)
		putTheme(R.string.pref_appearance_theme_dark_key, "ultrablack")
		putTheme(R.string.pref_appearance_theme_key, "green")
		val fragment = settings()
		toggle(fragment, false)
		assertEquals(AppearanceTheme.ULTRABLACK, configuredTheme(true))
		assertEquals(AppearanceTheme.ULTRABLACK, configuredTheme(false))
		val manual = fragment.findPreference<ListPreference>(
			app.getString(R.string.pref_appearance_theme_key)
		)!!
		assertTrue(manual.isVisible)
		assertEquals("ultrablack", manual.value)
		assertEquals(manual.entry, manual.summary)
	}
}
