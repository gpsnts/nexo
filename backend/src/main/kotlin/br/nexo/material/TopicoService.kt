package br.nexo.material

import br.nexo.NexoProperties
import br.nexo.conta.ErroNegocio
import br.nexo.seguranca.UsuarioAtual
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

data class TopicoDto(val id: UUID, val assuntoId: UUID, val nome: String, val ordem: Int, val inicio: Int?, val fim: Int?, val previa: String?, val temConteudo: Boolean = false)

/** Verdadeiro se o tópico (alias `t`) já tem resumo ou questões geradas. */
private const val TEM_CONTEUDO = "(EXISTS (SELECT 1 FROM resumo r WHERE r.topico_id = t.id) OR EXISTS (SELECT 1 FROM questao q WHERE q.topico_id = t.id))"

@Service
class TopicoService(private val jdbc: JdbcTemplate, private val dados: Dados, private val tx: TransactionTemplate, private val props: NexoProperties) {
    private fun usuario() = UsuarioAtual.id() ?: throw ErroNegocio(HttpStatus.UNAUTHORIZED, "Não autenticado.", "nao_autenticado")

    /** Trava a linha do assunto até o fim da transação: duas inserções ao mesmo tempo não calculam a mesma `ordem`. Assunto de outra conta não aparece (RLS): 404. */
    private fun travarAssunto(id: UUID) {
        jdbc.query("SELECT id FROM assunto WHERE id = ? FOR UPDATE", { rs, _ -> rs.getString(1) }, id).firstOrNull()
            ?: throw ErroNegocio(HttpStatus.NOT_FOUND, "Assunto não encontrado.", "assunto_nao_encontrado")
    }

    fun assuntoPadrao(): UUID = jdbc.query("SELECT id FROM assunto WHERE assunto_padrao", { rs, _ -> rs.getObject(1, UUID::class.java) }).firstOrNull()
        ?: throw ErroNegocio(HttpStatus.CONFLICT, "Assunto padrão não encontrado.", "assunto_padrao_ausente")

    /** UC03: tópico livre ao final do assunto. */
    fun criarLivre(nome: String, assuntoId: UUID?): TopicoDto = tx.execute {
        val assunto = assuntoId ?: assuntoPadrao()
        travarAssunto(assunto)
        val ordem = jdbc.queryForObject("SELECT coalesce(max(ordem),0)+1 FROM topico WHERE assunto_id = ?", Int::class.java, assunto)!!
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO topico(id,usuario_id,assunto_id,nome,ordem) VALUES (?,?,?,?,?)", id, usuario(), assunto, nome.trim(), ordem)
        TopicoDto(id, assunto, nome.trim(), ordem, null, null, null)
    }

    /** Cria os tópicos propostos (e as fontes) ao final do assunto. Chamado dentro da transação da indexação. */
    fun criarDaProposta(materialId: UUID, assuntoId: UUID, propostos: List<TopicoProposto>) {
        travarAssunto(assuntoId)
        var ordem = jdbc.queryForObject("SELECT coalesce(max(ordem),0) FROM topico WHERE assunto_id = ?", Int::class.java, assuntoId)!!
        propostos.forEach { p ->
            val id = UUID.randomUUID()
            jdbc.update("INSERT INTO topico(id,usuario_id,assunto_id,nome,ordem) VALUES (?,?,?,?,?)", id, usuario(), assuntoId, p.nome.trim(), ++ordem)
            inserirFonte(id, materialId, p.inicio, p.fim)
        }
    }

    private fun inserirFonte(topicoId: UUID, materialId: UUID, inicio: Int, fim: Int) {
        jdbc.update(
            "INSERT INTO topico_fonte(id,usuario_id,topico_id,material_id,ordem_trecho_inicial,ordem_trecho_final) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), usuario(), topicoId, materialId, inicio, fim,
        )
    }

    fun doMaterial(materialId: UUID): List<TopicoDto> {
        if (dados.material(materialId) == null) throw ErroNegocio(HttpStatus.NOT_FOUND, "Material não encontrado.", "nao_encontrado")
        return jdbc.query(
            """SELECT t.id, t.assunto_id, t.nome, t.ordem, f.ordem_trecho_inicial AS ini, f.ordem_trecho_final AS fim,
                      (SELECT left(conteudo, 200) FROM trecho r WHERE r.material_id = f.material_id AND r.ordem = f.ordem_trecho_inicial) AS previa,
                      $TEM_CONTEUDO AS tem_conteudo
               FROM topico t JOIN topico_fonte f ON f.topico_id = t.id WHERE f.material_id = ? ORDER BY f.ordem_trecho_inicial""",
            { rs, _ -> TopicoDto(rs.getObject("id", UUID::class.java), rs.getObject("assunto_id", UUID::class.java), rs.getString("nome"), rs.getInt("ordem"), rs.getInt("ini"), rs.getInt("fim"), rs.getString("previa"), rs.getBoolean("tem_conteudo")) },
            materialId,
        )
    }

