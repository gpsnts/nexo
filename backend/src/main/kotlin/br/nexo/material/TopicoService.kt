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

/**
 * Tópico devolvido pela API.
 * @property id identificador.
 * @property assuntoId pasta.
 * @property nome nome do tópico.
 * @property ordem posição na pasta.
 * @property inicio ordem do primeiro trecho (nulo em tópico livre).
 * @property fim ordem do último trecho.
 * @property previa início do texto do primeiro trecho.
 * @property temConteudo se já tem resumo ou questões (congelado).
 */
data class TopicoDto(val id: UUID, val assuntoId: UUID, val nome: String, val ordem: Int, val inicio: Int?, val fim: Int?, val previa: String?, val temConteudo: Boolean = false)

/** Verdadeiro se o tópico (alias `t`) já tem resumo ou questões geradas. */
private const val TEM_CONTEUDO = "(EXISTS (SELECT 1 FROM resumo r WHERE r.topico_id = t.id) OR EXISTS (SELECT 1 FROM questao q WHERE q.topico_id = t.id))"

/** Tópicos: criação (livre ou pela proposta), listagem por material e revisão da divisão (renomear, unir, dividir, ajustar limites). */
@Service
class TopicoService(private val jdbc: JdbcTemplate, private val dados: Dados, private val tx: TransactionTemplate, private val props: NexoProperties) {
    /**
     * Usuário autenticado.
     * @return id do usuário do JWT.
     * @throws ErroNegocio 401 se não houver usuário.
     */
    private fun usuario() = UsuarioAtual.id() ?: throw ErroNegocio(HttpStatus.UNAUTHORIZED, "Não autenticado.", "nao_autenticado")

    /**
     * Trava a linha do assunto até o fim da transação: duas inserções ao mesmo tempo não calculam a mesma `ordem`. Assunto de outra conta não aparece (RLS): 404.
     * @param id assunto.
     * @throws ErroNegocio 404 se o assunto não existir.
     */
    private fun travarAssunto(id: UUID) {
        jdbc.query("SELECT id FROM assunto WHERE id = ? FOR UPDATE", { rs, _ -> rs.getString(1) }, id).firstOrNull()
            ?: throw ErroNegocio(HttpStatus.NOT_FOUND, "Assunto não encontrado.", "assunto_nao_encontrado")
    }

    /**
     * Assunto padrão do usuário.
     * @return id do "Geral".
     * @throws ErroNegocio 409 se não existir.
     */
    fun assuntoPadrao(): UUID = jdbc.query("SELECT id FROM assunto WHERE assunto_padrao", { rs, _ -> rs.getObject(1, UUID::class.java) }).firstOrNull()
        ?: throw ErroNegocio(HttpStatus.CONFLICT, "Assunto padrão não encontrado.", "assunto_padrao_ausente")

    /**
     * tópico livre ao final do assunto.
     * @param nome nome do tópico.
     * @param assuntoId pasta, ou nulo para o "Geral".
     * @param orientacao orientação opcional do estudante (foco, banca, estilo); em branco vale como ausente.
     * @return o tópico criado.
     * @throws ErroNegocio 404 se o assunto não existir; 400 se a orientação passar do limite.
     */
    fun criarLivre(nome: String, assuntoId: UUID?, orientacao: String? = null): TopicoDto = tx.execute {
        val texto = limparOrientacao(orientacao)
        val assunto = assuntoId ?: assuntoPadrao()
        travarAssunto(assunto)
        val ordem = jdbc.queryForObject("SELECT coalesce(max(ordem),0)+1 FROM topico WHERE assunto_id = ?", Int::class.java, assunto)!!
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO topico(id,usuario_id,assunto_id,nome,ordem,orientacao) VALUES (?,?,?,?,?,?)", id, usuario(), assunto, nome.trim(), ordem, texto)
        TopicoDto(id, assunto, nome.trim(), ordem, null, null, null)
    }!!

    /**
     * Grava (ou limpa) a orientação padrão do tópico, usada nas próximas gerações.
     * @param topicoId tópico.
     * @param orientacao texto novo; em branco limpa.
     * @return a orientação gravada, ou nulo se foi limpa.
     * @throws ErroNegocio 404 se o tópico não existir; 400 se a orientação passar do limite.
     */
    fun definirOrientacao(topicoId: UUID, orientacao: String?): String? = tx.execute {
        val texto = limparOrientacao(orientacao)
        if (jdbc.update("UPDATE topico SET orientacao = ? WHERE id = ?", texto, topicoId) == 0) throw ErroNegocio(HttpStatus.NOT_FOUND, "Tópico não encontrado.", "nao_encontrado")
        texto
    }

    /**
     * Cria os tópicos propostos (e as fontes) ao final do assunto. Chamado dentro da transação da indexação.
     * @param materialId material de origem.
     * @param assuntoId pasta dos tópicos.
     * @param propostos faixas e nomes propostos.
     */
    fun criarDaProposta(materialId: UUID, assuntoId: UUID, propostos: List<TopicoProposto>) {
        travarAssunto(assuntoId)
        var ordem = jdbc.queryForObject("SELECT coalesce(max(ordem),0) FROM topico WHERE assunto_id = ?", Int::class.java, assuntoId)!!
        propostos.forEach { p ->
            val id = UUID.randomUUID()
            jdbc.update("INSERT INTO topico(id,usuario_id,assunto_id,nome,ordem) VALUES (?,?,?,?,?)", id, usuario(), assuntoId, p.nome.trim(), ++ordem)
            inserirFonte(id, materialId, p.inicio, p.fim)
        }
    }

