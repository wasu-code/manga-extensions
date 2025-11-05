package eu.kanade.tachiyomi.extension.all.cookieinjector

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.ConfigurableSource
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

const val JS_CODE = """
avascript:(function () {
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

class CookieInjector : ConfigurableSource {

    @Suppress("unused")
    val id: Long = 9999999

    @Suppress("unused")
    val name = "Cookie Injector"
    val lang = "all"

    private val context by lazy { Injekt.get<Application>() }

    private val network: NetworkHelper = Injekt.get()
    private val prefs: SharedPreferences =
        Injekt.get<Application>().getSharedPreferences("cookie_injector_prefs", 0)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = "COPY_TRIGGER"
            title = "Tap to copy"
            summary = """
                1. Copy this text
                2. Go to browser
                3. Go to website you want to login into
                4. While on the website:
                    + Click the search bar
                    + Remove everything there
                    + Paste copied text
                    + Go to the start of the text and add letter "j" on the beginning
                    + Click enter
                5. Go back here and paste cookies string down below
            """.trimIndent() // TODO: text is too long and not fully displayed
            setDefaultValue(false)
            setOnPreferenceClickListener {
                val clipboard = context.getSystemService("clipboard") as ClipboardManager
                val clip = ClipData.newPlainText("Js code", JS_CODE)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(screen.context, "📋 Example JSON copied to clipboard", Toast.LENGTH_SHORT).show()
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