    /**
     * Revisão da proposta (UC02 passo 6, regra 8): recebe a lista completa e aplica renomear, unir, dividir e ajustar limites numa única transação.
     * Tópicos que não vêm na lista são excluídos (unir); os sem `id` são criados (dividir). As restrições de ordem e de faixa são adiadas até o commit.
     */
    fun revisar(materialId: UUID, faixas: List<FaixaTopico>): List<TopicoDto> {
        try {
            tx.executeWithoutResult { aplicarRevisao(materialId, faixas) }
        } catch (e: DataIntegrityViolationException) {
            throw ErroNegocio(HttpStatus.CONFLICT, "Não foi possível aplicar a divisão: algum tópico removido já tem resumo ou questões.", "topico_com_conteudo")
        }
        return tx.execute { doMaterial(materialId) }
    }

    private fun aplicarRevisao(materialId: UUID, faixas: List<FaixaTopico>) {
        val m = dados.material(materialId) ?: throw ErroNegocio(HttpStatus.NOT_FOUND, "Material não encontrado.", "nao_encontrado")
        if (m.status != "pronto") throw ErroNegocio(HttpStatus.CONFLICT, "O material ainda não está pronto.", "material_nao_pronto")
        val total = dados.totalTrechos(materialId)
        Cobertura.validar(faixas, total, props.material.maxTopicos)?.let { throw ErroNegocio(HttpStatus.UNPROCESSABLE_ENTITY, it, "cobertura_invalida") }

        class Atual(val assunto: UUID, val nome: String, val inicio: Int, val fim: Int, val temConteudo: Boolean)
        val linhas = jdbc.query(
            "SELECT t.id, t.assunto_id, t.nome, f.ordem_trecho_inicial AS ini, f.ordem_trecho_final AS fim, $TEM_CONTEUDO AS tem_conteudo FROM topico t JOIN topico_fonte f ON f.topico_id = t.id WHERE f.material_id = ?",
            { rs, _ -> rs.getObject("id", UUID::class.java) to Atual(rs.getObject("assunto_id", UUID::class.java), rs.getString("nome"), rs.getInt("ini"), rs.getInt("fim"), rs.getBoolean("tem_conteudo")) }, materialId,
        ).toMap()
        val atuais = linhas.mapValues { it.value.assunto }
        val ids = faixas.mapNotNull { it.id }
        if (ids.size != ids.toSet().size) throw ErroNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "Tópico repetido na lista.", "cobertura_invalida")
        if (!atuais.keys.containsAll(ids)) throw ErroNegocio(HttpStatus.NOT_FOUND, "Tópico não encontrado neste material.", "nao_encontrado")
        // Tópico com resumo ou questões: renomear pode, mas limites e existência ficam congelados (ADR 0028). O conteúdo gerado cita os trechos daquela faixa.
        val removidos = (atuais.keys - ids.toSet()).filter { linhas.getValue(it).temConteudo }
        val movidos = faixas.filter { it.id != null && linhas.getValue(it.id).let { a -> a.temConteudo && (a.inicio != it.inicio || a.fim != it.fim) } }.mapNotNull { it.id }
        (removidos + movidos).firstOrNull()?.let {
            throw ErroNegocio(HttpStatus.CONFLICT, "O tópico \"${linhas.getValue(it).nome}\" já tem resumo ou questões: os limites dele não podem mudar nem ele pode ser unido ou removido. Renomear é permitido.", "topico_com_conteudo")
        }
        val assunto = atuais.values.toSet().singleOrNull() ?: throw ErroNegocio(HttpStatus.CONFLICT, "Os tópicos do material estão em assuntos diferentes.", "assuntos_diferentes")
        travarAssunto(assunto)

        // Ordem final no assunto: os demais tópicos ficam onde estavam; os do material ocupam, juntos, o lugar do primeiro deles.
        val doAssunto = jdbc.query("SELECT id FROM topico WHERE assunto_id = ? ORDER BY ordem", { rs, _ -> rs.getObject(1, UUID::class.java) }, assunto)
        val novos = faixas.map { it.copy(id = it.id ?: UUID.randomUUID()) }
        val sequencia = mutableListOf<UUID>()
        var inserido = false
        doAssunto.forEach { id ->
            if (id in atuais) {
                if (!inserido) { sequencia += novos.map { it.id!! }; inserido = true }
            } else sequencia += id
        }

        // Fontes e tópicos: atualiza, cria e remove. Nada é conferido até o commit (restrições DEFERRABLE).
        val remover = atuais.keys - ids.toSet()
        remover.forEach { id ->
            jdbc.update("DELETE FROM topico_fonte WHERE topico_id = ? AND material_id = ?", id, materialId)
            jdbc.update("DELETE FROM topico WHERE id = ?", id)
        }
        novos.forEach { f ->
            if (f.id in atuais) {
                jdbc.update("UPDATE topico SET nome = ? WHERE id = ?", f.nome.trim(), f.id)
                jdbc.update("UPDATE topico_fonte SET ordem_trecho_inicial = ?, ordem_trecho_final = ? WHERE topico_id = ? AND material_id = ?", f.inicio, f.fim, f.id, materialId)
            } else {
                jdbc.update("INSERT INTO topico(id,usuario_id,assunto_id,nome,ordem) VALUES (?,?,?,?,?)", f.id, usuario(), assunto, f.nome.trim(), sequencia.indexOf(f.id) + 1)
                inserirFonte(f.id!!, materialId, f.inicio, f.fim)
            }
        }
        sequencia.forEachIndexed { i, id -> jdbc.update("UPDATE topico SET ordem = ? WHERE id = ? AND ordem <> ?", i + 1, id, i + 1) }
    }
}
