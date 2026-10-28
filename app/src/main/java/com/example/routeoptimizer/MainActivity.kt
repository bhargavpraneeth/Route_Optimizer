package com.example.routeoptimizer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

data class Place(val name: String, val lat: Double, val lon: Double)

object Api {
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "RouteOptimizerApp/1.0")
        c.connectTimeout = 10000
        c.readTimeout = 15000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    fun search(q: String): List<Place> {
        val json = JSONObject(get("https://photon.komoot.io/api/?limit=6&q=${URLEncoder.encode(q, "UTF-8")}"))
        val f = json.getJSONArray("features")
        return (0 until f.length()).map { i ->
            val o = f.getJSONObject(i)
            val p = o.getJSONObject("properties")
            val c = o.getJSONObject("geometry").getJSONArray("coordinates")
            val label = listOf("name", "street", "city", "state", "country")
                .map { p.optString(it) }.filter { it.isNotBlank() }.distinct().joinToString(", ")
            Place(label, c.getDouble(1), c.getDouble(0))
        }
    }

    /** Road-time matrix in seconds from OSRM. Throws on failure. */
    fun matrix(pts: List<Place>): Array<DoubleArray> {
        val coords = pts.joinToString(";") { "${it.lon},${it.lat}" }
        val json = JSONObject(get("https://router.project-osrm.org/table/v1/driving/$coords?annotations=duration"))
        val rows = json.getJSONArray("durations")
        return Array(pts.size) { i ->
            DoubleArray(pts.size) { j -> rows.getJSONArray(i).optDouble(j, 1e9) }
        }
    }

    fun haversineMatrix(pts: List<Place>): Array<DoubleArray> {
        fun km(a: Place, b: Place): Double {
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
            return 2 * 6371.0 * asin(sqrt(h))
        }
        // approximate seconds at 50 km/h
        return Array(pts.size) { i -> DoubleArray(pts.size) { j -> km(pts[i], pts[j]) / 50.0 * 3600.0 } }
    }
}

/** Start fixed at index 0, one-way route. Nearest neighbour + 2-opt. */
fun optimize(d: Array<DoubleArray>): List<Int> {
    val n = d.size
    val left = (1 until n).toMutableList()
    var route = mutableListOf(0)
    while (left.isNotEmpty()) {
        val next = left.minBy { d[route.last()][it] }
        route.add(next); left.remove(next)
    }
    fun cost(r: List<Int>) = r.zipWithNext().sumOf { (a, b) -> d[a][b] }
    var improved = true
    while (improved) {
        improved = false
        for (i in 1 until n - 1) for (j in i + 1 until n) {
            val cand = route.take(i) + route.subList(i, j + 1).reversed() + route.drop(j + 1)
            if (cost(cand) < cost(route) - 1e-9) { route = cand.toMutableList(); improved = true }
        }
    }
    return route
}

fun openInMaps(ctx: Context, ordered: List<Place>) {
    val origin = ordered.first()
    val dest = ordered.last()
    val wps = ordered.drop(1).dropLast(1).joinToString("|") { "${it.lat},${it.lon}" }
    var url = "https://www.google.com/maps/dir/?api=1&origin=${origin.lat},${origin.lon}" +
        "&destination=${dest.lat},${dest.lon}&travelmode=driving"
    if (wps.isNotEmpty()) url += "&waypoints=" + Uri.encode(wps)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    try {
        ctx.startActivity(Intent(intent).setPackage("com.google.android.apps.maps"))
    } catch (e: ActivityNotFoundException) {
        ctx.startActivity(intent)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var start by remember { mutableStateOf<Place?>(null) }
    val stops = remember { mutableStateListOf<Place>() }
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf(listOf<Place>()) }
    var ordered by remember { mutableStateOf(listOf<Place>()) }
    var legs by remember { mutableStateOf(listOf<Double>()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Text("Route Optimizer", style = MaterialTheme.typography.headlineSmall) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search a place") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(enabled = query.isNotBlank() && !busy, onClick = {
                    scope.launch {
                        busy = true; msg = ""
                        try {
                            suggestions = withContext(Dispatchers.IO) { Api.search(query) }
                            if (suggestions.isEmpty()) msg = "No results"
                        } catch (e: Exception) { msg = "Search failed: ${e.message}" }
                        busy = false
                    }
                }) { Text("Search") }
            }
        }
        items(suggestions) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(p.name)
                    Row {
                        TextButton(onClick = { start = p; suggestions = emptyList(); query = ""; ordered = emptyList() }) { Text("Set as start") }
                        TextButton(onClick = {
                            if (stops.size >= 10) msg = "Max 10 stops (Google Maps limit)"
                            else { stops.add(p); suggestions = emptyList(); query = ""; ordered = emptyList() }
                        }) { Text("Add as stop") }
                    }
                }
            }
        }
        if (msg.isNotEmpty()) item { Text(msg, color = MaterialTheme.colorScheme.error) }
        item { Text("Start", style = MaterialTheme.typography.titleMedium) }
        item { Text(start?.name ?: "Not set") }
        item { Text("Stops (${stops.size}/10)", style = MaterialTheme.typography.titleMedium) }
        items(stops.toList()) { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.weight(1f))
                TextButton(onClick = { stops.remove(p); ordered = emptyList() }) { Text("Remove") }
            }
        }
        item {
            Button(
                enabled = start != null && stops.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true; msg = ""
                        val pts = listOf(start!!) + stops.toList()
                        val m = withContext(Dispatchers.IO) {
                            try { Api.matrix(pts) }
                            catch (e: Exception) { msg = "Road data unavailable, using straight-line estimates"; Api.haversineMatrix(pts) }
                        }
                        val order = withContext(Dispatchers.Default) { optimize(m) }
                        ordered = order.map { pts[it] }
                        legs = order.zipWithNext { a, b -> m[a][b] }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Working..." else "Optimize route") }
        }
        if (ordered.isNotEmpty()) {
            item { Text("Best order", style = MaterialTheme.typography.titleMedium) }
            items(ordered.indices.toList()) { i ->
                val label = if (i == 0) "Start: ${ordered[i].name}"
                else "$i. ${ordered[i].name}  (+${(legs[i - 1] / 60).roundToInt()} min)"
                Text(label)
            }
            item {
                Text("Total: about ${(legs.sum() / 60).roundToInt()} min driving")
            }
            item {
                Button(modifier = Modifier.fillMaxWidth(), onClick = { openInMaps(ctx, ordered) }) {
                    Text("Open in Google Maps")
                }
            }
        }
    }
}
