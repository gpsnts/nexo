package br.nexo.seguranca

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

/**
 * Trilha dos eventos de segurança (ADR 0017), uma linha por evento no logger "auditoria": `evento=… usuario=<uuid> ip=…`.
 * Nunca registra e-mail, senha nem token. O IP vem da requisição corrente ("-" em segundo plano).
 */
@Component
class Auditoria {
    private val log = LoggerFactory.getLogger("auditoria")

    fun registrar(evento: String, usuarioId: UUID? = null, detalhe: String? = null) {
        val ip = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.remoteAddr ?: "-"
        log.info("evento={} usuario={} ip={}{}", evento, usuarioId ?: "-", ip, detalhe?.let { " detalhe=$it" } ?: "")
    }
}
