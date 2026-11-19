package com.palmace.jkapk.engine

import java.io.File

// Configuracion de firma para builds debug o release.
// Si se deja null en ProjectConfig, SignStage usa el debug.keystore empaquetado.
data class SigningConfig(
    val keystoreFile: File,
    val keystorePassword: String,
    val keyAlias: String,
    val keyPassword: String = keystorePassword,
    val storeType: String? = null
)
