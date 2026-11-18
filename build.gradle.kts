// Raíz: solo declara plugins compartidos.
// Cada módulo aplica lo que necesita.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
