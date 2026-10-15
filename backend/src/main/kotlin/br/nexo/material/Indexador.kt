package br.nexo.material

import br.nexo.NexoProperties
import br.nexo.seguranca.UsuarioAtual
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class Tarefa(val usuarioId: UUID, val materialId: UUID, val assuntoId: UUID)

class FilaCheia : RuntimeException("Fila de indexação cheia")

/**
 * UC04 e UC05 em executor próprio (ADR 0024). O estado da tarefa é o do banco (material e registro_geracao); a fila é só memória.
 * Tarefa perdida num reinício é detectada pelo [Andamento] (sem pulso e sem tarefa viva em [ativos]) e vira falhou/interrompido.
 * Thread de segundo plano não tem usuário (ADR 0019): tudo roda em `UsuarioAtual.como` e cada etapa abre a PRÓPRIA transação curta;
 * nenhuma transação fica aberta durante a chamada HTTP ao serviço Python.
 */
@Component
class Indexador(
    private val props: NexoProperties,
    private val dados: Dados,
    private val proc: Processamento,
    private val armazenamento: Armazenamento,
    private val topicos: TopicoService,
    private val tx: TransactionTemplate,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    val ativos: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val pool = ThreadPoolExecutor(props.material.threads, props.material.threads, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(props.material.fila)) { r ->
        Thread(r, "indexacao").apply { isDaemon = true }
    }

    fun submeter(t: Tarefa) {
        ativos += t.materialId
        val corpo = Runnable { executar(t) }
        if (!props.material.assincrono) return corpo.run()
        try {
            pool.execute(corpo)
        } catch (e: RejectedExecutionException) {
            ativos -= t.materialId
            throw FilaCheia()
        }
    }

    private fun executar(t: Tarefa) = UsuarioAtual.como(t.usuarioId) {
        try {
            rodar(t)
        } catch (e: FalhaProcessamento) {
            log.warn("Indexação de {} falhou: {}", t.materialId, e.message)
            falhar(t, if (e.codigo in CODIGOS_CONHECIDOS) e.codigo else "falha_ia")
        } catch (e: Exception) {
            log.error("Indexação de {} falhou", t.materialId, e)
            falhar(t, "erro_interno")
        } finally {
            ativos -= t.materialId
        }
    }

    private fun falhar(t: Tarefa, motivo: String) {
        runCatching {
            UsuarioAtual.como(t.usuarioId) {
                tx.executeWithoutResult {
                    dados.definirStatus(t.materialId, "falhou", motivo)
                    dados.falharRegistrosAbertos(t.materialId)
                }
            }
        }.onFailure { log.error("Não foi possível marcar {} como falhou", t.materialId, it) }
    }

    private fun rodar(t: Tarefa) {
        val m = tx.execute { dados.material(t.materialId) } ?: return  // excluído enquanto esperava na fila
        val (modeloLinguagem, modeloEmbedding) = proc.modelos()
        val idx = UUID.randomUUID()
        tx.executeWithoutResult {
            dados.definirStatus(t.materialId, "processando")
            dados.criarRegistro(idx, t.usuarioId, t.materialId, "indexacao_material", modeloEmbedding, 2)
        }

        // Extração e divisão (uma chamada): 2 -> 30
        val arquivo = armazenamento.ler(m.caminho)
        val extracao = proc.processar(m.nomeArquivo, arquivo)
        if (extracao.trechos.isEmpty()) throw FalhaProcessamento("sem_texto", "Extração sem trechos")
        tx.executeWithoutResult { dados.progresso(idx, 30) }

        // Embeddings em lotes; cada lote grava os trechos na própria transação: 30 -> 95
        var tokens = 0
        var modelo = modeloEmbedding
        val lotes = extracao.trechos.chunked(props.material.loteEmbeddings)
        lotes.forEachIndexed { i, lote ->
            val v = proc.embeddings(lote.map { it.conteudo })
            if (v.vetores.size != lote.size || v.vetores.any { it.size != DIMENSAO }) throw FalhaProcessamento("falha_ia", "Embeddings fora do esperado (dimensão ${DIMENSAO})")
            tokens += v.tokens
            modelo = v.modelo
            tx.executeWithoutResult {
                dados.inserirTrechos(t.usuarioId, t.materialId, v.modelo, lote, v.vetores)
                dados.progresso(idx, 30 + 65 * (i + 1) / lotes.size)
            }
        }
        val custo = props.material.precosEmbeddingUsdPorMilhao[modelo]?.multiply(BigDecimal(tokens))?.divide(BigDecimal(1_000_000), 6, java.math.RoundingMode.HALF_UP) ?: BigDecimal.ZERO
        tx.executeWithoutResult { dados.concluirRegistro(idx, modelo, tokens, 0, custo) }

        sugerirTopicos(t, extracao.trechos, modeloLinguagem)
    }

    /** UC05. Modelo sem proposta válida (ou fora do ar): blocos de tamanho semelhante (A1). A cobertura é sempre conferida aqui (regra 12). */
    private fun sugerirTopicos(t: Tarefa, trechos: List<TrechoExtraido>, modeloLinguagem: String) {
        val sug = UUID.randomUUID()
        tx.executeWithoutResult { dados.criarRegistro(sug, t.usuarioId, t.materialId, "sugestao_topicos", modeloLinguagem, 10) }
        var tokensEntrada = 0
        var tokensSaida = 0
        var modelo = modeloLinguagem
        // 1) Estrutura do documento (títulos): determinística, sem custo e explicável. 2) Modelo de linguagem, só se não houver estrutura. 3) Blocos iguais.
        var proposta: List<TopicoProposto> = Cobertura.porEstrutura(trechos, props.material.trechosMinimosPorTopico, props.material.maxTopicos) ?: emptyList()
        if (proposta.isNotEmpty()) modelo = MODELO_ESTRUTURA
        else try {
            val r = proc.topicos(trechos.map { ResumoTrecho(it.ordem, it.nivel, it.titulo, it.conteudo.take(120)) })
            tokensEntrada = r.tokensEntrada; tokensSaida = r.tokensSaida; modelo = r.modelo
            proposta = r.topicos
        } catch (e: FalhaProcessamento) {
            log.warn("Sugestão de tópicos de {} sem proposta ({}): usando blocos iguais", t.materialId, e.message)
        }
        val valida = proposta.map { FaixaTopico(null, it.nome, it.inicio, it.fim) }
        if (Cobertura.validar(valida, trechos.size, props.material.maxTopicos) != null) {
            proposta = Cobertura.blocosIguais(trechos.size, props.material.trechosPorTopicoPlanoB, trechos.map { it.titulo })
        }
        tx.executeWithoutResult {
            topicos.criarDaProposta(t.materialId, t.assuntoId, proposta)
            dados.concluirRegistro(sug, modelo, tokensEntrada, tokensSaida, BigDecimal.ZERO)  // sem tabela de preço de modelo de linguagem na F2 (ADR 0024)
            dados.definirStatus(t.materialId, "pronto")
        }
    }

    override fun destroy() = pool.shutdown()

    companion object {
        const val DIMENSAO = 1536
        const val MODELO_ESTRUTURA = "regras-de-estrutura"  // registro_geracao.modelo_ia quando os tópicos vêm dos títulos, sem modelo de linguagem
        val CODIGOS_CONHECIDOS = setOf("sem_texto", "formato_invalido")
    }
}
