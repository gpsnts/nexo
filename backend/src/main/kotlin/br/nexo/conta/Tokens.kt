package br.nexo.conta

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Segredos opacos de 256 bits (refresh e links de e-mail). O banco guarda só o hash SHA-256. */
object Tokens {
    private val aleatorio = SecureRandom()

    /**
     * Gera um segredo aleatório de 256 bits.
     * @return o segredo em Base64 URL, sem preenchimento.
     */
    fun novo(): String = ByteArray(32).also(aleatorio::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /**
     * Calcula o hash gravado no banco.
     * @param bruto segredo bruto.
     * @return SHA-256 em hexadecimal.
     */
    fun hash(bruto: String): String = MessageDigest.getInstance("SHA-256").digest(bruto.toByteArray()).joinToString("") { "%02x".format(it) }
}
