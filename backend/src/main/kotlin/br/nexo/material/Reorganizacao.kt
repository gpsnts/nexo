package br.nexo.material

import br.nexo.NexoProperties
import br.nexo.conta.ErroNegocio
import br.nexo.seguranca.UsuarioAtual
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tópico de um rascunho de reorganização.
 * @property id tópico existente, ou nulo se é novo.
 * @property nome nome proposto.
 * @property inicio ordem do primeiro trecho.
 * @property fim ordem do último trecho.
 * @property temConteudo se já tem resumo ou questões (congelado).
 */
data class TopicoRascunho(val id: UUID?, val nome: String, val inicio: Int, val fim: Int, val temConteudo: Boolean)
/**
 * Rascunho de divisão proposto pela IA (não gravado).
 * @property topicos tópicos propostos.
 * @property mudou se difere da divisão atual.
 * @property modelo modelo usado.
 * @property tokensEntrada tokens do prompt.
 * @property tokensSaida tokens gerados.
 */
data class Rascunho(val topicos: List<TopicoRascunho>, val mudou: Boolean, val modelo: String, val tokensEntrada: Int, val tokensSaida: Int)

/**
 * "Reorganizar com IA": o modelo de linguagem reavalia a divisão atual e propõe outra. Devolve um RASCUNHO e não grava nada: o usuário revisa na tela
 * e confirma pelo `PUT /materiais/{id}/topicos` de sempre, com a mesma regra de tópicos congelados. Cada pedido é uma operação
 * com IA: ganha registro em `registro_geracao` (`sugestao_topicos`), com tokens mesmo quando a proposta é recusada.
 */
@Service
class Reorganizacao(
    private val dados: Dados,
    private val topicos: TopicoService,
    private val proc: Processamento,
    private val tx: TransactionTemplate,
    private val props: NexoProperties,
    private val limites: LimitesIa,
    private val precos: PrecosIa,
) {
    private val emAndamento = ConcurrentHashMap.newKeySet<UUID>()  // um pedido por material por vez (o clique repetido não gasta duas chamadas)

    /**
     * Pede à IA uma nova divisão e devolve um rascunho, sem gravar nada.
     * @param materialId material pronto.
     * @return o rascunho proposto.
     * @throws ErroNegocio 404 se o material não existir; 409 se não estiver pronto ou já houver pedido em andamento; 422 se a IA devolver divisão inválida; 503 se a IA falhar; limites de uso.
     */
    fun reorganizar(materialId: UUID): Rascunho {
        val uid = UsuarioAtual.id() ?: throw ErroNegocio(HttpStatus.UNAUTHORIZED, "Não autenticado.", "nao_autenticado")
        val atuais = tx.execute {
            val m = dados.material(materialId) ?: throw ErroNegocio(HttpStatus.NOT_FOUND, "Material não encontrado.", "nao_encontrado")
            if (m.status != "pronto") throw ErroNegocio(HttpStatus.CONFLICT, "O material ainda não está pronto.", "material_nao_pronto")
            limites.exigirDisponivel()
            topicos.doMaterial(materialId)
        }
        if (!emAndamento.add(materialId)) throw ErroNegocio(HttpStatus.CONFLICT, "Já há uma reorganização em andamento para este material.", "reorganizacao_em_andamento")
        try {
            val resumos = tx.execute { dados.resumosDosTrechos(materialId, PREVIA) }
            val modeloLinguagem = try { proc.modelos().first } catch (e: FalhaProcessamento) { throw indisponivel(e.codigo) }
            val registro = UUID.randomUUID()
            tx.executeWithoutResult { dados.criarRegistro(registro, uid, materialId, "sugestao_topicos", modeloLinguagem, 10) }

            val r = try {
                proc.reorganizar(resumos, atuais.map { TopicoAtual(it.nome, it.inicio!!, it.fim!!, it.temConteudo) })
            } catch (e: FalhaProcessamento) {
                tx.executeWithoutResult { dados.falharRegistro(registro, modeloLinguagem, 0, 0, "falha_ia") }
                throw indisponivel(e.codigo)
            }

            val faixas = r.topicos.map { FaixaTopico(null, it.nome, it.inicio, it.fim) }
            val congeladosIntactos = atuais.filter { it.temConteudo }.all { c -> r.topicos.any { it.inicio == c.inicio && it.fim == c.fim } }
            if (Cobertura.validar(faixas, resumos.size, props.material.maxTopicos) != null || !congeladosIntactos) {
                tx.executeWithoutResult { dados.falharRegistro(registro, r.modelo, r.tokensEntrada, r.tokensSaida, "falha_ia", precos.custoLinguagem(r.modelo, r.tokensEntrada, r.tokensSaida)) }
                throw ErroNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "A IA não devolveu uma divisão válida. Nada foi alterado.", "proposta_ia_invalida")
            }
            tx.executeWithoutResult { dados.concluirRegistro(registro, r.modelo, r.tokensEntrada, r.tokensSaida, precos.custoLinguagem(r.modelo, r.tokensEntrada, r.tokensSaida)) }

            val porFaixa = atuais.associateBy { it.inicio to it.fim }
            val rascunho = r.topicos.map { p -> porFaixa[p.inicio to p.fim].let { TopicoRascunho(it?.id, p.nome.trim(), p.inicio, p.fim, it?.temConteudo ?: false) } }
            val mudou = rascunho.size != atuais.size || rascunho.zip(atuais).any { (n, a) -> n.id != a.id || n.nome != a.nome }
            return Rascunho(rascunho, mudou, r.modelo, r.tokensEntrada, r.tokensSaida)
        } finally {
            emAndamento.remove(materialId)
        }
    }

    /**
     * Erro de IA indisponível.
     * @param codigo código da falha do serviço de IA.
     * @return erro 503 `ia_indisponivel` com mensagem clara.
     */
    private fun indisponivel(codigo: String? = null) = ErroNegocio(HttpStatus.SERVICE_UNAVAILABLE, MensagensIa.deCodigo(codigo), "ia_indisponivel")

    companion object {
        const val PREVIA = 100
    }
}
