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

data class TopicoRascunho(val id: UUID?, val nome: String, val inicio: Int, val fim: Int, val temConteudo: Boolean)
data class Rascunho(val topicos: List<TopicoRascunho>, val mudou: Boolean, val modelo: String, val tokensEntrada: Int, val tokensSaida: Int)

/**
 * "Reorganizar com IA": o modelo de linguagem reavalia a divisão atual e propõe outra. Devolve um RASCUNHO e não grava nada: o usuário revisa na tela
 * e confirma pelo `PUT /materiais/{id}/topicos` de sempre (ADR 0025), com a mesma regra de tópicos congelados (ADR 0028). Cada pedido é uma operação
 * com IA: ganha registro em `registro_geracao` (`sugestao_topicos`), com tokens mesmo quando a proposta é recusada (ADR 0032).
 */
@Service
class Reorganizacao(
    private val dados: Dados,
    private val topicos: TopicoService,
    private val proc: Processamento,
    private val tx: TransactionTemplate,
    private val props: NexoProperties,
    private val limites: LimitesIa,
) {
    private val emAndamento = ConcurrentHashMap.newKeySet<UUID>()  // um pedido por material por vez (o clique repetido não gasta duas chamadas)

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
            val modeloLinguagem = try { proc.modelos().first } catch (e: FalhaProcessamento) { throw indisponivel() }
            val registro = UUID.randomUUID()
            tx.executeWithoutResult { dados.criarRegistro(registro, uid, materialId, "sugestao_topicos", modeloLinguagem, 10) }

            val r = try {
                proc.reorganizar(resumos, atuais.map { TopicoAtual(it.nome, it.inicio!!, it.fim!!, it.temConteudo) })
            } catch (e: FalhaProcessamento) {
                tx.executeWithoutResult { dados.falharRegistro(registro, modeloLinguagem, 0, 0) }
                throw indisponivel()
            }

            val faixas = r.topicos.map { FaixaTopico(null, it.nome, it.inicio, it.fim) }
            val congeladosIntactos = atuais.filter { it.temConteudo }.all { c -> r.topicos.any { it.inicio == c.inicio && it.fim == c.fim } }
            if (Cobertura.validar(faixas, resumos.size, props.material.maxTopicos) != null || !congeladosIntactos) {
                tx.executeWithoutResult { dados.falharRegistro(registro, r.modelo, r.tokensEntrada, r.tokensSaida) }
                throw ErroNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "A IA não devolveu uma divisão válida. Nada foi alterado.", "proposta_ia_invalida")
            }
            tx.executeWithoutResult { dados.concluirRegistro(registro, r.modelo, r.tokensEntrada, r.tokensSaida, BigDecimal.ZERO) }  // sem tabela de preço de linguagem ainda (ADR 0026)

            val porFaixa = atuais.associateBy { it.inicio to it.fim }
            val rascunho = r.topicos.map { p -> porFaixa[p.inicio to p.fim].let { TopicoRascunho(it?.id, p.nome.trim(), p.inicio, p.fim, it?.temConteudo ?: false) } }
            val mudou = rascunho.size != atuais.size || rascunho.zip(atuais).any { (n, a) -> n.id != a.id || n.nome != a.nome }
            return Rascunho(rascunho, mudou, r.modelo, r.tokensEntrada, r.tokensSaida)
        } finally {
            emAndamento.remove(materialId)
        }
    }

    private fun indisponivel() = ErroNegocio(HttpStatus.SERVICE_UNAVAILABLE, "O modelo de linguagem não respondeu. Tente novamente em instantes.", "ia_indisponivel")

    companion object {
        const val PREVIA = 100
    }
}
