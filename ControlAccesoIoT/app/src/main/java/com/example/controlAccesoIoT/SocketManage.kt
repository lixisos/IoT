package com.example.controlAccesoIoT


import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale

class SocketManager(
    private val dbHelper: DatabaseHelper,
    private val onEstadoConexionCambiado: (Boolean, String) -> Unit,
    private val onMensajeRecibido: (String) -> Unit
) {
    companion object {
        private const val TAG = "CONTROL_ACCESO_IOT"
        const val PUERTO = 5000
        private const val TOKEN_SEGURIDAD = "TOKEN_OT_27400"
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var writer: PrintWriter? = null
    private var jobRed: Job? = null
    private var activo = false

    // Obtiene la IP local Wi-Fi del dispositivo para mostrarla en pantalla
    fun obtenerIpLocal(context: Context): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt == 0) "10.0.2.15 (Emulador)"
            else String.format(
                Locale.US,
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (e: Exception) {
            "IP no disponible"
        }
    }

    // MODO 1: Servidor (Actúa como Nodo IoT / Receptor en puerto 5000)
    fun iniciarServidor() {
        detener()
        activo = true
        jobRed = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(PUERTO)
                Log.i(TAG, "SERVIDOR IOT: Esperando conexión en puerto $PUERTO...")
                withContext(Dispatchers.Main) {
                    onEstadoConexionCambiado(false, "Esperando dispositivo en puerto $PUERTO...")
                }

                while (activo) {
                    val socketAceptado = serverSocket?.accept() ?: break
                    clientSocket = socketAceptado
                    writer = PrintWriter(socketAceptado.getOutputStream(), true)
                    val ipCliente = socketAceptado.inetAddress.hostAddress ?: "Desconocida"

                    Log.i(TAG, "CONEXIÓN ESTABLECIDA: Dispositivo par conectado desde $ipCliente")
                    withContext(Dispatchers.Main) {
                        onEstadoConexionCambiado(true, "Conectado con $ipCliente")
                    }

                    escucharMensajes(socketAceptado)
                }
            } catch (e: Exception) {
                if (activo) {
                    Log.e(TAG, "Error en Servidor Socket: ${e.message}")
                }
            }
        }
    }

    // MODO 2: Cliente con RECONEXIÓN AUTOMÁTICA CADA 2 SEGUNDOS (Fail-Safe)
    fun iniciarClienteConReconexion(ipDestino: String) {
        detener()
        activo = true
        jobRed = CoroutineScope(Dispatchers.IO).launch {
            while (activo) {
                try {
                    Log.d(TAG, "Intentando conectar a $ipDestino:$PUERTO...")
                    val socket = Socket()
                    socket.connect(InetSocketAddress(ipDestino.trim(), PUERTO), 2000)
                    clientSocket = socket
                    writer = PrintWriter(socket.getOutputStream(), true)

                    Log.i(TAG, "CONEXIÓN EXITOSA con Nodo IoT ($ipDestino:$PUERTO)")
                    withContext(Dispatchers.Main) {
                        onEstadoConexionCambiado(true, "Conectado a $ipDestino")
                    }

                    // Sincronizar eventos que quedaron pendientes mientras no había red
                    val pendientes = dbHelper.sincronizarEventosPendientes()
                    for (ev in pendientes) {
                        enviarTramaSegura("SYNC|${ev.fechaHora}|${ev.usuario}|${ev.descripcion}")
                    }

                    // Escuchar datos hasta que se caiga la conexión
                    escucharMensajes(socket)

                } catch (e: Exception) {
                    Log.w(TAG, "Sin conexión con $ipDestino. Reintentando automáticamente en 2 segundos...")
                    withContext(Dispatchers.Main) {
                        onEstadoConexionCambiado(false, "Desconectado - Reintentando en 2s...")
                    }
                }

                if (activo) {
                    delay(2000L) // Reconexión automática cada 2 segundos exigida en rúbrica
                }
            }
        }
    }

    private suspend fun escucharMensajes(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            while (activo && !socket.isClosed) {
                val linea = reader.readLine() ?: break
                Log.d(TAG, "TRAMA RECIBIDA: $linea")

                // Validar token de seguridad ISO 27400
                if (linea.startsWith("$TOKEN_SEGURIDAD|")) {
                    val contenido = linea.removePrefix("$TOKEN_SEGURIDAD|")
                    withContext(Dispatchers.Main) {
                        onMensajeRecibido(contenido)
                    }
                } else {
                    Log.e(TAG, "SEGURIDAD: Trama rechazada por token inválido -> $linea")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Enlace interrumpido: ${e.message}")
        } finally {
            writer = null
            withContext(Dispatchers.Main) {
                onEstadoConexionCambiado(false, "Enlace perdido - Reconectando...")
            }
        }
    }

    // Envía comando encapsulado con token de seguridad; si no hay red, guarda localmente
    fun enviarComando(usuario: String, comando: String, descripcionLog: String): Boolean {
        val conectado = clientSocket != null && clientSocket?.isConnected == true && writer != null
        return if (conectado) {
            CoroutineScope(Dispatchers.IO).launch {
                enviarTramaSegura("$usuario|$comando")
            }
            dbHelper.registrarEvento(usuario, descripcionLog, sincronizado = true)
            true
        } else {
            // Modo Fail-Safe: guarda en SQLite con sincronizado = false para enviarlo al reconectar
            dbHelper.registrarEvento(usuario, "[PENDIENTE RED] $descripcionLog", sincronizado = false)
            Log.w(TAG, "OFFLINE FAIL-SAFE: Evento guardado en SQLite para envío posterior.")
            false
        }
    }

    private fun enviarTramaSegura(payload: String) {
        try {
            writer?.println("$TOKEN_SEGURIDAD|$payload")
            Log.i(TAG, "TRAMA ENVIADA: $TOKEN_SEGURIDAD|$payload")
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando trama: ${e.message}")
        }
    }

    fun detener() {
        activo = false
        jobRed?.cancel()
        try {
            writer?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (_: Exception) {
        }
        writer = null
        clientSocket = null
        serverSocket = null
    }
}