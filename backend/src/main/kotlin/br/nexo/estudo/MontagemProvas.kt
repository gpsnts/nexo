package br.nexo.estudo

import br.nexo.NexoProperties
import br.nexo.conta.ErroNegocio
import br.nexo.material.Geracao
import br.nexo.material.GeracaoDto
import br.nexo.material.Pedido
import br.nexo.seguranca.UsuarioAtual
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Motivo que interrompe a montagem inteira (cota esgotada, IA fora do ar): o que já foi gerado é aproveitado.
 * @property codigo código do motivo.
 */
private class Parada(val codigo: String) : RuntimeException(codigo)

/**
 * Monta, em segundo plano, as provas com questões novas: pede ao serviço de geração as questões de cada tópico (em lotes, um de cada vez),
 * espera cada lote terminar, e depois completa a prova com questões do banco. Cada lote passa pela mesma verificação de fidelidade e pelo mesmo
 * descarte de repetidas da geração comum, e conta na cota de IA do estudante. Excluir a prova durante a montagem cancela o que falta.
 *
 * @property provas regras e gravação das provas.
 * @property ativas provas que este servidor está montando ou tem na fila.
 * @property geracao geração de questões por tópico.
 * @property props parâmetros de negócio.
 * @property tx gerenciador de transação.
 * @property jdbc acesso ao banco.
 */
@Service
class MontagemProvas(
    private val provas: Provas,
    private val ativas: ProvasAtivas,
    private val geracao: Geracao,
    private val props: NexoProperties,
    private val tx: TransactionTemplate,
    private val jdbc: JdbcTemplate,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pool = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(50)) { r -> Thread(r, "montagem-prova").apply { isDaemon = true } }

    /**
     * Enfileira a montagem das provas (ou monta na hora, se a montagem não for assíncrona, como nos testes).
     * @param tarefas provas já gravadas com status `montando`.
     */
    fun submeter(tarefas: List<TarefaMontagem>) {
        for (t in tarefas) {
            ativas.marcar(t.provaId)
            val corpo = Runnable { try { executar(t) } finally { ativas.soltar(t.provaId) } }
            if (!props.prova.montagemAssincrona) { corpo.run(); continue }
            try {
                pool.execute(corpo)
            } catch (e: RejectedExecutionException) {
                ativas.soltar(t.provaId)
                UsuarioAtual.como(t.usuarioId) { provas.falharMontagem(t.provaId, "fila_cheia") }
            }
        }
    }

    /**
     * Gera as questões novas da prova, lote a lote, e fecha a montagem.
     * @param t prova em montagem.
     * @return nada de útil (o resultado fica gravado na prova).
     */
    private fun executar(t: TarefaMontagem) = UsuarioAtual.como(t.usuarioId) {
        if (!provas.montando(t.provaId)) return@como  // excluída ou encerrada enquanto esperava na fila: não gasta cota
        val novas = mutableListOf<UUID>()
        var motivo = "sem_questoes"
        var falhasSeguidas = 0
        try {
            for (plano in t.planos) {
                var restante = plano.quantidade
                while (restante > 0) {
                    if (!provas.montando(t.provaId)) return@como  // a prova foi excluída: nada mais a fazer
                    val lote = minOf(restante, props.geracao.questoesPorPedido)
                    restante -= lote
                    val (pedido, antes) = pedir(t, plano.topicoId, lote, novas.size) ?: continue
                    val g = aguardar(t, pedido, novas.size) ?: return@como
                    if (g.motivo == "cancelada") throw Parada("cancelada")
                    if (g.status == "falhou") {
                        // duas gerações seguidas falhando (IA fora do ar, embedding): parar poupa a cota e os limites por hora do estudante
                        if (++falhasSeguidas >= 2) throw Parada("falha_ia")
                        continue
                    }
                    falhasSeguidas = 0
                    novas += idsDoTopico(plano.topicoId) - antes.toSet()
                    provas.registrarGeradas(t.provaId, novas.size)
                }
            }
        } catch (e: Parada) {
            motivo = e.codigo
            log.warn("Montagem da prova {} interrompida: {}", t.provaId, e.codigo)
        } catch (e: Exception) {
            motivo = "erro_interno"
            log.error("Montagem da prova {} falhou", t.provaId, e)
        }
        runCatching { provas.fecharMontagem(t, novas, motivo) }.onFailure { log.error("Não foi possível fechar a prova {}", t.provaId, it) }
    }

    /**
     * Pede um lote de questões de um tópico; se o tópico ou o usuário já tem geração aberta, espera a vaga (marcando o pulso da prova).
     * Antes de cada tentativa guarda os ids que o tópico já tem, para saber depois quais questões esta geração acrescentou (e não as de outra operação).
     * @param t prova em montagem.
     * @param topico tópico.
     * @param lote quantas questões pedir.
     * @param geradas questões novas já geradas na prova, para o pulso.
     * @return o pedido e os ids do tópico de antes dele, ou nulo se este lote deve ser pulado (tópico cheio, removido, sem vaga depois de esperar ou prova excluída).
     * @throws Parada se a cota ou a IA impedem de continuar.
     */
    private fun pedir(t: TarefaMontagem, topico: UUID, lote: Int, geradas: Int): Pair<Pedido, List<UUID>>? {
        repeat(MAX_ESPERAS_DE_VAGA) {
            val antes = idsDoTopico(topico)
            try {
                return geracao.pedirQuestoes(topico, lote, "media", t.orientacao) to antes
            } catch (e: ErroNegocio) {
                when (e.codigo) {
                    "geracao_em_andamento", "geracoes_simultaneas" -> {
                        if (!provas.montando(t.provaId)) return null
                        provas.registrarGeradas(t.provaId, geradas)
                        Thread.sleep(props.prova.montagemEspera.toMillis())
                    }
                    "limite_creditos", "limite_operacoes", "ia_indisponivel", "fila_cheia" -> throw Parada(e.codigo ?: "erro")
                    else -> return null
                }
            }
        }
        return null
    }

    /**
     * Espera uma geração terminar, marcando o pulso da prova a cada consulta.
     * @param t prova em montagem.
     * @param pedido geração pedida.
     * @param geradas questões novas já geradas na prova, para o pulso.
     * @return o estado final da geração, ou nulo se a prova foi excluída enquanto esperava (a geração é cancelada).
     */
    private fun aguardar(t: TarefaMontagem, pedido: Pedido, geradas: Int): GeracaoDto? {
        while (true) {
            val g = geracao.consultar(pedido.registroId)
            if (g.status == "concluida" || g.status == "falhou") return g
            if (!provas.montando(t.provaId)) { runCatching { geracao.cancelar(pedido.registroId) }; return null }
            provas.registrarGeradas(t.provaId, geradas)
            Thread.sleep(props.prova.montagemEspera.toMillis())
        }
    }

    /**
     * Ids das questões de um tópico, para saber quais a geração acrescentou.
     * @param topico tópico.
     * @return os ids.
     */
    private fun idsDoTopico(topico: UUID): List<UUID> = tx.execute { jdbc.query("SELECT id FROM questao WHERE topico_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, topico) }!!

    override fun destroy() = pool.shutdown()

    private companion object {
        /** Quantas vezes (uma por `montagem-espera`) a montagem espera uma vaga de geração antes de pular o lote. */
        const val MAX_ESPERAS_DE_VAGA = 30
    }
}
