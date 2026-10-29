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
    /**
     * Cria um token de uso único e grava só o hash.
     * @param usuarioId dono do token.
     * @param tipo finalidade (redefinição, confirmação ou verificação).
     * @param novoEmail e-mail pendente, só na troca de e-mail.
     * @param validade tempo de vida do token.
     * @return o token bruto, para ir no link do e-mail.
     */
    fun criar(usuarioId: UUID, tipo: String, novoEmail: String?, validade: Duration): String {
        val bruto = Tokens.novo()
        tokens.save(TokenConta(UUID.randomUUID(), usuarioId, tipo, Tokens.hash(bruto), novoEmail, relogio.instant().plus(validade)))
        return bruto
    }

    /**
     * Valida um token bruto: existente, do tipo certo, não usado e não expirado.
     * @param bruto token recebido no link.
     * @param tipo finalidade esperada.
     * @return o registro do token.
     * @throws ErroNegocio 400 se o link for inválido ou expirado.
     */
    fun validar(bruto: String, tipo: String): TokenConta {
        val t = tokens.findByTokenHashAndTipo(Tokens.hash(bruto), tipo)
        if (t == null || t.usadoEm != null || t.expiraEm.isBefore(relogio.instant())) throw linkInvalido()
        return t
    }

    /**
     * Conta os pedidos do tipo feitos na última hora (base do limite por hora).
     * @param usuarioId usuário.
     * @param tipo finalidade do token.
     * @return quantidade de tokens criados na última hora.
     */
    fun pedidosNaUltimaHora(usuarioId: UUID, tipo: String): Long =
        tokens.countByUsuarioIdAndTipoAndCriadoEmAfter(usuarioId, tipo, relogio.instant().minus(Duration.ofHours(1)))

    /**
     * Invalida os tokens pendentes do tipo, para só o mais novo valer.
     * @param usuarioId usuário.
     * @param tipo finalidade do token.
     * @return quantidade de tokens invalidados.
     */
    fun invalidarPendentes(usuarioId: UUID, tipo: String) = tokens.invalidarPendentes(usuarioId, tipo, relogio.instant())

    /**
     * Instante atual.
     * @return o instante do relógio injetado (fixo nos testes).
     */
    fun agora(): Instant = relogio.instant()

    companion object {
        const val REDEFINICAO = "redefinicao_senha"
        const val CONFIRMACAO = "confirmacao_email"
        const val VERIFICACAO = "verificacao_email"

        /**
         * Erro padrão de link inválido.
         * @return [ErroNegocio] 400 com código `link_invalido`.
         */
        fun linkInvalido() = ErroNegocio(HttpStatus.BAD_REQUEST, "Link inválido ou expirado. Peça um novo.", "link_invalido")
    }
}
