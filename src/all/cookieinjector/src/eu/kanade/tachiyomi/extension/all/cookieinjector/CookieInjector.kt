package eu.kanade.tachiyomi.extension.all.cookieinjector

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.ConfigurableSource
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val BOOKMARKLET = """
javascript:(function () {
  const input = document.createElement('input');
  input.value = JSON.stringify({url : window.location.href, cookie : document.cookie});
  document.body.appendChild(input);
  input.focus();
  input.select();
  var result = document.execCommand('copy');
  document.body.removeChild(input);
  if(result)
    alert('Cookie copied to clipboard');
  else
    prompt('Failed to copy cookie. Manually copy below cookie\n\n', input.value);
})();
"""

enum class BrowserInstruction(val title: String, val content: String) {
    CHROMIUM(
        "Chromium (eg. Chrome)",
        """
            1. Add any website to bookmarks
            2. Edit this bookmark
            3. As content/url of bookmark paste copied text
            4. Go to target website
            5. Click search bar and search for bookmark name
            6. Click this bookmark (marked with ⭐ before name)
        """.trimIndent(),
    ),
    GECKO(
        "Gecko (eg. Firefox)",
        """
            1. Add any website to bookmarks
            2. Edit this bookmark
            3. As content/url of bookmark paste copied text
            4. Go to target website
            5. Open menu > Bookmarks
            6. Click on the bookmark
        """.trimIndent(),
    ),
}

class CookieInjector : ConfigurableSource {

    @Suppress("unused")
    val id: Long = 9999999

    @Suppress("unused")
    val name = "Cookie Injector"
    val lang = "all"

    /**
     * Visible name of the source.
     */
    override fun toString() = name

    private val context by lazy { Injekt.get<Application>() }
    private val network: NetworkHelper = Injekt.get()
    private val prefs: SharedPreferences =
        Injekt.get<Application>().getSharedPreferences("cookie_injector_prefs", 0)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "BROWSER"
            title = "Select browser to get specific instructions"
            summary = BrowserInstruction.valueOf(prefs.getString(key, BrowserInstruction.CHROMIUM.name)!!).content
            entries = BrowserInstruction.values().map { it.title }.toTypedArray()
            entryValues = BrowserInstruction.values().map { it.name }.toTypedArray()
            setDefaultValue(BrowserInstruction.CHROMIUM.name)
            setOnPreferenceChangeListener { _, newValue ->
                val instruction = BrowserInstruction.valueOf(newValue as String)
                summary = instruction.content
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = "COPY_TRIGGER"
            title = "Tap to copy bookmarklet"
            setDefaultValue(false)
            setOnPreferenceClickListener {
                // copy bookmarklet code
                val clipboard = context.getSystemService("clipboard") as ClipboardManager
                val clip = ClipData.newPlainText("Cookie Bookmarklet", BOOKMARKLET)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(screen.context, "Bookmarklet copied to clipboard", Toast.LENGTH_SHORT).show()
                // trigger opening browser
                val intent = Intent().apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse("http://about:blank")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                true
            }
            setOnPreferenceChangeListener { pref, _ ->
                false // prevent from switching to toggled state
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "COOKIE_JSON"
            title = "Paste cookie JSON"
            summary = "Format: {\"url\": \"https://example.com\", \"cookie\": \"a=1; b=2\"}"

            setOnPreferenceChangeListener { _, newValue ->
                val json = newValue.toString().trim()
                try {
                    val obj = JSONObject(json)
                    val url = obj.getString("url")
                    val cookieStr = obj.getString("cookie")

                    val httpUrl = url.toHttpUrl()
                    val parts = httpUrl.host.split(".")
                    val domain = (
                        if (parts.size >= 2) {
                            // take last two segments (example.com)
                            parts.takeLast(2).joinToString(".")
                        } else {
                            parts.takeLast(1)
                        }
                        ) as String
                    android.util.Log.d("AAA", domain)
                    val cookieList = cookieStr.split(";").mapNotNull { pair ->
                        val parts = pair.trim().split("=")
                        if (parts.size == 2) {
                            Cookie.Builder()
                                .domain(domain)
                                .path("/")
                                .name(parts[0].trim())
                                .value(parts[1].trim())
//                                .httpOnly() //TODO
//                                .secure() //TODO
                                .build()
                        } else {
                            null
                        }
                    }

                    network.client.cookieJar.saveFromResponse(httpUrl, cookieList)
                    Toast.makeText(screen.context, "✅ Cookies added for $url", Toast.LENGTH_LONG).show()
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(screen.context, "❌ Invalid JSON format", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }.also(screen::addPreference)
    }
}
