package br.nexo.seguranca

import br.nexo.conta.EstadoConta
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Confere, a cada requisição autenticada, se a conta ainda pode usar o sistema. O token de acesso dura minutos e continuaria valendo
 * depois de uma suspensão; esta consulta por chave primária faz o corte valer na hora. Conta apagada também perde o acesso.
 *
 * Não é um `@Component` de propósito: o Spring Boot registraria o filtro também fora da cadeia de segurança, antes de haver usuário autenticado.
 * Ele entra na cadeia pela [SegurancaConfig], logo depois da leitura do token.
 *
 * @property jdbc acesso ao banco (a tabela `usuario` não tem isolamento por conta).
 */
class FiltroEstadoConta(private val jdbc: JdbcTemplate) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) = request.requestURI.startsWith("/auth/") || request.requestURI.startsWith("/actuator/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val sujeito = (SecurityContextHolder.getContext().authentication?.principal as? Jwt)?.subject
        if (sujeito == null) { chain.doFilter(request, response); return }
        val status = jdbc.query("SELECT status_conta FROM usuario WHERE id = ?", { rs, _ -> rs.getString(1) }, UUID.fromString(sujeito)).firstOrNull()
        val erro = if (status == null) null else EstadoConta.erro(status)
        if (status != null && erro == null) { chain.doFilter(request, response); return }
        response.status = if (erro != null) 403 else 401
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write(
            if (erro != null) """{"mensagem":"${erro.message}","codigo":"${erro.codigo}"}"""
            else """{"mensagem":"Sessão expirada. Entre novamente.","codigo":"sessao_expirada"}""",
        )
    }
}
