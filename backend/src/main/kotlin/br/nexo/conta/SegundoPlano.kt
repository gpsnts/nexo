package br.nexo.conta

import br.nexo.NexoProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Trabalho que não pode atrasar nem diferenciar a resposta HTTP (envio de e-mail, recuperação de senha). Falha vira log, nunca erro para quem chamou.
 * Com [assincrono] falso roda na hora; só os testes usam isso.
 * fila em memória, perde o que estiver pendente se o processo cair; trocar por uma fila durável se o e-mail virar requisito crítico.
 */
class SegundoPlano(private val assincrono: Boolean, capacidade: Int = 200) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    // Fila limitada: se o e-mail travar, o excesso é descartado com log em vez de crescer até esgotar a memória.
    private val pool = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(capacidade)) { r -> Thread(r, "conta-segundo-plano").apply { isDaemon = true } }

    /**
     * Executa a tarefa em segundo plano (ou na hora, se [assincrono] for falso). Falha vira log; fila cheia descarta a tarefa com log.
     * @param tarefa trabalho a executar.
     */
    fun executar(tarefa: () -> Unit) {
        val seguro = Runnable { try { tarefa() } catch (e: Exception) { log.error("Falha em tarefa em segundo plano", e) } }
        if (!assincrono) return seguro.run()
        try {
            pool.execute(seguro)
        } catch (e: RejectedExecutionException) {
            log.error("Fila de segundo plano cheia: tarefa descartada")
        }
    }

    override fun destroy() = pool.shutdown()
}

/** Configuração do executor de segundo plano da conta. */
@Configuration
class SegundoPlanoConfig {
    /**
     * Cria o executor de segundo plano com os parâmetros de `nexo.conta`.
     * @param p parâmetros de negócio.
     * @return executor de tarefas.
     */
    @Bean
    fun segundoPlano(p: NexoProperties) = SegundoPlano(p.conta.assincrono, p.conta.filaSegundoPlano)
}
