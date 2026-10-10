package br.nexo.seguranca

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

/** Usuário dono da transação corrente: o do JWT, ou o informado por [como] (cadastro, sem token ainda). */
object UsuarioAtual {
    private val forcado = ThreadLocal<UUID?>()

    fun id(): UUID? = forcado.get()
        ?: (SecurityContextHolder.getContext().authentication?.principal as? Jwt)?.subject?.let(UUID::fromString)

    fun sessaoId(): UUID? = (SecurityContextHolder.getContext().authentication?.principal as? Jwt)?.getClaimAsString("sid")?.let(UUID::fromString)

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
