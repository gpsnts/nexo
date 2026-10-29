package br.nexo.material

import br.nexo.NexoProperties
import br.nexo.conta.ErroNegocio
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore

/**
 * Teto global de envios de material em curso. Cada envio segura o arquivo em memória (até 20 MB, mais as cópias do hash e do multipart
 * para o serviço Python); sem teto, poucos usuários em paralelo esgotariam o heap. Sem vaga na hora: 503, sem fila (o cliente tenta de novo).
 */
@Component
class EnviosSimultaneos(props: NexoProperties) {
    private val vagas = Semaphore(props.material.enviosSimultaneos)

    /**
     * Executa o bloco se houver vaga de envio.
     * @param bloco trabalho do envio.
     * @return o resultado do bloco.
     * @throws ErroNegocio 503 `servidor_ocupado` se não houver vaga.
     */
    fun <T> executar(bloco: () -> T): T {
        if (!vagas.tryAcquire()) throw ErroNegocio(HttpStatus.SERVICE_UNAVAILABLE, "O servidor está recebendo muitos envios agora. Tente novamente em instantes.", "servidor_ocupado")
        try {
            return bloco()
        } finally {
            vagas.release()
        }
    }

    /**
     * Só para os testes: segura todas as vagas e devolve quem as libera.
     * @return função que libera as vagas seguradas.
     */
    internal fun ocuparTudo(): () -> Unit {
        var n = 0
        while (vagas.tryAcquire()) n++
        return { vagas.release(n) }
    }
}
