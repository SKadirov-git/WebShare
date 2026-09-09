package com.example.webshare

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

class MainActivity : Activity() {

    private val sharedFiles = mutableListOf<SharedFile>()
    private var serverSocket: ServerSocket? = null
    private var isServerRunning = false

    private lateinit var tvIpAddress: TextView
    private lateinit var tvFileList: TextView

    data class SharedFile(val name: String, val uri: Uri, val size: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val btnPickFile = Button(this).apply {
            text = "1. Select Files / Games / Music"
            setOnClickListener { openFilePicker() }
        }

        tvFileList = TextView(this).apply {
            text = "Shared Files: None"
            textSize = 14f
            setPadding(0, 20, 0, 20)
        }

        val btnStartServer = Button(this).apply {
            text = "2. Start Web Server"
            setOnClickListener { startWebServer() }
        }

        tvIpAddress = TextView(this).apply {
            text = "IP Address: Server Stopped"
            textSize = 18f
            setPadding(0, 30, 0, 0)
        }

        layout.addView(btnPickFile)
        layout.addView(tvFileList)
        layout.addView(btnStartServer)
        layout.addView(tvIpAddress)
        setContentView(layout)
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, 200)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == RESULT_OK) {
            data?.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    addFileToList(clipData.getItemAt(i).uri)
                }
            } ?: data?.data?.let { uri ->
                addFileToList(uri)
            }
            updateUIFileList()
        }
    }

    private fun addFileToList(uri: Uri) {
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
                size = cursor.getLong(sizeIndex)
            }
        }
        sharedFiles.add(SharedFile(name, uri, size))
    }

    private fun updateUIFileList() {
        val names = sharedFiles.joinToString("\n") { "• ${it.name}" }
        tvFileList.text = if (names.isEmpty()) "Shared Files: None" else "Shared Files:\n$names"
    }

    private fun startWebServer() {
        if (sharedFiles.isEmpty()) {
            Toast.makeText(this, "Please select at least one file first!", Toast.LENGTH_SHORT).show()
            return
        }

        if (isServerRunning) return

        val ip = getHotspotIpAddress()
        if (ip == null) {
            Toast.makeText(this, "Please turn on your Wi-Fi Hotspot first!", Toast.LENGTH_LONG).show()
            return
        }

        isServerRunning = true
        val port = 8080
        tvIpAddress.text = "Tell friends to enter in browser:\n\nhttp://$ip:$port"

        Thread {
            try {
                serverSocket = ServerSocket(port)
                while (isServerRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    handleClientRequest(clientSocket)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun handleClientRequest(socket: Socket) {
        Thread {
            try {
                val input = socket.getInputStream().bufferedReader()
                val output = socket.getOutputStream()
                val requestLine = input.readLine() ?: return@Thread

                if (requestLine.startsWith("GET /download/")) {
                    val indexStr = requestLine.substringAfter("GET /download/").substringBefore(" ")
                    val index = indexStr.toIntOrNull()
                    if (index != null && index in sharedFiles.indices) {
                        serveFileDownload(sharedFiles[index], output)
                    }
                } else {
                    serveHtmlPage(output)
                }
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun serveHtmlPage(output: OutputStream) {
        val htmlBuilder = StringBuilder()
        htmlBuilder.append("<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'>")
        htmlBuilder.append("<title>Hotspot Share</title><style>")
        htmlBuilder.append("body { font-family: sans-serif; padding: 20px; background: #121212; color: #fff; }")
        htmlBuilder.append(".card { background: #1e1e1e; padding: 15px; margin-bottom: 10px; border-radius: 8px; display: flex; justify-content: space-between; align-items: center; }")
        htmlBuilder.append("a { background: #00e676; color: #000; padding: 10px 15px; text-decoration: none; border-radius: 5px; font-weight: bold; }")
        htmlBuilder.append("</style></head><body><h2>Shared Files & Games</h2>")

        sharedFiles.forEachIndexed { index, file ->
            htmlBuilder.append("<div class='card'>")
            htmlBuilder.append("<div><strong>${file.name}</strong></div>")
            htmlBuilder.append("<a href='/download/$index'>Download</a>")
            htmlBuilder.append("</div>")
        }

        htmlBuilder.append("</body></html>")

        val bytes = htmlBuilder.toString().toByteArray()
        output.write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun serveFileDownload(file: SharedFile, output: OutputStream) {
        val inputStream: InputStream = contentResolver.openInputStream(file.uri) ?: return
        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"${file.name}\"\r\nContent-Length: ${file.size}\r\n\r\n".toByteArray())
        
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
        inputStream.close()
        output.flush()
    }

    private fun getHotspotIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.name.contains("ap") || networkInterface.name.contains("wlan")) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is InetAddress) {
                            val ip = address.hostAddress
                            if (ip != null && !ip.contains(":")) {
                                return ip
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "192.168.43.1"
    }

    override fun onDestroy() {
        super.onDestroy()
        isServerRunning = false
        serverSocket?.close()
    }
}