    /**
     * Liga um tópico a uma faixa de trechos de um material.
     * @param topicoId tópico.
     * @param materialId material.
     * @param inicio ordem do primeiro trecho.
     * @param fim ordem do último trecho.
     */
    private fun inserirFonte(topicoId: UUID, materialId: UUID, inicio: Int, fim: Int) {
        jdbc.update(
            "INSERT INTO topico_fonte(id,usuario_id,topico_id,material_id,ordem_trecho_inicial,ordem_trecho_final) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), usuario(), topicoId, materialId, inicio, fim,
        )
    }

    /**
     * Lista os tópicos de um material, por ordem de trecho.
     * @param materialId material.
     * @return os tópicos, com prévia e se já têm conteúdo.
     * @throws ErroNegocio 404 se o material não existir.
     */
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
     * Revisão da proposta: recebe a lista completa e aplica renomear, unir, dividir e ajustar limites numa única transação. Tópicos que não vêm na lista são excluídos (unir); os sem `id` são criados (dividir). As restrições de ordem e de faixa são adiadas até o commit.
     * @param materialId material.
     * @param faixas lista completa de tópicos com suas faixas.
     * @return os tópicos após a revisão.
     * @throws ErroNegocio 409 se mexer em tópico congelado; 422 se a cobertura for inválida; 404 se o material não existir.
     */
    fun revisar(materialId: UUID, faixas: List<FaixaTopico>): List<TopicoDto> {
        try {
            tx.executeWithoutResult { aplicarRevisao(materialId, faixas) }
        } catch (e: DataIntegrityViolationException) {
            throw ErroNegocio(HttpStatus.CONFLICT, "Não foi possível aplicar a divisão: algum tópico removido já tem resumo ou questões.", "topico_com_conteudo")
        }
        return tx.execute { doMaterial(materialId) }
    }

    /**
     * Aplica a revisão dentro da transação, validando antes de gravar.
     * @param materialId material.
     * @param faixas lista completa de tópicos com suas faixas.
     */
    private fun aplicarRevisao(materialId: UUID, faixas: List<FaixaTopico>) {
        val m = dados.material(materialId) ?: throw ErroNegocio(HttpStatus.NOT_FOUND, "Material não encontrado.", "nao_encontrado")
        if (m.status != "pronto") throw ErroNegocio(HttpStatus.CONFLICT, "O material ainda não está pronto.", "material_nao_pronto")
        val total = dados.totalTrechos(materialId)
        Cobertura.validar(faixas, total, props.material.maxTopicos)?.let { throw ErroNegocio(HttpStatus.UNPROCESSABLE_ENTITY, it, "cobertura_invalida") }

        /**
         * Tópico atual lido do banco.
         * @property assunto pasta.
         * @property nome nome.
         * @property inicio primeiro trecho.
         * @property fim último trecho.
         * @property temConteudo se está congelado.
         */
        class Atual(val assunto: UUID, val nome: String, val inicio: Int, val fim: Int, val temConteudo: Boolean)
        val linhas = jdbc.query(
            "SELECT t.id, t.assunto_id, t.nome, f.ordem_trecho_inicial AS ini, f.ordem_trecho_final AS fim, $TEM_CONTEUDO AS tem_conteudo FROM topico t JOIN topico_fonte f ON f.topico_id = t.id WHERE f.material_id = ?",
            { rs, _ -> rs.getObject("id", UUID::class.java) to Atual(rs.getObject("assunto_id", UUID::class.java), rs.getString("nome"), rs.getInt("ini"), rs.getInt("fim"), rs.getBoolean("tem_conteudo")) }, materialId,
        ).toMap()
        val atuais = linhas.mapValues { it.value.assunto }
        val ids = faixas.mapNotNull { it.id }
        if (ids.size != ids.toSet().size) throw ErroNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "Tópico repetido na lista.", "cobertura_invalida")
        if (!atuais.keys.containsAll(ids)) throw ErroNegocio(HttpStatus.NOT_FOUND, "Tópico não encontrado neste material.", "nao_encontrado")
        // Tópico com resumo ou questões: renomear pode, mas limites e existência ficam congelados. O conteúdo gerado cita os trechos daquela faixa.
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

    companion object {
    /**
     * Normaliza a orientação do estudante.
     * @param orientacao texto digitado, ou nulo.
     * @return o texto sem espaços nas pontas, ou nulo se estiver vazio.
     * @throws ErroNegocio 400 se passar de [ORIENTACAO_MAX] caracteres.
     */
    fun limparOrientacao(orientacao: String?): String? {
        val texto = orientacao?.trim()?.ifEmpty { null } ?: return null
        if (texto.length > ORIENTACAO_MAX) throw ErroNegocio(HttpStatus.BAD_REQUEST, "A orientação pode ter até $ORIENTACAO_MAX caracteres.", "dados_invalidos")
        return texto
    }

        /** Tamanho máximo da orientação do estudante, igual ao CHECK da coluna `topico.orientacao`. */
        const val ORIENTACAO_MAX = 500
    }
}
