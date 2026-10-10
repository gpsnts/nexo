package br.nexo.conta

import jakarta.servlet.http.HttpServletRequest

/** De onde veio o login ou a renovação: guardado na sessão para a lista "Sessões ativas". */
data class Origem(val dispositivo: String, val ip: String)

fun HttpServletRequest.origem() = Origem(descreverDispositivo(getHeader("User-Agent")), remoteAddr ?: "")

/** "Firefox em Linux". Não é um parser completo de User-Agent: só o suficiente para a pessoa reconhecer o aparelho. */
fun descreverDispositivo(agente: String?): String {
    if (agente.isNullOrBlank()) return "Dispositivo desconhecido"
    val navegador = when {
        "Edg/" in agente || "EdgA/" in agente -> "Edge"
        "OPR/" in agente || "Opera" in agente -> "Opera"
        "Firefox/" in agente || "FxiOS/" in agente -> "Firefox"
        "Chrome/" in agente || "CriOS/" in agente -> "Chrome"
        "Safari/" in agente -> "Safari"
        else -> "Navegador"
    }
    val sistema = when {
        "Android" in agente -> "Android"
        "iPhone" in agente || "iPad" in agente -> "iOS"
        "Windows" in agente -> "Windows"
        "Mac OS X" in agente || "Macintosh" in agente -> "macOS"
        "CrOS" in agente -> "ChromeOS"
        "Linux" in agente -> "Linux"
        else -> "sistema desconhecido"
    }
    return "$navegador em $sistema"
}
