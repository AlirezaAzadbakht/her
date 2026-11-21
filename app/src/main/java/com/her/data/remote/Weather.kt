package com.her.data.remote

import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Today's and tomorrow's forecast from Open-Meteo (free, no key). Only a point rounded to about 1 km is sent. */
open class WeatherClient(
    private val base: String = "https://api.open-meteo.com/v1/forecast",
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
) {
    /** A short summary for the model, or null when the forecast can't be had. Never throws. */
    open suspend fun forecast(lat: Double, lng: Double): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val url = base.toHttpUrl().newBuilder()
                .addQueryParameter("latitude", roundCoord(lat))
                .addQueryParameter("longitude", roundCoord(lng))
                .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max")
                .addQueryParameter("hourly", "precipitation_probability")
                .addQueryParameter("forecast_days", "2")
                .addQueryParameter("timezone", "auto")
                .build()
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                summarizeForecast(JSONObject(response.body?.string().orEmpty()))
            }
        }.getOrNull()
    }
}

/** Two decimals is about 1 km: enough for a forecast, not enough to find a house. */
internal fun roundCoord(value: Double): String = String.format(Locale.US, "%.2f", value)

internal fun summarizeForecast(raw: JSONObject): JSONObject {
    val daily = raw.getJSONObject("daily")
    val dates = daily.getJSONArray("time")
    val hourly = raw.optJSONObject("hourly")
    val days = JSONArray()
    for (i in 0 until dates.length()) {
        val date = dates.getString(i)
        val rainyHours = JSONArray()
        hourly?.let { h ->
            val times = h.getJSONArray("time")
            val chance = h.getJSONArray("precipitation_probability")
            for (j in 0 until times.length()) {
                val t = times.getString(j)
                if (t.startsWith(date) && chance.optInt(j) >= 50) rainyHours.put(t.substringAfter('T'))
            }
        }
        days.put(JSONObject().apply {
            put("date", date)
            put("conditions", weatherWord(daily.getJSONArray("weather_code").optInt(i)))
            put("highC", daily.getJSONArray("temperature_2m_max").optDouble(i))
            put("lowC", daily.getJSONArray("temperature_2m_min").optDouble(i))
            put("rainChancePct", daily.getJSONArray("precipitation_probability_max").optInt(i))
            put("windMaxKmh", daily.getJSONArray("wind_speed_10m_max").optDouble(i))
            put("gustsMaxKmh", daily.getJSONArray("wind_gusts_10m_max").optDouble(i))
            if (rainyHours.length() > 0) put("rainLikelyHours", rainyHours)
        })
    }
    return JSONObject().put("days", days)
}

/** WMO weather codes, as Open-Meteo reports them. */
internal fun weatherWord(code: Int): String = when (code) {
    0 -> "clear"
    1, 2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "fog"
    in 51..57 -> "drizzle"
    in 61..67 -> "rain"
    in 71..77 -> "snow"
    in 80..82 -> "rain showers"
    85, 86 -> "snow showers"
    in 95..99 -> "thunderstorm"
    else -> "unknown"
}
