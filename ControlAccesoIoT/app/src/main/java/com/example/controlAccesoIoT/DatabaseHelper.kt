package com.example.controlAccesoIoT

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Usuario(
    val id: Int,
    val username: String,
    val rol: String,
    val intentosFallidos: Int,
    val bloqueado: Boolean
)

data class EventoIoT(
    val id: Int,
    val fechaHora: String,
    val usuario: String,
    val descripcion: String,
    val sincronizado: Boolean
)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "seguridad_iot.db", null, 1) {

    companion object {
        private const val TAG = "CONTROL_ACCESO_IOT"

        // Genera un Salt aleatorio de 16 bytes en formato hexadecimal
        fun generarSalt(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        // Cifra la contraseña usando SHA-256 + Salt (Norma ISO 27400)
        fun hashSha256ConSalt(password: String, salt: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest((password + salt).toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        // Valida que la contraseña sea segura (mínimo 8 caracteres, letras y números)
        fun esPasswordSegura(pass: String): Boolean {
            val debiles = listOf("12345678", "password", "admin123", "11111111", "qwerty12")
            if (pass.length < 8) return false
            if (pass.lowercase() in debiles) return false
            val tieneLetra = pass.any { it.isLetter() }
            val tieneNumero = pass.any { it.isDigit() }
            return tieneLetra && tieneNumero
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Tabla de usuarios con control de intentos y roles
        db.execSQL("""
            CREATE TABLE usuarios (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                salt TEXT NOT NULL,
                rol TEXT NOT NULL,
                intentos_fallidos INTEGER DEFAULT 0,
                bloqueado INTEGER DEFAULT 0
            )
        """.trimIndent())

        // Tabla de eventos IoT con bandera 'sincronizado' para modo Fail-Safe
        db.execSQL("""
            CREATE TABLE eventos_iot (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                fecha_hora TEXT NOT NULL,
                usuario TEXT NOT NULL,
                descripcion TEXT NOT NULL,
                sincronizado INTEGER DEFAULT 1
            )
        """.trimIndent())

        // Usuarios iniciales por defecto con claves seguras
        insertarUsuarioInicial(db, "admin", "Admin2026", "ADMIN")
        insertarUsuarioInicial(db, "operador1", "Oper2026", "OPERADOR")
    }

    private fun insertarUsuarioInicial(db: SQLiteDatabase, user: String, pass: String, rol: String) {
        val salt = generarSalt()
        val hash = hashSha256ConSalt(pass, salt)
        val values = ContentValues().apply {
            put("username", user)
            put("password_hash", hash)
            put("salt", salt)
            put("rol", rol)
            put("intentos_fallidos", 0)
            put("bloqueado", 0)
        }
        db.insert("usuarios", null, values)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS usuarios")
        db.execSQL("DROP TABLE IF EXISTS eventos_iot")
        onCreate(db)
    }

    // Autenticación con bloqueo al superar 3 intentos fallidos
    fun validarLogin(username: String, password: String): Pair<Usuario?, String> {
        val db = writableDatabase
        val cursor = db.rawQuery("SELECT * FROM usuarios WHERE username = ?", arrayOf(username.trim()))

        if (!cursor.moveToFirst()) {
            cursor.close()
            Log.w(TAG, "LOGIN FALLIDO: Usuario '$username' no existe en SQLite.")
            return Pair(null, "Usuario o contraseña incorrectos.")
        }

        val id = cursor.getInt(cursor.getColumnIndexOrThrow("id"))
        val hashGuardado = cursor.getString(cursor.getColumnIndexOrThrow("password_hash"))
        val salt = cursor.getString(cursor.getColumnIndexOrThrow("salt"))
        val rol = cursor.getString(cursor.getColumnIndexOrThrow("rol"))
        val intentos = cursor.getInt(cursor.getColumnIndexOrThrow("intentos_fallidos"))
        val bloqueado = cursor.getInt(cursor.getColumnIndexOrThrow("bloqueado")) == 1
        cursor.close()

        if (bloqueado) {
            Log.e(TAG, "ACCESO DENEGADO: La cuenta '$username' está BLOQUEADA por seguridad.")
            return Pair(null, "Cuenta bloqueada por superar 3 intentos. Contacte al Administrador.")
        }

        val hashIngresado = hashSha256ConSalt(password, salt)

        return if (hashIngresado == hashGuardado) {
            // Reiniciar intentos fallidos al iniciar sesión con éxito
            val cv = ContentValues().apply { put("intentos_fallidos", 0) }
            db.update("usuarios", cv, "id = ?", arrayOf(id.toString()))
            Log.i(TAG, "LOGIN EXITOSO: Usuario '$username' ingresó con rol [$rol].")
            registrarEvento(username, "Inicio de sesión exitoso ($rol)", true)
            Pair(Usuario(id, username, rol, 0, false), "OK")
        } else {
            val nuevosIntentos = intentos + 1
            val debeBloquear = nuevosIntentos >= 3
            val cv = ContentValues().apply {
                put("intentos_fallidos", nuevosIntentos)
                put("bloqueado", if (debeBloquear) 1 else 0)
            }
            db.update("usuarios", cv, "id = ?", arrayOf(id.toString()))

            if (debeBloquear) {
                Log.e(TAG, "ALERTA SEGURIDAD ISO 27400: Cuenta '$username' BLOQUEADA tras 3 intentos fallidos.")
                registrarEvento("SISTEMA", "Cuenta '$username' bloqueada por 3 intentos fallidos", true)
                Pair(null, "Cuenta bloqueada (3/3 intentos). Solicite desbloqueo al Administrador.")
            } else {
                Log.w(TAG, "CLAVE ERRÓNEA para '$username'. Intento $nuevosIntentos de 3.")
                Pair(null, "Contraseña incorrecta. Intento $nuevosIntentos de 3.")
            }
        }
    }

    // Crear nuevo usuario (Solo Administrador)
    fun crearUsuario(username: String, password: String, rol: String): Pair<Boolean, String> {
        if (username.isBlank()) return Pair(false, "Ingrese un nombre de usuario.")
        if (!esPasswordSegura(password)) {
            return Pair(false, "La clave debe tener mínimo 8 caracteres, letras y números (no secuencias simples).")
        }
        val db = writableDatabase
        val salt = generarSalt()
        val hash = hashSha256ConSalt(password, salt)
        val cv = ContentValues().apply {
            put("username", username.trim())
            put("password_hash", hash)
            put("salt", salt)
            put("rol", rol)
            put("intentos_fallidos", 0)
            put("bloqueado", 0)
        }
        val res = db.insert("usuarios", null, cv)
        return if (res != -1L) {
            Log.i(TAG, "ADMIN: Usuario '$username' ($rol) creado con SHA-256 + Salt.")
            Pair(true, "Usuario '$username' creado correctamente.")
        } else {
            Pair(false, "El nombre de usuario ya existe.")
        }
    }

    // Desbloquear cuenta (Solo Administrador)
    fun desbloquearUsuario(username: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("intentos_fallidos", 0)
            put("bloqueado", 0)
        }
        db.update("usuarios", cv, "username = ?", arrayOf(username))
        Log.i(TAG, "ADMIN: Cuenta '$username' desbloqueada y contador reiniciado.")
    }

    fun obtenerUsuarios(): List<Usuario> {
        val lista = mutableListOf<Usuario>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM usuarios ORDER BY id ASC", null)
        while (cursor.moveToNext()) {
            lista.add(
                Usuario(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                    username = cursor.getString(cursor.getColumnIndexOrThrow("username")),
                    rol = cursor.getString(cursor.getColumnIndexOrThrow("rol")),
                    intentosFallidos = cursor.getInt(cursor.getColumnIndexOrThrow("intentos_fallidos")),
                    bloqueado = cursor.getInt(cursor.getColumnIndexOrThrow("bloqueado")) == 1
                )
            )
        }
        cursor.close()
        return lista
    }

    // Registrar evento en SQLite (soporta guardar offline si sincronizado = false)
    fun registrarEvento(usuario: String, descripcion: String, sincronizado: Boolean) {
        val db = writableDatabase
        val hora = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
        val cv = ContentValues().apply {
            put("fecha_hora", hora)
            put("usuario", usuario)
            put("descripcion", descripcion)
            put("sincronizado", if (sincronizado) 1 else 0)
        }
        db.insert("eventos_iot", null, cv)
        Log.d(TAG, "SQLITE LOG [$hora] ($usuario): $descripcion | Sincronizado: $sincronizado")
    }

    fun obtenerEventos(): List<EventoIoT> {
        val lista = mutableListOf<EventoIoT>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM eventos_iot ORDER BY id DESC LIMIT 30", null)
        while (cursor.moveToNext()) {
            lista.add(
                EventoIoT(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                    fechaHora = cursor.getString(cursor.getColumnIndexOrThrow("fecha_hora")),
                    usuario = cursor.getString(cursor.getColumnIndexOrThrow("usuario")),
                    descripcion = cursor.getString(cursor.getColumnIndexOrThrow("descripcion")),
                    sincronizado = cursor.getInt(cursor.getColumnIndexOrThrow("sincronizado")) == 1
                )
            )
        }
        cursor.close()
        return lista
    }

    // Obtener eventos retenidos en modo avión/desconexión y marcarlos como enviados
    fun sincronizarEventosPendientes(): List<EventoIoT> {
        val pendientes = mutableListOf<EventoIoT>()
        val db = writableDatabase
        val cursor = db.rawQuery("SELECT * FROM eventos_iot WHERE sincronizado = 0", null)
        while (cursor.moveToNext()) {
            pendientes.add(
                EventoIoT(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                    fechaHora = cursor.getString(cursor.getColumnIndexOrThrow("fecha_hora")),
                    usuario = cursor.getString(cursor.getColumnIndexOrThrow("usuario")),
                    descripcion = cursor.getString(cursor.getColumnIndexOrThrow("descripcion")),
                    sincronizado = false
                )
            )
        }
        cursor.close()

        if (pendientes.isNotEmpty()) {
            val cv = ContentValues().apply { put("sincronizado", 1) }
            db.update("eventos_iot", cv, "sincronizado = 0", null)
            Log.i(TAG, "FAIL-SAFE: Se sincronizaron ${pendientes.size} eventos retenidos en SQLite.")
        }
        return pendientes
    }
}