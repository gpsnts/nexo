package br.nexo.material

import br.nexo.NexoProperties
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** Conteúdo-base de um tópico: os trechos que vão ao modelo, na ordem do material, e o gasto da busca (embedding do nome do tópico). */
class Base(val topico: TopicoLinha, val trechos: List<TrechoBase>, val totalNaFaixa: Int, val tokensEmbedding: Int, val modeloEmbedding: String?) {
    val livre get() = trechos.isEmpty()
    /**
     * Trechos numerados de 1 a N para o prompt (ordens de materiais diferentes se repetem).
     * @return os trechos com o rótulo e o texto.
     */
    fun paraModelo() = trechos.mapIndexed { i, t -> TrechoConteudo(i + 1, t.conteudo) }  // rótulos 1..N: ordens de materiais diferentes se repetem
}

/**
 * Tópico com fontes: se todas as faixas cabem no orçamento, usa tudo, em ordem, sem busca; senão embute o nome do tópico e traz os trechos mais
 * similares dentro das faixas (busca exata) até o orçamento, reordenados pelo material. Sem fontes: lista vazia (conhecimento geral do modelo).
 * Deve ser chamado em transação (RLS); a chamada HTTP de embedding acontece FORA dela (o chamador passa [embutir]).
 */
@Service
class ConteudoBase(private val dados: GeracaoDados, private val proc: Processamento, private val props: NexoProperties) {
    private val orcamento get() = props.geracao.contextoMaxCaracteres

    /**
     * Diz se o conteúdo do tópico passa do orçamento e exige busca vetorial.
     * @param topicoId tópico.
     * @return verdadeiro se há fontes e as faixas somam mais caracteres que o orçamento de contexto.
     */
    fun precisaDeBusca(topicoId: UUID): Boolean = dados.fontes(topicoId).isNotEmpty() && dados.caracteresDasFaixas(topicoId) > orcamento

    /**
     * Monta o conteúdo-base do tópico. [variar]: em tópico grande, trechos ainda não citados por nenhuma questão vêm primeiro, para os pedidos seguintes não reciclarem sempre os mesmos.
     * @param topico tópico.
     * @param vetorDoNome embedding do nome do tópico, quando houver busca; nulo = usar todos os trechos.
     * @param variar prioriza trechos ainda não citados.
     * @return os trechos escolhidos e o gasto do embedding.
     */
    fun obter(topico: TopicoLinha, vetorDoNome: Vetores?, variar: Boolean = false): Base {
        if (dados.fontes(topico.id).isEmpty()) return Base(topico, emptyList(), 0, 0, null)
        val todos = dados.trechosDasFaixas(topico.id)
        if (vetorDoNome == null) return Base(topico, todos, todos.size, 0, null)
        val candidatos = dados.trechosMaisSimilares(topico.id, vetorDoNome.vetores.first(), MAX_CANDIDATOS, variar)
        var soma = 0
        val escolhidos = candidatos.takeWhile { t -> (soma + t.conteudo.length <= orcamento || soma == 0).also { if (it) soma += t.conteudo.length } }
        return Base(topico, escolhidos.sortedWith(compareBy({ it.materialId }, { it.ordem })), todos.size, vetorDoNome.tokens, vetorDoNome.modelo)
    }

    /**
     * Custo estimado do embedding.
     * @param modelo modelo de embedding, ou nulo.
     * @param tokens tokens consumidos.
     * @return custo em US$ (zero se o modelo não tiver preço).
     */
    fun custoEmbedding(modelo: String?, tokens: Int): BigDecimal =
        modelo?.let { props.precoEmbedding(it) }?.multiply(BigDecimal(tokens))?.divide(BigDecimal(1_000_000), 6, RoundingMode.HALF_UP) ?: BigDecimal.ZERO

    companion object {
        const val MAX_CANDIDATOS = 200
    }
}
