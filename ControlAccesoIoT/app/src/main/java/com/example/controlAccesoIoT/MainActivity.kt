package com.example.controlAccesoIoT

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF1F5F9)) {
                    AppControlAccesoIoT()
                }
            }
        }
    }
}

@Composable
fun AppControlAccesoIoT() {
    val context = LocalContext.current
    val dbHelper = remember { DatabaseHelper(context) }
    var usuarioActual by remember { mutableStateOf<Usuario?>(null) }

    if (usuarioActual == null) {
        PantallaLogin(
            dbHelper = dbHelper,
            onLoginExitoso = { usuario -> usuarioActual = usuario }
        )
    } else {
        PantallaPrincipal(
            usuario = usuarioActual!!,
            dbHelper = dbHelper,
            onCerrarSesion = {
                Log.i("CONTROL_ACCESO_IOT", "Cierre de sesión de '${usuarioActual?.username}'")
                usuarioActual = null
            }
        )
    }
}

@Composable
fun PantallaLogin(
    dbHelper: DatabaseHelper,
    onLoginExitoso: (Usuario) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mensajeError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "🔒", fontSize = 46.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Control de Acceso IoT",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0F172A)
        )
        Text(
            text = "Seguridad OT • Estándar ISO/IEC 27400",
            fontSize = 13.sp,
            color = Color(0xFF64748B)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Usuario") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña (Mín. 8 caracteres)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (mensajeError != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = mensajeError!!,
                            color = Color(0xFFDC2626),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        val (userObj, msg) = dbHelper.validarLogin(username, password)
                        if (userObj != null) {
                            mensajeError = null
                            onLoginExitoso(userObj)
                        } else {
                            mensajeError = msg
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A))
                ) {
                    Text("INICIAR SESIÓN", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaPrincipal(
    usuario: Usuario,
    dbHelper: DatabaseHelper,
    onCerrarSesion: () -> Unit
) {
    val context = LocalContext.current

    var conectado by remember { mutableStateOf(false) }
    var estadoRedTexto by remember { mutableStateOf("Sin enlace inalámbrico") }
    var ipDestino by remember { mutableStateOf("192.168.1.") }

    var accesoAbierto by remember { mutableStateOf(false) }
    var bloqueoManual by remember { mutableStateOf(true) }
    var distanciaCm by remember { mutableFloatStateOf(45f) }

    var listaEventos by remember { mutableStateOf(dbHelper.obtenerEventos()) }
    var listaUsuarios by remember { mutableStateOf(dbHelper.obtenerUsuarios()) }

    var nuevoUser by remember { mutableStateOf("") }
    var nuevaPass by remember { mutableStateOf("") }
    var nuevoRol by remember { mutableStateOf("OPERADOR") }
    var msgAdmin by remember { mutableStateOf<String?>(null) }

    val socketManager = remember {
        SocketManager(
            dbHelper = dbHelper,
            onEstadoConexionCambiado = { isConnected, texto ->
                conectado = isConnected
                estadoRedTexto = texto
                listaEventos = dbHelper.obtenerEventos()
            },
            onMensajeRecibido = { trama ->
                val partes = trama.split("|")
                if (partes.size >= 2) {
                    val emisor = partes[0]
                    val cmd = partes[1]
                    when {
                        cmd == "CMD_A" -> {
                            accesoAbierto = true
                            bloqueoManual = false
                            dbHelper.registrarEvento(emisor, "Remoto: Apertura de acceso ('A') - Servo 90°", true)
                        }
                        cmd == "CMD_C" -> {
                            accesoAbierto = false
                            bloqueoManual = true
                            dbHelper.registrarEvento(emisor, "Remoto: Bloqueo Manual ('C') - Servo 0°", true)
                        }
                        cmd.startsWith("DIST_") -> {
                            val dist = cmd.removePrefix("DIST_").toFloatOrNull() ?: 45f
                            distanciaCm = dist
                            if (dist <= 15f) {
                                accesoAbierto = false
                                dbHelper.registrarEvento("SENSOR_HC-SR04", "¡INTRUSO DETECTADO a ${dist.toInt()} cm!", true)
                            }
                        }
                    }
                    listaEventos = dbHelper.obtenerEventos()
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose { socketManager.detener() }
    }

    val ipLocal = remember { socketManager.obtenerIpLocal(context) }
    val hayIntruso = distanciaCm <= 15f

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Panel IoT • ${usuario.username}", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("Rol: ${usuario.rol} | IP Local: $ipLocal", fontSize = 11.sp)
                    }
                },
                actions = {
                    TextButton(onClick = onCerrarSesion) {
                        Text("Salir", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E3A8A),
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(
                                        color = if (conectado) Color(0xFF16A34A) else Color(0xFFD97706),
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = estadoRedTexto,
                                fontWeight = FontWeight.Bold,
                                color = if (conectado) Color(0xFF16A34A) else Color(0xFFD97706),
                                fontSize = 13.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = { socketManager.iniciarServidor() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
                        ) {
                            Text("Modo Nodo IoT (Servidor)", fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = ipDestino,
                                onValueChange = { ipDestino = it },
                                label = { Text("IP del Nodo IoT", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { socketManager.iniciarClienteConReconexion(ipDestino) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                            ) {
                                Text("Conectar", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            item {
                val colorFondo = if (hayIntruso || bloqueoManual) Color(0xFFFEF2F2) else Color(0xFFF0FDF4)
                val colorTexto = if (hayIntruso || bloqueoManual) Color(0xFF991B1B) else Color(0xFF166534)

                Card(
                    colors = CardDefaults.cardColors(containerColor = colorFondo),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = when {
                                hayIntruso -> "🚨 ALERTA: INTRUSO DETECTADO"
                                bloqueoManual -> "🔒 CERRADO (BLOQUEO MANUAL)"
                                else -> "🔓 ACCESO ABIERTO / DESPEJADO"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = colorTexto
                        )
                        Text(
                            text = "Sensor Ultrasónico HC-SR04: ${distanciaCm.toInt()} cm",
                            fontSize = 13.sp,
                            color = colorTexto
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text("Simular distancia sensor (≤ 15 cm dispara alerta):", fontSize = 11.sp, color = Color.DarkGray)
                        Slider(
                            value = distanciaCm,
                            onValueChange = { distanciaCm = it },
                            onValueChangeFinished = {
                                val d = distanciaCm.toInt()
                                if (d <= 15) {
                                    accesoAbierto = false
                                    socketManager.enviarComando(
                                        usuario.username,
                                        "DIST_$d",
                                        "Alerta Sensor: Objeto detectado a $d cm"
                                    )
                                } else {
                                    socketManager.enviarComando(
                                        usuario.username,
                                        "DIST_$d",
                                        "Lectura Sensor: Zona despejada ($d cm)"
                                    )
                                }
                                listaEventos = dbHelper.obtenerEventos()
                            },
                            valueRange = 5f..60f
                        )
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Control de Actuadores (Servo SG90)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    accesoAbierto = true
                                    bloqueoManual = false
                                    socketManager.enviarComando(
                                        usuario.username,
                                        "CMD_A",
                                        "Comando 'A': Apertura de puerta (90°)"
                                    )
                                    listaEventos = dbHelper.obtenerEventos()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                            ) {
                                Text("Abrir ('A')", fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    accesoAbierto = false
                                    bloqueoManual = true
                                    socketManager.enviarComando(
                                        usuario.username,
                                        "CMD_C",
                                        "Comando 'C': Bloqueo Manual (0°)"
                                    )
                                    listaEventos = dbHelper.obtenerEventos()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                            ) {
                                Text("Bloquear ('C')", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (usuario.rol == "ADMIN") {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Administración de Usuarios (Exclusivo ADMIN)",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E3A8A),
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = nuevoUser,
                                onValueChange = { nuevoUser = it },
                                label = { Text("Nuevo Usuario") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = nuevaPass,
                                onValueChange = { nuevaPass = it },
                                label = { Text("Contraseña segura (Ej: Guard2026)") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 6.dp)
                            ) {
                                RadioButton(selected = nuevoRol == "OPERADOR", onClick = { nuevoRol = "OPERADOR" })
                                Text("Operador", fontSize = 13.sp)
                                Spacer(modifier = Modifier.width(16.dp))
                                RadioButton(selected = nuevoRol == "ADMIN", onClick = { nuevoRol = "ADMIN" })
                                Text("Admin", fontSize = 13.sp)
                            }

                            Button(
                                onClick = {
                                    val (ok, msg) = dbHelper.crearUsuario(nuevoUser, nuevaPass, nuevoRol)
                                    msgAdmin = msg
                                    if (ok) {
                                        nuevoUser = ""
                                        nuevaPass = ""
                                        listaUsuarios = dbHelper.obtenerUsuarios()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Crear Usuario")
                            }

                            if (msgAdmin != null) {
                                Text(msgAdmin!!, fontSize = 12.sp, color = Color(0xFF1E3A8A), modifier = Modifier.padding(top = 4.dp))
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Cuentas registradas en SQLite:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)

                            listaUsuarios.forEach { u ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${u.username} [${u.rol}] ${if (u.bloqueado) "⛔ BLOQUEADO" else "✅ Activo"}",
                                        fontSize = 12.sp
                                    )
                                    if (u.bloqueado) {
                                        TextButton(onClick = {
                                            dbHelper.desbloquearUsuario(u.username)
                                            listaUsuarios = dbHelper.obtenerUsuarios()
                                        }) {
                                            Text("Desbloquear", fontSize = 11.sp, color = Color(0xFFDC2626))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Bitácora de Eventos (SQLite Local)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF0F172A)
                )
            }

            items(listaEventos) { ev ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "[${ev.fechaHora}] ${ev.usuario} ${if (!ev.sincronizado) "⏳ (Pendiente red)" else ""}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF475569)
                        )
                        Text(text = ev.descripcion, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}