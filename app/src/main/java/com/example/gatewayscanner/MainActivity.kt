package com.example.gatewayscanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

data class PortResult(
    val port: Int,
    val serviceName: String,
    val isOpen: Boolean,
    val details: String = ""
)

val COMMON_RTSP_PATHS = listOf(
    "/",
    "/live",
    "/live.sdp",
    "/live/ch0",
    "/live/ch1",
    "/live/main",
    "/live/sub",
    "/stream",
    "/stream0",
    "/stream1",
    "/stream2",
    "/h264",
    "/h264Preview_01_main",
    "/h264Preview_01_sub",
    "/ch0_0.h264",
    "/ch0_1.h264",
    "/videoMain",
    "/videoSub",
    "/cam/realmonitor?channel=1&subtype=0",
    "/cam/realmonitor?channel=1&subtype=1",
    "/Streaming/Channels/101",
    "/Streaming/Channels/102",
    "/onvif1",
    "/onvif2",
    "/11",
    "/12",
    "/user=admin&password=&channel=1&stream=0.sdp?",
    "/axis-media/media.amp",
    "/mpeg4",
    "/media/video1",
    "/rtsp_tunnel"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ScannerScreen()
                }
            }
        }
    }
}

@Composable
fun ScannerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var gatewayIp by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<PortResult>>(emptyList()) }
    var rtspLog by remember { mutableStateOf("Ожидание сканирования...") }

    val targetPorts = listOf(
        80 to "HTTP",
        443 to "HTTPS",
        22 to "SSH",
        23 to "Telnet",
        53 to "DNS",
        5353 to "mDNS",
        554 to "RTSP"
    )

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text(
            text = "Сканер шлюза и RTSP",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "IP Шлюза: ${gatewayIp ?: "Не определён"}",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                scope.launch {
                    isScanning = true
                    results = emptyList()
                    rtspLog = "Поиск шлюза..."

                    val ip = getGatewayIp(context)
                    gatewayIp = ip

                    if (ip != null) {
                        rtspLog = "Проверка портов на $ip..."
                        val scanJobs = targetPorts.map { (port, name) ->
                            async(Dispatchers.IO) {
                                val open = isPortOpen(ip, port, timeoutMs = 1500)
                                var info = if (open) "ОТКРЫТ" else "Закрыт"

                                if (port == 554 && open) {
                                    val rtspResponse = probeRtsp(ip, 554)
                                    withContext(Dispatchers.Main) {
                                        rtspLog = rtspResponse
                                    }
                                    info = "ОТКРЫТ (RTSP активен)"
                                } else if (port == 554 && !open) {
                                    withContext(Dispatchers.Main) {
                                        rtspLog = "Порт 554 закрыт. Подключение RTSP невозможно."
                                    }
                                }
                                PortResult(port, name, open, info)
                            }
                        }
                        results = scanJobs.awaitAll()
                    } else {
                        rtspLog = "Ошибка: подключитесь к Wi-Fi сети."
                    }
                    isScanning = false
                }
            },
            enabled = !isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isScanning) "Сканирование..." else "Найти шлюз и проверить")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Статус RTSP (554):", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(rtspLog, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        val isPort554Open = results.find { it.port == 554 }?.isOpen == true
        RtspStreamFeature(gatewayIp = gatewayIp, isPort554Open = isPort554Open)

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${item.port} (${item.serviceName})", fontWeight = FontWeight.Medium)
                        Text(
                            text = item.details,
                            color = if (item.isOpen) Color(0xFF2E7D32) else Color.Gray,
                            fontWeight = if (item.isOpen) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RtspStreamFeature(gatewayIp: String?, isPort554Open: Boolean) {
    val scope = rememberCoroutineScope()
    var isCheckingStream by remember { mutableStateOf(false) }
    var activeRtspUrl by remember { mutableStateOf<String?>(null) }
    var showManualDialog by remember { mutableStateOf(false) }
    var detectedPath by remember { mutableStateOf("/live") }
    var statusMessage by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = {
                if (gatewayIp == null) return@Button
                scope.launch {
                    isCheckingStream = true
                    statusMessage = "Проверка стандартных RTSP-путей на порту 554..."
                    val result = findPublicRtspStream(gatewayIp, 554)
                    isCheckingStream = false

                    when (result) {
                        is StreamCheckResult.Success -> {
                            statusMessage = "Поток найден: ${result.url}"
                            activeRtspUrl = result.url
                        }
                        is StreamCheckResult.AuthRequired -> {
                            detectedPath = result.foundPath
                            statusMessage = "Путь ${result.foundPath} найден, но требует логин и пароль."
                            showManualDialog = true
                        }
                        is StreamCheckResult.NotFound -> {
                            detectedPath = "/live"
                            statusMessage = "Стандартные пути не подошли. Укажите параметры вручную."
                            showManualDialog = true
                        }
                    }
                }
            },
            enabled = gatewayIp != null && isPort554Open && !isCheckingStream,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isCheckingStream) "Поиск потока..." else "Посмотреть видео поток")
        }

        if (statusMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = statusMessage, style = MaterialTheme.typography.bodySmall)
        }

        activeRtspUrl?.let { url ->
            Spacer(modifier = Modifier.height(8.dp))
            RtspVideoPlayer(
                rtspUrl = url,
                onError = { errorMsg ->
                    statusMessage = "Ошибка воспроизведения: $errorMsg"
                    activeRtspUrl = null
                    showManualDialog = true
                }
            )
        }
    }

    if (showManualDialog && gatewayIp != null) {
        ManualRtspDialog(
            defaultIp = gatewayIp,
            initialPath = detectedPath,
            onDismiss = { showManualDialog = false },
            onConnect = { finalUrl ->
                showManualDialog = false
                statusMessage = "Подключение к $finalUrl..."
activeRtspUrl = finalUrl
}
)
}
}
sealed class StreamCheckResult {
data class Success(val url: String) : StreamCheckResult()
data class AuthRequired(val foundPath: String) : StreamCheckResult()
object NotFound : StreamCheckResult()
}
suspend fun findPublicRtspStream(ip: String, port: Int = 554): StreamCheckResult = withContext(Dispatchers.IO) {
var authPath: String? = null
for (chunk in COMMON_RTSP_PATHS.chunked(5)) {
val jobs = chunk.map { path ->
async {
val url = "rtsp://$ip:$port$path"
val socket = Socket()
try {
socket.connect(InetSocketAddress(ip, port), 1200)
socket.soTimeout = 1200
val out = PrintWriter(socket.getOutputStream(), true)
val input = BufferedReader(InputStreamReader(socket.getInputStream()))
val request = "DESCRIBE $url RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\n\r\n"
out.print(request)
out.flush()
val statusLine = input.readLine() ?: ""
when {
statusLine.contains("200 OK") -> "SUCCESS:$url"
statusLine.contains("401") -> "AUTH:$path"
else -> null
}
} catch (: Exception) {
null
} finally {
try { socket.close() } catch (: Exception) { }
}
}
}
val chunkResults = jobs.awaitAll().filterNotNull()
val success = chunkResults.firstOrNull { it.startsWith("SUCCESS:") }
if (success != null) {
return@withContext StreamCheckResult.Success(success.removePrefix("SUCCESS:"))
}
val auth = chunkResults.firstOrNull { it.startsWith("AUTH:") }
if (auth != null && authPath == null) {
authPath = auth.removePrefix("AUTH:")
}
}
if (authPath != null) {
StreamCheckResult.AuthRequired(authPath!!)
} else {
StreamCheckResult.NotFound
}
}
@Composable
fun ManualRtspDialog(
defaultIp: String,
initialPath: String,
onDismiss: () -> Unit,
onConnect: (String) -> Unit
) {
var path by remember { mutableStateOf(initialPath) }
var login by remember { mutableStateOf("") }
var password by remember { mutableStateOf("") }
AlertDialog(
onDismissRequest = onDismiss,
title = { Text("Параметры RTSP-потока") },
text = {
Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("Укажите путь и данные для входа на $defaultIp:554:")
OutlinedTextField(
value = path,
onValueChange = { path = it },
label = { Text("Путь (например, /live)") },
singleLine = true
)
OutlinedTextField(
value = login,
onValueChange = { login = it },
label = { Text("Логин") },
singleLine = true
)
OutlinedTextField(
value = password,
onValueChange = { password = it },
label = { Text("Пароль") },
visualTransformation = PasswordVisualTransformation(),
singleLine = true
)
}
},
confirmButton = {
Button(onClick = {
val cleanPath = if (path.startsWith("/")) path else "/$path"
val authPart = if (login.isNotBlank()) {
if (password.isNotBlank()) "$login:$password@" else "$login@"
} else ""
onConnect("rtsp://$authPart$defaultIp:554$cleanPath")
}) {
Text("Подключить")
}
},
dismissButton = {
TextButton(onClick = onDismiss) { Text("Отмена") }
}
)
}
@OptIn(UnstableApi::class)
@Composable
fun RtspVideoPlayer(rtspUrl: String, onError: (String) -> Unit) {
val context = LocalContext.current
val exoPlayer = remember(rtspUrl) {
ExoPlayer.Builder(context).build().apply {
val mediaSource = RtspMediaSource.Factory()
.setForceUseRtpTcp(true)
.createMediaSource(MediaItem.fromUri(Uri.parse(rtspUrl)))
setMediaSource(mediaSource)
addListener(object : Player.Listener {
override fun onPlayerError(error: PlaybackException) {
onError(error.message ?: "Не удалось открыть поток")
}
})
prepare()
playWhenReady = true
}
}
DisposableEffect(exoPlayer) {
onDispose { exoPlayer.release() }
}
AndroidView(
factory = { ctx ->
PlayerView(ctx).apply {
player = exoPlayer
useController = true
}
},
modifier = Modifier.fillMaxWidth().height(220.dp)
)
}
fun getGatewayIp(context: Context): String? {
val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
val activeNetwork = cm.activeNetwork ?: return null
val linkProperties: LinkProperties = cm.getLinkProperties(activeNetwork) ?: return null
for (route in linkProperties.routes) {
if (route.isDefaultRoute && route.gateway is Inet4Address) {
return route.gateway?.hostAddress
}
}
return null
}
fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
val socket = Socket()
return try {
socket.connect(InetSocketAddress(ip, port), timeoutMs)
true
} catch (e: Exception) {
false
} finally {
try { socket.close() } catch (_: Exception) { }
}
}
fun probeRtsp(ip: String, port: Int): String {
val socket = Socket()
return try {
socket.connect(InetSocketAddress(ip, port), 2000)
socket.soTimeout = 2000
val out = PrintWriter(socket.getOutputStream(), true)
val input = BufferedReader(InputStreamReader(socket.getInputStream()))
val request = "OPTIONS rtsp://$ip:$port/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: AndroidScanner\r\n\r\n"
out.print(request)
out.flush()
val responseLines = mutableListOf()
var line: String?
while (input.readLine().also { line = it } != null) {
if (line.isNullOrEmpty()) break
responseLines.add(line!!)
}
if (responseLines.isNotEmpty()) {
"Ответ сервера:\n" + responseLines.joinToString("\n")
} else {
"Соединение установлено, но сервер не вернул RTSP-заголовки."
}
} catch (e: Exception) {
"Ошибка при обмене RTSP: ${e.message}"
} finally {
try { socket.close() } catch (_: Exception) { }
}
}
