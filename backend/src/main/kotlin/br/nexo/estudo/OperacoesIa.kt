package br.nexo.estudo

import br.nexo.conta.ErroNegocio
import br.nexo.material.Acumulado
import br.nexo.material.FalhaProcessamento
import br.nexo.material.LimitesIa
import br.nexo.material.MensagensIa
import br.nexo.seguranca.UsuarioAtual
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Registro de operação com IA de chamada síncrona (dica, chat, nome do estudo).
 * O registro **nasce antes da chamada ao modelo**, na mesma transação que confere os limites (e com a trava por usuário de `LimitesIa`): chamadas
 * simultâneas se enxergam umas às outras, e uma queda no meio da chamada não deixa gasto sem registro. Registro aberto já reserva o mínimo da cota.
 */
@Service
class OperacoesIa(private val jdbc: JdbcTemplate, private val limites: LimitesIa, private val tx: TransactionTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Deve ser chamado DENTRO de uma transação: confere os limites e abre o registro (`em_andamento`).
     * @param operacao tipo da operação (dica, chat, nome_estudo).
     * @param modelo nome do modelo de linguagem.
     * @param topicoId tópico, se houver.
     * @param materialId material, se houver.
     * @param livre se a entrada é um tópico livre.
     * @return id do registro aberto.
     * @throws ErroNegocio se algum limite foi atingido.
     */
    fun abrir(operacao: String, modelo: String, topicoId: UUID?, materialId: UUID?, livre: Boolean): UUID {
        limites.exigirDisponivel()
        val id = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO registro_geracao(id,usuario_id,topico_id,material_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual)
               VALUES (?,?,?,?,?,?,?,'em_andamento',0,0,0,0,0,0,0)""",
            id, UsuarioAtual.id(), topicoId, materialId, operacao, if (livre) "topico_livre" else "material", modelo,
        )
        return id
    }

    /**
     * Fecha o registro como concluído, com tokens e custo. Dentro de transação (a do próprio resultado).
     * @param id registro.
     * @param modelo modelo usado.
     * @param a tokens e custo acumulados.
     */
    fun concluir(id: UUID, modelo: String, a: Acumulado) {
        jdbc.update(
            "UPDATE registro_geracao SET status='concluida', progresso_percentual=100, modelo_ia=?, tokens_entrada=?, tokens_saida=?, custo_estimado_usd=?, finalizada_em=now() WHERE id=? AND status IN ('pendente','em_andamento')",
            modelo, a.tokensEntrada, a.tokensSaida, a.custo, id,
        )
    }

    /**
     * Fecha o registro como falho, com o que já foi gasto.
     * @param id registro.
     * @param modelo modelo usado.
     * @param a tokens e custo acumulados.
     * @param motivo motivo da falha.
     */
    private fun falhar(id: UUID, modelo: String, a: Acumulado, motivo: String) {
        jdbc.update(
            "UPDATE registro_geracao SET status='falhou', motivo_falha=?, modelo_ia=?, tokens_entrada=?, tokens_saida=?, custo_estimado_usd=?, finalizada_em=now() WHERE id=? AND status IN ('pendente','em_andamento')",
            motivo, modelo, a.tokensEntrada, a.tokensSaida, a.custo, id,
        )
    }

    /**
     * Roda a chamada ao modelo (fora de transação) e, se falhar de qualquer jeito, fecha o registro como `falhou` com o que já gastou. Falha do serviço de IA vira 503 `ia_indisponivel`; qualquer outra exceção segue para o tratador de erros (500), mas o registro já está fechado.
     * @param id registro aberto.
     * @param modelo modelo usado.
     * @param a tokens e custo acumulados.
     * @param operacao nome da operação, para o log.
     * @param bloco a chamada ao serviço de IA.
     * @return o resultado do bloco.
     * @throws ErroNegocio 503 `ia_indisponivel` se o serviço de IA falhar.
     */
    fun <T> chamar(id: UUID, modelo: String, a: Acumulado, operacao: String, bloco: () -> T): T = try {
        bloco()
    } catch (e: ErroNegocio) {
        runCatching { tx.executeWithoutResult { falhar(id, modelo, a, "erro_interno") } }
        throw e
    } catch (e: Exception) {
        if (e is FalhaProcessamento) log.warn("{} falhou: {}", operacao, e.message) else log.error("{} falhou", operacao, e)
        runCatching { tx.executeWithoutResult { falhar(id, modelo, a, if (e is FalhaProcessamento) "falha_ia" else "erro_interno") } }
            .onFailure { log.error("Não foi possível fechar o registro de {}", operacao, it) }
        throw if (e is FalhaProcessamento) ErroNegocio(HttpStatus.SERVICE_UNAVAILABLE, MensagensIa.deCodigo(e.codigo), "ia_indisponivel") else e
    }
}
