package br.nexo.conta

import org.springframework.http.HttpStatus

/** Além do tamanho (8 a 128, validado no controller): recusa senhas comuns, repetidas e que contêm o e-mail. */
object PoliticaSenha {
    private val comuns: Set<String> by lazy {
        PoliticaSenha::class.java.getResourceAsStream("/senhas-comuns.txt")!!.bufferedReader().readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    fun problema(senha: String, email: String?): String? {
        val minuscula = senha.lowercase()
        val local = email?.substringBefore('@')?.lowercase().orEmpty()
        return when {
            minuscula in comuns -> "Essa senha é muito comum. Escolha outra."
            senha.toSet().size == 1 -> "Evite repetir o mesmo caractere."
            local.length >= 4 && minuscula.contains(local) -> "A senha não pode conter o seu e-mail."
            else -> null
        }
    }

    fun exigir(senha: String, email: String?) {
        problema(senha, email)?.let { throw ErroNegocio(HttpStatus.BAD_REQUEST, it, "senha_fraca") }
    }
}
