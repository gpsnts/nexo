package br.nexo.seguranca

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

/** Usuário dono da transação corrente: o do JWT, ou o informado por [como] (cadastro, sem token ainda). */
object UsuarioAtual {
    private val forcado = ThreadLocal<UUID?>()

    /**
     * Id do usuário corrente.
     * @return id do usuário forçado por [como] ou, na falta, o do JWT autenticado; nulo se não houver usuário.
     */
    fun id(): UUID? = forcado.get()
        ?: (SecurityContextHolder.getContext().authentication?.principal as? Jwt)?.subject?.let(UUID::fromString)

    /**
     * Id da sessão do token corrente.
     * @return id da sessão (claim `sid` do JWT) ou nulo sem autenticação.
     */
    fun sessaoId(): UUID? = (SecurityContextHolder.getContext().authentication?.principal as? Jwt)?.getClaimAsString("sid")?.let(UUID::fromString)

    /**
     * Executa o bloco com o usuário informado como dono da transação (cadastro, antes de haver token); restaura o anterior ao terminar.
     * @param id usuário a usar.
     * @param bloco trabalho a executar.
     * @return o resultado do bloco.
     */
    fun <T> como(id: UUID, bloco: () -> T): T {
        val anterior = forcado.get()
        forcado.set(id)
        try {
            return bloco()
        } finally {
            if (anterior == null) forcado.remove() else forcado.set(anterior)
        }
    }
}
