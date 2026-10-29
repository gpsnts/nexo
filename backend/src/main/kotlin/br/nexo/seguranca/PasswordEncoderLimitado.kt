package br.nexo.seguranca

import br.nexo.conta.ErroNegocio
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Limita quantos hashes rodam ao mesmo tempo. Cada Argon2id usa ~16 MiB; sem teto, uma rajada vinda de muitos IPs
 * (o limite por IP não a barra) esgotaria a memória. Quem passa do tempo de espera recebe 503 em vez de derrubar o servidor.
 */
class PasswordEncoderLimitado(private val base: PasswordEncoder, permissoes: Int, private val espera: Duration) : PasswordEncoder {
    private val vagas = Semaphore(permissoes, true)

    override fun encode(rawPassword: CharSequence?): String? = limitar { base.encode(rawPassword) }

    override fun matches(rawPassword: CharSequence?, encodedPassword: String?): Boolean = limitar { base.matches(rawPassword, encodedPassword) }

    override fun upgradeEncoding(encodedPassword: String?): Boolean = base.upgradeEncoding(encodedPassword)

    /**
     * Executa a tarefa só se houver vaga entre os hashes simultâneos.
     * @param tarefa cálculo ou verificação do hash.
     * @return o resultado da tarefa.
     * @throws ErroNegocio 503 se a espera pela vaga estourar.
     */
    private fun <T> limitar(tarefa: () -> T): T {
        val entrou = try {
            vagas.tryAcquire(espera.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!entrou) throw ErroNegocio(HttpStatus.SERVICE_UNAVAILABLE, "Servidor ocupado. Tente novamente em instantes.", "servidor_ocupado")
        try {
            return tarefa()
        } finally {
            vagas.release()
        }
    }
}
