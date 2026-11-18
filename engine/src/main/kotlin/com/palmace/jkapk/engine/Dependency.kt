package com.palmace.jkapk.engine

enum class Scope { IMPLEMENTATION, COMPILE_ONLY, RUNTIME_ONLY }

data class Dependency(
    val group: String,
    val artifact: String,
    val version: String,
    val scope: Scope = Scope.IMPLEMENTATION,
    val preferLocal: Boolean = false
) {
    val coordinate: String get() = "$group:$artifact:$version"
}
