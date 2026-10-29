package br.nexo.seguranca

import br.nexo.NexoProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Limite por IP nas rotas públicas que custam um hash Argon2. O bloqueio de 5 falhas protege uma conta;
 * este protege o servidor de quem tenta contas diferentes. Janela deslizante, em memória.
 * o contador é por instância; com mais de uma instância do backend, mover para um armazenamento compartilhado.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class LimiteTaxaFiltro(private val props: NexoProperties, private val relogio: Clock) : OncePerRequestFilter() {
    private val acessos = ConcurrentHashMap<String, ArrayDeque<Long>>()

    override fun shouldNotFilter(request: HttpServletRequest) = !(request.method == "POST" && request.requestURI in ROTAS)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val agora = relogio.millis()
        val janela = props.limitePublico.janela.toMillis()
        val fila = acessos.computeIfAbsent(chaveIp(request.remoteAddr)) { ArrayDeque() }
        val espera = synchronized(fila) {
            while (fila.isNotEmpty() && fila.first() <= agora - janela) fila.removeFirst()
            if (fila.size >= props.limitePublico.requisicoes) fila.first() + janela - agora else { fila.addLast(agora); 0L }
        }
        if (espera > 0) {
            val segundos = (espera + 999) / 1000
            response.status = 429
            response.setHeader("Retry-After", segundos.toString())
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("""{"mensagem":"Muitas tentativas. Aguarde $segundos segundos e tente de novo.","codigo":"limite_taxa","esperaSegundos":$segundos}""")
            return
        }
        chain.doFilter(request, response)
    }

    /** Remove da memória os IPs sem acessos dentro da janela (roda a cada 10 minutos). */
    @Scheduled(fixedRate = 600_000)
    fun limpar() {
        val limite = relogio.millis() - props.limitePublico.janela.toMillis()
        acessos.entries.removeIf { (_, fila) -> synchronized(fila) { fila.isEmpty() || fila.last() <= limite } }
    }

    companion object {
        /**
         * Chave de contagem de um IP: IPv4 como está; IPv6 reduzido ao bloco /64.
         * @param endereco IP do cliente.
         * @return a chave usada no contador.
         */
        fun chaveIp(endereco: String): String {
            if (':' !in endereco) return endereco
            return try {
                (java.net.InetAddress.getByName(endereco) as? java.net.Inet6Address)?.address?.take(8)?.joinToString("") { "%02x".format(it) }?.plus("/64") ?: endereco
            } catch (e: Exception) {
                endereco
            }
        }

        val ROTAS = setOf("/auth/cadastro", "/auth/login", "/auth/esqueci-senha", "/auth/redefinir-senha", "/auth/reenviar-verificacao")
    }
}
