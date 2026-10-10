package br.nexo.conta

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Tokens de uso único enviados por e-mail (verificação, redefinição de senha, troca de e-mail). Só o hash é gravado. */
@Service
class TokenContaService(private val tokens: TokenContaRepository, private val relogio: Clock) {
    fun criar(usuarioId: UUID, tipo: String, novoEmail: String?, validade: Duration): String {
        val bruto = Tokens.novo()
        tokens.save(TokenConta(UUID.randomUUID(), usuarioId, tipo, Tokens.hash(bruto), novoEmail, relogio.instant().plus(validade)))
        return bruto
    }

    fun validar(bruto: String, tipo: String): TokenConta {
        val t = tokens.findByTokenHashAndTipo(Tokens.hash(bruto), tipo)
        if (t == null || t.usadoEm != null || t.expiraEm.isBefore(relogio.instant())) throw linkInvalido()
        return t
    }

    fun pedidosNaUltimaHora(usuarioId: UUID, tipo: String): Long =
        tokens.countByUsuarioIdAndTipoAndCriadoEmAfter(usuarioId, tipo, relogio.instant().minus(Duration.ofHours(1)))

    fun invalidarPendentes(usuarioId: UUID, tipo: String) = tokens.invalidarPendentes(usuarioId, tipo, relogio.instant())

    fun agora(): Instant = relogio.instant()

    companion object {
        const val REDEFINICAO = "redefinicao_senha"
        const val CONFIRMACAO = "confirmacao_email"
        const val VERIFICACAO = "verificacao_email"

        fun linkInvalido() = ErroNegocio(HttpStatus.BAD_REQUEST, "Link inválido ou expirado. Peça um novo.", "link_invalido")
    }
}
