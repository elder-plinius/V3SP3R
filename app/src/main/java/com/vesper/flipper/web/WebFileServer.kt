package com.vesper.flipper.web

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.vesper.flipper.ble.FlipperFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded file manager for the Flipper file system.
 *
 * Security model:
 * - Every request must present the per-session access token, either as the
 *   "token" query parameter or as the "X-Vesper-Token" header. Requests without
 *   it receive 401 before any file system call happens.
 * - The token is generated with SecureRandom on every [start] and is exposed
 *   through [accessToken] / [accessUrl] so the app can show a scannable link.
 * - Only paths below [rootPath] are addressable. ".." segments are resolved
 *   locally and rejected when they would leave that root.
 * - Connections have a 10 second socket timeout, are capped at MAX_CONNECTIONS
 *   at a time, and request bodies are capped at MAX_UPLOAD_BYTES.
 * - Every response carries X-Content-Type-Options: nosniff and
 *   Cache-Control: no-store.
 */
class WebFileServer(
    private val context: Context,
    private val flipperFileSystem: FlipperFileSystem,
    private val port: Int = 8888,
    rootPath: String = DEFAULT_ROOT
) {
    private val rootPath: String = normalizeRoot(rootPath)

    private val connectionSemaphore = Semaphore(MAX_CONNECTIONS)
    private val activeSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var generation: Long = 0L

    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** Base URL without credentials, kept for callers that only need the host. */
    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    /** URL that already contains the access token. Show this to the user. */
    private val _accessUrl = MutableStateFlow<String?>(null)
    val accessUrl: StateFlow<String?> = _accessUrl.asStateFlow()

    /** Per-session access token. Null while the server is stopped. */
    private val _accessToken = MutableStateFlow<String?>(null)
    val accessToken: StateFlow<String?> = _accessToken.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    @Synchronized
    fun start() {
        if (_isRunning.value) return

        generation += 1
        val myGeneration = generation
        val token = generateToken()

        serverJob = scope.launch {
            var boundSocket: ServerSocket? = null
            try {
                coroutineScope {
                    val socket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(port))
                    }
                    boundSocket = socket
                    serverSocket = socket

                    val host = getLocalIpAddress()
                    val baseUrl = "http://$host:$port"
                    val published = synchronized(this@WebFileServer) {
                        if (generation != myGeneration) {
                            false
                        } else {
                            _serverUrl.value = baseUrl
                            _accessToken.value = token
                            _accessUrl.value = "$baseUrl/?$TOKEN_QUERY_PARAM=$token"
                            _isRunning.value = true
                            true
                        }
                    }
                    if (!published) {
                        runCatching { socket.close() }
                        return@coroutineScope
                    }

                    while (isActive) {
                        val client = try {
                            socket.accept()
                        } catch (e: SocketException) {
                            break
                        }

                        if (!connectionSemaphore.tryAcquire()) {
                            rejectBusy(client)
                            continue
                        }

                        launch {
                            try {
                                handleConnection(client, token)
                            } finally {
                                connectionSemaphore.release()
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Web file server stopped: " + e.javaClass.simpleName)
            } finally {
                runCatching { boundSocket?.close() }
                if (generation == myGeneration) {
                    serverSocket = null
                    _isRunning.value = false
                    _serverUrl.value = null
                    _accessUrl.value = null
                    _accessToken.value = null
                    _connectionCount.value = 0
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        generation += 1
        serverJob?.cancel()
        serverJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        activeSockets.toList().forEach { socket -> runCatching { socket.close() } }
        activeSockets.clear()
        _isRunning.value = false
        _serverUrl.value = null
        _accessUrl.value = null
        _accessToken.value = null
        _connectionCount.value = 0
    }

    private suspend fun handleConnection(socket: Socket, expectedToken: String) {
        activeSockets.add(socket)
        _connectionCount.update { it + 1 }

        try {
            socket.use { sock ->
                sock.soTimeout = SOCKET_TIMEOUT_MS
                val input = BufferedInputStream(sock.getInputStream())
                val output = sock.getOutputStream()

                try {
                    val requestLine = readHttpLine(input) ?: return
                    val parts = requestLine.split(' ')
                    if (parts.size < 3 || !parts[2].startsWith("HTTP/1.")) {
                        writeJsonError(output, "400 Bad Request", "Malformed request")
                        return
                    }

                    val method = parts[0].uppercase(Locale.US)
                    val target = parts[1]
                    val path = decodePath(target.substringBefore('?')) ?: run {
                        writeJsonError(output, "400 Bad Request", "Malformed path")
                        return
                    }
                    val headers = readHeaders(input) ?: run {
                        writeJsonError(output, "431 Request Header Fields Too Large", "Headers are too large")
                        return
                    }

                    if (!isAuthorized(target, headers, expectedToken)) {
                        serveUnauthorized(output, path.startsWith("/api/"))
                        return
                    }

                    when {
                        path == "/" || path == "/index.html" -> serveIndexPage(output, expectedToken)
                        path == "/api/list" && method == "GET" -> handleListApi(target, output)
                        path == "/api/read" && method == "GET" -> handleReadApi(target, output)
                        path == "/api/download" && method == "GET" -> handleDownloadApi(target, output)
                        path == "/api/delete" && method == "POST" -> handleDeleteApi(target, output)
                        path == "/api/mkdir" && method == "POST" -> handleMkdirApi(target, output)
                        path == "/api/upload" && method == "POST" -> handleUploadApi(target, headers, input, output)
                        path.startsWith("/api/") -> writeJsonError(output, "404 Not Found", "Unknown endpoint")
                        else -> serve404(output)
                    }
                } catch (e: SocketTimeoutException) {
                    Log.w(TAG, "Closing idle web connection")
                } catch (e: PayloadTooLargeException) {
                    runCatching { writeJsonError(output, "413 Payload Too Large", uploadLimitMessage()) }
                } catch (e: BadRequestException) {
                    runCatching { writeJsonError(output, "400 Bad Request", e.message ?: "Bad request") }
                } catch (e: Exception) {
                    Log.w(TAG, "Web request failed: " + e.javaClass.simpleName)
                    runCatching { writeJsonError(output, "500 Internal Server Error", "Request failed") }
                }
            }
        } finally {
            runCatching { socket.close() }
            activeSockets.remove(socket)
            _connectionCount.update { if (it > 0) it - 1 else 0 }
        }
    }

    private fun isAuthorized(target: String, headers: Map<String, String>, expectedToken: String): Boolean {
        val supplied = headers[TOKEN_HEADER.lowercase(Locale.US)]
            ?: extractQueryParam(target, TOKEN_QUERY_PARAM)
            ?: return false
        return MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            expectedToken.toByteArray(Charsets.UTF_8)
        )
    }

    private fun readHeaders(input: InputStream): Map<String, String>? {
        val headers = mutableMapOf<String, String>()
        var totalBytes = 0

        while (true) {
            val line = readHttpLine(input) ?: return null
            totalBytes += line.toByteArray(Charsets.UTF_8).size + 2
            if (totalBytes > MAX_HEADER_BYTES) return null
            if (line.isEmpty()) break

            val colonIndex = line.indexOf(':')
            if (colonIndex <= 0) continue
            val name = line.substring(0, colonIndex).trim().lowercase(Locale.US)
            val value = line.substring(colonIndex + 1).trim()
            if (name.isEmpty()) continue
            headers[name] = if (headers.containsKey(name)) headers[name] + ", " + value else value
        }

        return headers
    }

    private fun readHttpLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) {
                return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8.name())
            }
            if (byte == '\n'.code) {
                return buffer.toString(Charsets.UTF_8.name()).removeSuffix("\r")
            }
            if (buffer.size() >= MAX_LINE_BYTES) {
                throw BadRequestException("Header line is too long")
            }
            buffer.write(byte)
        }
    }

    private suspend fun handleListApi(target: String, output: OutputStream) {
        val requestedPath = extractQueryParam(target, "path")
        val flipperPath = if (requestedPath == null) rootPath else resolveFlipperPath(requestedPath)
        if (flipperPath == null) {
            writeJsonError(output, "403 Forbidden", "Path is outside the shared folder")
            return
        }

        try {
            val result = flipperFileSystem.listDirectory(flipperPath)
            if (result.isSuccess) {
                val files = result.getOrNull().orEmpty()
                val json = buildString {
                    append("{\"files\":[")
                    files.forEachIndexed { index, entry ->
                        if (index > 0) append(',')
                        append("{\"name\":\"").append(jsonEscape(entry.name))
                        append("\",\"isDir\":").append(entry.isDirectory)
                        append(",\"size\":").append(entry.size)
                        append('}')
                    }
                    append("]}")
                }
                writeJson(output, "200 OK", json)
            } else {
                writeJsonError(output, "502 Bad Gateway", "Could not list that folder")
            }
        } catch (e: Exception) {
            writeJsonError(output, "500 Internal Server Error", e.message ?: "Could not list that folder")
        }
    }

    private suspend fun handleReadApi(target: String, output: OutputStream) {
        val flipperPath = resolveRequestedPath(target) ?: run {
            writeJsonError(output, "403 Forbidden", "Path is outside the shared folder")
            return
        }

        try {
            val result = flipperFileSystem.readFile(flipperPath)
            if (result.isSuccess) {
                val content = result.getOrNull().orEmpty()
                writeJson(output, "200 OK", "{\"content\":\"" + jsonEscape(content) + "\"}")
            } else {
                writeJsonError(output, "502 Bad Gateway", "Could not read that file")
            }
        } catch (e: Exception) {
            writeJsonError(output, "500 Internal Server Error", e.message ?: "Could not read that file")
        }
    }

    private suspend fun handleDownloadApi(target: String, output: OutputStream) {
        val flipperPath = resolveRequestedPath(target) ?: run {
            writeJsonError(output, "403 Forbidden", "Path is outside the shared folder")
            return
        }
        val filename = sanitizeFilename(flipperPath.substringAfterLast('/'))

        try {
            val result = flipperFileSystem.readFileBytes(flipperPath)
            val bytes = result.getOrNull()
            if (result.isFailure || bytes == null) {
                writeJsonError(output, "502 Bad Gateway", "Could not download that file")
                return
            }

            writeResponseHeaders(
                output = output,
                status = "200 OK",
                contentType = "application/octet-stream",
                contentLength = bytes.size.toLong(),
                extraHeaders = listOf("Content-Disposition" to "attachment; filename=\"$filename\"")
            )
            output.write(bytes)
            output.flush()
        } catch (e: Exception) {
            writeJsonError(output, "500 Internal Server Error", e.message ?: "Could not download that file")
        }
    }

    private suspend fun handleDeleteApi(target: String, output: OutputStream) {
        val flipperPath = resolveRequestedPath(target) ?: run {
            writeJsonError(output, "403 Forbidden", "Path is outside the shared folder")
            return
        }
        if (flipperPath == rootPath) {
            writeJsonError(output, "403 Forbidden", "The shared folder itself cannot be deleted")
            return
        }

        try {
            val result = flipperFileSystem.deleteFile(flipperPath)
            if (result.isSuccess) {
                writeJson(output, "200 OK", "{\"success\":true}")
            } else {
                writeJsonError(output, "502 Bad Gateway", "Could not delete that item")
            }
        } catch (e: Exception) {
            writeJsonError(output, "500 Internal Server Error", e.message ?: "Could not delete that item")
        }
    }

    private suspend fun handleMkdirApi(target: String, output: OutputStream) {
        val flipperPath = resolveRequestedPath(target) ?: run {
            writeJsonError(output, "403 Forbidden", "Path is outside the shared folder")
            return
        }
        if (flipperPath == rootPath) {
            writeJsonError(output, "400 Bad Request", "Choose a folder name")
            return
        }

        try {
            val result = flipperFileSystem.createDirectory(flipperPath)
            if (result.isSuccess) {
                writeJson(output, "200 OK", "{\"success\":true}")
            } else {
                writeJsonError(output, "502 Bad Gateway", "Could not create that folder")
            }
        } catch (e: Exception) {
            writeJsonError(output, "500 Internal Server Error", e.message ?: "Could not create that folder")
        }
    }

    private suspend fun handleUploadApi(
        target: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream
    ) {
        val directory = resolveRequestedPath(target) ?: run {
            writeJsonError(output, "403 Forbidden", "Path is outside the shared folder")
            return
        }

        val contentType = headers["content-type"].orEmpty()
        if (!contentType.startsWith("multipart/form-data", ignoreCase = true)) {
            writeJsonError(output, "415 Unsupported Media Type", "Expected a multipart upload")
            return
        }

        val boundary = contentType.substringAfter("boundary=", "")
            .trim()
            .trim('"')
        if (boundary.isBlank() || boundary.length > MAX_BOUNDARY_LENGTH) {
            writeJsonError(output, "400 Bad Request", "Missing multipart boundary")
            return
        }

        if (headers.containsKey("transfer-encoding")) {
            writeJsonError(output, "411 Length Required", "Chunked uploads are not supported")
            return
        }

        val contentLength = headers["content-length"]?.toLongOrNull() ?: run {
            writeJsonError(output, "411 Length Required", "Missing content length")
            return
        }
        if (contentLength <= 0) {
            writeJsonError(output, "400 Bad Request", "Empty upload")
            return
        }
        if (contentLength > MAX_UPLOAD_BYTES) {
            writeJsonError(output, "413 Payload Too Large", uploadLimitMessage())
            return
        }

        try {
            val body = readBody(input, contentLength)
            val boundaryBytes = "--$boundary".toByteArray(Charsets.UTF_8)
            val parts = splitByteArray(body, boundaryBytes)

            var savedCount = 0
            var inspectedParts = 0
            var totalBytes = 0L

            for (part in parts) {
                if (inspectedParts >= MAX_UPLOAD_PARTS) break
                inspectedParts += 1

                val trimmed = trimLeadingCrlf(part)
                if (trimmed.isEmpty()) continue
                if (trimmed.contentEquals("--".toByteArray(Charsets.UTF_8))) continue

                val headerEnd = indexOfBytes(trimmed, CRLF_CRLF, 0)
                if (headerEnd <= 0) continue

                val headerText = trimmed.copyOfRange(0, headerEnd).toString(Charsets.UTF_8)
                val bodyBytes = trimmed.copyOfRange(headerEnd + CRLF_CRLF.size, trimmed.size)
                val fileBytes = trimTrailingCrlf(bodyBytes)

                if (totalBytes + fileBytes.size > MAX_UPLOAD_BYTES) {
                    writeJsonError(output, "413 Payload Too Large", uploadLimitMessage())
                    return
                }

                val disposition = headerText.lines().firstOrNull {
                    it.startsWith("Content-Disposition", ignoreCase = true)
                } ?: continue

                val filename = Regex("filename=\"([^\"]*)\"").find(disposition)?.groupValues?.get(1)
                if (filename.isNullOrBlank()) continue

                val safeName = sanitizeFilename(filename)
                if (safeName.isBlank()) continue

                val targetPath = resolveFlipperPath(directory.trimEnd('/') + "/" + safeName)
                if (targetPath == null || targetPath == rootPath) continue

                val writeResult = flipperFileSystem.writeFileBytes(targetPath, fileBytes)
                if (writeResult.isFailure) {
                    writeJsonError(output, "502 Bad Gateway", "Could not save " + jsonEscape(safeName))
                    return
                }

                savedCount += 1
                totalBytes += fileBytes.size
            }

            if (savedCount > 0) {
                writeJson(
                    output,
                    "200 OK",
                    "{\"success\":true,\"files\":$savedCount,\"bytes\":$totalBytes}"
                )
            } else {
                writeJsonError(output, "400 Bad Request", "No files found in the upload")
            }
        } catch (e: PayloadTooLargeException) {
            writeJsonError(output, "413 Payload Too Large", uploadLimitMessage())
        }
    }

    private fun resolveRequestedPath(target: String): String? {
        val rawPath = extractQueryParam(target, "path") ?: return null
        return resolveFlipperPath(rawPath)
    }

    /**
     * Resolves "." and ".." without touching the file system, then proves the
     * result is [rootPath] or a descendant of it. Anything else returns null.
     */
    private fun resolveFlipperPath(rawPath: String): String? {
        if (rawPath.isBlank() || rawPath.contains('\u0000')) return null

        val segments = ArrayDeque<String>()
        for (segment in rawPath.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (segments.isEmpty()) return null
                    segments.removeLast()
                }
                else -> segments.addLast(segment)
            }
        }

        val resolved = "/" + segments.joinToString("/")
        return if (resolved == rootPath || resolved.startsWith("$rootPath/")) resolved else null
    }

    private fun readBody(input: InputStream, length: Long): ByteArray {
        if (length > MAX_UPLOAD_BYTES) throw PayloadTooLargeException()
        val output = ByteArrayOutputStream(length.toInt())
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var remaining = length

        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw BadRequestException("Unexpected end of request body")
            output.write(buffer, 0, read)
            remaining -= read
        }

        return output.toByteArray()
    }

    private fun serveIndexPage(output: OutputStream, token: String) {
        val escapedToken = escapeHtml(token)
        val escapedRoot = escapeHtml(rootPath)
        val html = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="vesper-token" content="${escapedToken}">
    <title>Vesper file manager</title>
    <style>
        :root { color-scheme: dark; }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #08070c; color: #f5f2ee; }
        header { padding: 20px; border-bottom: 1px solid #2a2533; }
        header h1 { font-family: Georgia, "Times New Roman", serif; font-size: 1.4rem; font-weight: 600; }
        .status { margin-top: 4px; color: #a49cae; font-size: 0.85rem; }
        nav { padding: 12px 20px; border-bottom: 1px solid #2a2533; font-size: 0.9rem; color: #a49cae; }
        nav a { color: #d4af37; text-decoration: none; }
        nav a:hover { text-decoration: underline; }
        main { padding: 20px; max-width: 960px; margin: 0 auto; }
        #toolbar { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
        button, .upload-btn { min-height: 48px; background: #1c1824; border: 1px solid #3a3348; color: #f5f2ee; padding: 10px 16px; border-radius: 8px; cursor: pointer; font-size: 0.9rem; }
        button:hover, .upload-btn:hover { background: #2a2433; }
        .upload-btn { display: inline-flex; align-items: center; }
        .notice { margin-bottom: 16px; padding: 12px; border-radius: 8px; background: #2a1a22; color: #e8a0ad; }
        .notice[hidden] { display: none; }
        .file-item { display: flex; align-items: center; padding: 10px 12px; background: #121019; border: 1px solid #2a2533; border-radius: 8px; margin-bottom: 8px; gap: 12px; }
        .file-item .icon { color: #a49cae; font-size: 0.7rem; letter-spacing: 0.08em; min-width: 34px; }
        .file-item .name { flex: 1; background: none; border: none; color: #f5f2ee; text-align: left; cursor: pointer; font-size: 0.95rem; padding: 8px 0; }
        .file-item.dir .name { color: #d4af37; }
        .file-item .size { color: #a49cae; font-size: 0.8rem; min-width: 72px; text-align: right; }
        .file-item .actions { display: flex; gap: 4px; }
        .file-item .actions button { min-height: 40px; padding: 6px 10px; font-size: 0.8rem; }
        .empty { color: #a49cae; padding: 32px 0; text-align: center; }
        .modal { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.8); display: flex; align-items: center; justify-content: center; padding: 16px; }
        .modal.hidden { display: none; }
        .modal-content { background: #121019; border: 1px solid #3a3348; padding: 20px; border-radius: 12px; max-width: 720px; width: 100%; max-height: 82vh; overflow: auto; position: relative; }
        .modal-content h2 { font-size: 1.05rem; margin-bottom: 12px; word-break: break-word; }
        .modal-content pre { background: #08070c; padding: 12px; border-radius: 8px; overflow: auto; max-height: 52vh; font-size: 0.8rem; white-space: pre-wrap; word-break: break-word; }
        .modal-content button { margin-top: 12px; }
        .close { position: absolute; top: 12px; right: 12px; min-height: 40px; padding: 6px 10px; }
        @media (max-width: 560px) {
            .file-item { flex-wrap: wrap; }
            .file-item .size { min-width: 0; }
        }
    </style>
</head>
<body>
    <header>
        <h1>Vesper file manager</h1>
        <p class="status">Shared folder: ${escapedRoot}</p>
    </header>

    <nav id="breadcrumb" aria-label="Breadcrumb"></nav>

    <main>
        <p id="notice" class="notice" role="status" hidden></p>
        <div id="toolbar">
            <button type="button" id="btn-up">Up</button>
            <button type="button" id="btn-refresh">Refresh</button>
            <button type="button" id="btn-new-folder">New folder</button>
            <label class="upload-btn">Upload<input type="file" id="upload-input" hidden></label>
        </div>
        <div id="file-list"></div>
    </main>

    <div id="modal" class="modal hidden" role="dialog" aria-modal="true" aria-labelledby="modal-title">
        <div class="modal-content">
            <button type="button" class="close" id="modal-close" aria-label="Close">Close</button>
            <h2 id="modal-title"></h2>
            <pre id="modal-body"></pre>
            <button type="button" id="modal-download">Download</button>
        </div>
    </div>

    <script>
        (function () {
            'use strict';

            var tokenMeta = document.querySelector('meta[name="vesper-token"]');
            var TOKEN = (tokenMeta && tokenMeta.content) ||
                new URLSearchParams(window.location.search).get('token') || '';
            var currentPath = '${escapedRoot}';
            var previewPath = null;

            function notice(message, isError) {
                var el = document.getElementById('notice');
                el.textContent = message || '';
                el.hidden = !message;
                el.style.color = isError ? '#e8a0ad' : '#a49cae';
            }

            function joinPath(base, name) {
                var trimmed = base;
                while (trimmed.length > 0 && trimmed.charAt(trimmed.length - 1) === '/') {
                    trimmed = trimmed.substring(0, trimmed.length - 1);
                }
                return trimmed + '/' + name;
            }

            async function api(url, options) {
                var opts = options || {};
                var headers = opts.headers || {};
                headers['X-Vesper-Token'] = TOKEN;
                opts.headers = headers;

                var response = await fetch(url, opts);
                var text = await response.text();
                var data = {};
                try {
                    data = text ? JSON.parse(text) : {};
                } catch (e) {
                    data = { error: text || 'Request failed' };
                }
                if (!response.ok) {
                    throw new Error(data.error || ('Request failed with status ' + response.status));
                }
                return data;
            }

            function renderBreadcrumb() {
                var nav = document.getElementById('breadcrumb');
                while (nav.firstChild) nav.removeChild(nav.firstChild);

                var parts = currentPath.split('/').filter(function (part) { return part; });
                var path = '';
                parts.forEach(function (part, index) {
                    path += '/' + part;
                    var link = document.createElement('a');
                    link.href = '#';
                    link.textContent = part;
                    var target = path;
                    link.addEventListener('click', function (event) {
                        event.preventDefault();
                        navigate(target);
                    });
                    nav.appendChild(link);
                    if (index < parts.length - 1) {
                        nav.appendChild(document.createTextNode(' / '));
                    }
                });
            }

            function renderFiles(files) {
                var list = document.getElementById('file-list');
                while (list.firstChild) list.removeChild(list.firstChild);

                if (!files || files.length === 0) {
                    var empty = document.createElement('p');
                    empty.className = 'empty';
                    empty.textContent = 'This folder is empty.';
                    list.appendChild(empty);
                    return;
                }

                files.forEach(function (file) {
                    var item = document.createElement('div');
                    item.className = 'file-item' + (file.isDir ? ' dir' : '');

                    var icon = document.createElement('span');
                    icon.className = 'icon';
                    icon.textContent = file.isDir ? 'DIR' : 'FILE';

                    var name = document.createElement('button');
                    name.type = 'button';
                    name.className = 'name';
                    name.textContent = file.name;
                    var itemPath = joinPath(currentPath, file.name);
                    name.addEventListener('click', function () {
                        if (file.isDir) {
                            navigate(itemPath);
                        } else {
                            viewFile(itemPath);
                        }
                    });

                    var size = document.createElement('span');
                    size.className = 'size';
                    size.textContent = file.isDir ? '' : formatSize(file.size);

                    var actions = document.createElement('span');
                    actions.className = 'actions';

                    if (!file.isDir) {
                        var download = document.createElement('button');
                        download.type = 'button';
                        download.textContent = 'Download';
                        download.addEventListener('click', function () { downloadFile(itemPath); });
                        actions.appendChild(download);
                    }

                    var remove = document.createElement('button');
                    remove.type = 'button';
                    remove.textContent = 'Delete';
                    remove.addEventListener('click', function () { deleteItem(itemPath); });
                    actions.appendChild(remove);

                    item.appendChild(icon);
                    item.appendChild(name);
                    item.appendChild(size);
                    item.appendChild(actions);
                    list.appendChild(item);
                });
            }

            function formatSize(bytes) {
                var value = Number(bytes) || 0;
                if (value < 1024) return value + ' B';
                if (value < 1024 * 1024) return (value / 1024).toFixed(1) + ' KB';
                return (value / (1024 * 1024)).toFixed(1) + ' MB';
            }

            async function navigate(path) {
                try {
                    var data = await api('/api/list?path=' + encodeURIComponent(path));
                    currentPath = path;
                    renderBreadcrumb();
                    renderFiles(data.files || []);
                    notice('');
                } catch (e) {
                    notice(e.message, true);
                }
            }

            function goUp() {
                var parts = currentPath.split('/').filter(function (part) { return part; });
                if (parts.length > 1) {
                    parts.pop();
                    navigate('/' + parts.join('/'));
                }
            }

            async function viewFile(path) {
                try {
                    var data = await api('/api/read?path=' + encodeURIComponent(path));
                    previewPath = path;
                    document.getElementById('modal-title').textContent = path.split('/').pop();
                    document.getElementById('modal-body').textContent = data.content || '';
                    document.getElementById('modal').classList.remove('hidden');
                } catch (e) {
                    notice(e.message, true);
                }
            }

            function closeModal() {
                document.getElementById('modal').classList.add('hidden');
                previewPath = null;
            }

            function downloadFile(path) {
                if (!TOKEN) {
                    notice('Reload this page from the link shown in Vesper.', true);
                    return;
                }
                window.location.href = '/api/download?path=' + encodeURIComponent(path) +
                    '&token=' + encodeURIComponent(TOKEN);
            }

            async function deleteItem(path) {
                var name = path.split('/').pop();
                if (!window.confirm('Delete ' + name + '?')) return;
                try {
                    await api('/api/delete?path=' + encodeURIComponent(path), { method: 'POST' });
                    if (previewPath === path) closeModal();
                    await navigate(currentPath);
                } catch (e) {
                    notice(e.message, true);
                }
            }

            async function createFolder() {
                var name = window.prompt('Folder name');
                if (!name) return;
                try {
                    await api('/api/mkdir?path=' + encodeURIComponent(joinPath(currentPath, name)), { method: 'POST' });
                    await navigate(currentPath);
                } catch (e) {
                    notice(e.message, true);
                }
            }

            async function uploadFile(file) {
                if (!file) return;
                var form = new FormData();
                form.append('file', file);
                notice('Uploading ' + file.name);
                try {
                    await api('/api/upload?path=' + encodeURIComponent(currentPath), {
                        method: 'POST',
                        body: form
                    });
                    notice('Uploaded ' + file.name);
                    await navigate(currentPath);
                } catch (e) {
                    notice(e.message, true);
                }
            }

            document.getElementById('btn-up').addEventListener('click', goUp);
            document.getElementById('btn-refresh').addEventListener('click', function () { navigate(currentPath); });
            document.getElementById('btn-new-folder').addEventListener('click', createFolder);
            document.getElementById('modal-close').addEventListener('click', closeModal);
            document.getElementById('modal-download').addEventListener('click', function () {
                if (previewPath) downloadFile(previewPath);
            });
            document.getElementById('upload-input').addEventListener('change', function (event) {
                uploadFile(event.target.files && event.target.files[0]);
                event.target.value = '';
            });

            navigate(currentPath);
        })();
    </script>
</body>
</html>
        """.trimIndent()

        writeHtml(output, "200 OK", html)
    }

    private fun serveUnauthorized(output: OutputStream, isApi: Boolean) {
        if (isApi) {
            writeJsonError(output, "401 Unauthorized", "Missing or invalid access token")
            return
        }

        val html = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Vesper file manager</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #08070c; color: #f5f2ee; padding: 32px; }
        h1 { font-family: Georgia, "Times New Roman", serif; font-size: 1.4rem; }
        p { color: #a49cae; margin-top: 12px; line-height: 1.5; }
    </style>
</head>
<body>
    <h1>Unauthorized</h1>
    <p>Open the link shown in Vesper, or send the X-Vesper-Token header. The token changes every time the server starts.</p>
</body>
</html>
        """.trimIndent()

        writeHtml(output, "401 Unauthorized", html)
    }

    private fun serve404(output: OutputStream) {
        val html = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Vesper file manager</title>
</head>
<body>
    <h1>Not found</h1>
</body>
</html>
        """.trimIndent()

        writeHtml(output, "404 Not Found", html)
    }

    private fun writeJson(output: OutputStream, status: String, json: String) {
        writeResponse(output, status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
    }

    private fun writeJsonError(output: OutputStream, status: String, message: String) {
        writeJson(output, status, "{\"error\":\"" + jsonEscape(message) + "\"}")
    }

    private fun writeHtml(output: OutputStream, status: String, html: String) {
        writeResponse(output, status, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
    }

    private fun writeResponse(
        output: OutputStream,
        status: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: List<Pair<String, String>> = emptyList()
    ) {
        writeResponseHeaders(output, status, contentType, body.size.toLong(), extraHeaders)
        output.write(body)
        output.flush()
    }

    private fun writeResponseHeaders(
        output: OutputStream,
        status: String,
        contentType: String,
        contentLength: Long,
        extraHeaders: List<Pair<String, String>> = emptyList()
    ) {
        val header = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(contentLength).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Frame-Options: DENY\r\n")
            extraHeaders.forEach { (name, value) ->
                append(name).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun rejectBusy(socket: Socket) {
        runCatching {
            socket.soTimeout = 1_000
            writeJsonError(
                socket.getOutputStream(),
                "503 Service Unavailable",
                "Too many active connections"
            )
        }
        runCatching { socket.close() }
    }

    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(): String {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
        if (ipInt != 0) {
            return String.format(
                Locale.US,
                "%d.%d.%d.%d",
                ipInt and 0xff,
                (ipInt shr 8) and 0xff,
                (ipInt shr 16) and 0xff,
                (ipInt shr 24) and 0xff
            )
        }

        val fromInterfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()

        return fromInterfaces?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    }

    private fun splitByteArray(data: ByteArray, delimiter: ByteArray): List<ByteArray> {
        if (delimiter.isEmpty()) return listOf(data)
        val parts = mutableListOf<ByteArray>()
        var start = 0
        var index = indexOfBytes(data, delimiter, start)
        while (index >= 0) {
            parts.add(data.copyOfRange(start, index))
            start = index + delimiter.size
            index = indexOfBytes(data, delimiter, start)
        }
        if (start <= data.size) {
            parts.add(data.copyOfRange(start, data.size))
        }
        return parts
    }

    private fun indexOfBytes(data: ByteArray, pattern: ByteArray, start: Int): Int {
        if (pattern.isEmpty()) return start
        if (data.size < pattern.size) return -1
        var i = start
        while (i <= data.size - pattern.size) {
            var matched = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
            i++
        }
        return -1
    }

    private fun trimLeadingCrlf(data: ByteArray): ByteArray {
        if (data.size >= 2 && data[0] == '\r'.code.toByte() && data[1] == '\n'.code.toByte()) {
            return data.copyOfRange(2, data.size)
        }
        return data
    }

    private fun trimTrailingCrlf(data: ByteArray): ByteArray {
        if (data.size >= 2 &&
            data[data.size - 2] == '\r'.code.toByte() &&
            data[data.size - 1] == '\n'.code.toByte()
        ) {
            return data.copyOfRange(0, data.size - 2)
        }
        return data
    }

    private fun sanitizeFilename(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        return base
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('.')
            .take(MAX_FILENAME_LENGTH)
    }

    private fun jsonEscape(value: String): String {
        return buildString(value.length + 8) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char.code < 0x20) {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
        }
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun decodePath(rawPath: String): String? {
        return runCatching {
            URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
        }.getOrNull()
    }

    private fun extractQueryParam(target: String, param: String): String? {
        val queryStart = target.indexOf('?')
        if (queryStart < 0) return null

        val query = target.substring(queryStart + 1)
        for (pair in query.split('&')) {
            val separator = pair.indexOf('=')
            if (separator <= 0) continue
            val name = pair.substring(0, separator)
            if (name != param) continue
            return runCatching {
                URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
            }.getOrNull()
        }
        return null
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun uploadLimitMessage(): String {
        return "Upload exceeds the " + (MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB limit"
    }

    private fun normalizeRoot(path: String): String {
        val normalized = path.replace('\\', '/').trimEnd('/')
        if (normalized.isEmpty()) return DEFAULT_ROOT
        val withSlash = if (normalized.startsWith('/')) normalized else "/$normalized"
        return if (withSlash == "/") DEFAULT_ROOT else withSlash
    }

    private class PayloadTooLargeException : Exception()

    private class BadRequestException(message: String) : Exception(message)

    companion object {
        private const val TAG = "WebFileServer"

        const val DEFAULT_ROOT = "/ext"

        private const val MAX_CONNECTIONS = 4
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val READ_BUFFER_SIZE = 8 * 1024
        private const val MAX_UPLOAD_BYTES = 8L * 1024 * 1024
        private const val MAX_UPLOAD_PARTS = 16
        private const val MAX_FILENAME_LENGTH = 120
        private const val MAX_BOUNDARY_LENGTH = 70
        private const val TOKEN_BYTES = 32
        private const val TOKEN_QUERY_PARAM = "token"
        private const val TOKEN_HEADER = "X-Vesper-Token"

        private val CRLF_CRLF = "\r\n\r\n".toByteArray(Charsets.UTF_8)
    }
}
