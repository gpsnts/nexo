package br.nexo.material

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Linha da tabela `material`, com a pasta do estudo.
 * @property id identificador.
 * @property nomeArquivo nome original.
 * @property tipoArquivo MIME do arquivo.
 * @property tamanhoBytes tamanho.
 * @property paginas páginas, se houver.
 * @property hash SHA-256 do arquivo.
 * @property caminho chave no armazenamento.
 * @property status pendente, processando, pronto ou falhou.
 * @property motivoFalha motivo, se falhou.
 * @property criadoEm criação.
 * @property atualizadoEm última atualização.
 * @property titulo nome dado ao estudo.
 * @property assuntoId pasta dos tópicos.
 */
data class MaterialLinha(
    val id: UUID, val nomeArquivo: String, val tipoArquivo: String, val tamanhoBytes: Long, val paginas: Int?, val hash: String,
    val caminho: String, val status: String, val motivoFalha: String?, val criadoEm: Instant, val atualizadoEm: Instant,
    val titulo: String? = null, val assuntoId: UUID? = null,
)

/**
 * Registro de operação com IA ligado a um material.
 * @property id identificador.
 * @property operacao tipo da operação.
 * @property status status do registro.
 * @property progresso 0 a 100.
 * @property atualizadoEm última atualização (pulso).
 */
data class RegistroLinha(val id: UUID, val operacao: String, val status: String, val progresso: Int, val atualizadoEm: Instant)

/**
 * Acesso por JDBC, sempre dentro de uma transação gerenciada (que põe app.usuario_id): a RLS filtra por conta, então nenhuma consulta
 * repete `usuario_id` no WHERE. JDBC e não JPA porque as gravações de trecho usam `vector`, as faixas dependem de restrições adiadas
 * e misturar os dois estilos na mesma transação muda a ordem das gravações.
 */
@Repository
class Dados(val jdbc: JdbcTemplate) {
    /**
     * Converte uma linha do ResultSet em [MaterialLinha].
     * @param rs resultado posicionado na linha.
     * @param n número da linha (não usado).
     * @return a linha convertida.
     */
    private fun material(rs: java.sql.ResultSet, n: Int) = MaterialLinha(
        rs.getObject("id", UUID::class.java), rs.getString("nome_arquivo"), rs.getString("tipo_arquivo"), rs.getLong("tamanho_bytes"),
        rs.getObject("quantidade_paginas") as Int?, rs.getString("hash_arquivo"), rs.getString("caminho_arquivo"), rs.getString("status_processamento"),
        rs.getString("motivo_falha"), rs.getTimestamp("criado_em").toInstant(), rs.getTimestamp("atualizado_em").toInstant(),
        rs.getString("titulo"), rs.getObject("assunto_id", UUID::class.java),
    )

    /** A pasta do estudo é a dos seus tópicos (movem juntos); nula enquanto o material ainda não tem tópicos. */
    private val sel = "SELECT m.*, (SELECT t.assunto_id FROM topico_fonte f JOIN topico t ON t.id = f.topico_id WHERE f.material_id = m.id ORDER BY t.ordem LIMIT 1) AS assunto_id FROM material m"

    /**
     * Busca um material.
     * @param id material.
     * @return o material, ou nulo se não for do usuário (RLS).
     */
    fun material(id: UUID): MaterialLinha? = jdbc.query("$sel WHERE m.id = ?", ::material, id).firstOrNull()
    /**
     * Lista os materiais, do mais novo ao mais antigo.
     * @param limite máximo de itens.
     * @return os materiais do usuário.
     */
    fun materiais(limite: Int): List<MaterialLinha> = jdbc.query("$sel ORDER BY m.criado_em DESC LIMIT ?", ::material, limite)
    /**
     * Busca um material do mesmo arquivo que não tenha falhado.
     * @param hash SHA-256 do arquivo.
     * @return o material, ou nulo.
     */
    fun materialAtivoPorHash(hash: String): MaterialLinha? = jdbc.query("$sel WHERE m.hash_arquivo = ? AND m.status_processamento <> 'falhou' LIMIT 1", ::material, hash).firstOrNull()
    /**
     * Materiais do mesmo arquivo que falharam (removidos no reenvio).
     * @param hash SHA-256 do arquivo.
     * @return os materiais com status falhou.
     */
    fun materiaisPorHashFalhos(hash: String): List<MaterialLinha> = jdbc.query("$sel WHERE m.hash_arquivo = ? AND m.status_processamento = 'falhou'", ::material, hash)
    /**
     * Conta os materiais ainda pendentes ou processando.
     * @return quantidade em processamento.
     */
    fun emProcessamento(): Int = jdbc.queryForObject("SELECT count(*) FROM material WHERE status_processamento IN ('pendente','processando')", Int::class.java)!!

    /**
     * Insere um material pendente.
     * @param id identificador gerado pelo backend.
     * @param usuario dono.
     * @param m dados do material.
     */
    fun inserirMaterial(id: UUID, usuario: UUID, m: MaterialLinha) {
        jdbc.update(
            "INSERT INTO material(id,usuario_id,nome_arquivo,tipo_arquivo,tamanho_bytes,quantidade_paginas,hash_arquivo,caminho_arquivo,status_processamento) VALUES (?,?,?,?,?,?,?,?,'pendente')",
            id, usuario, m.nomeArquivo, m.tipoArquivo, m.tamanhoBytes, m.paginas, m.hash, m.caminho,
        )
    }

    /**
     * Atualiza o status do material.
     * @param id material.
     * @param status novo status.
     * @param motivo motivo da falha, se houver.
     */
    fun definirStatus(id: UUID, status: String, motivo: String? = null) {
        jdbc.update("UPDATE material SET status_processamento = ?, motivo_falha = ? WHERE id = ?", status, motivo, id)
    }

    /**
     * Rotina de remoção do material (exclusão de material e reenvio após falha), a ser chamada dentro de uma transação.
     * @param id material.
     * @return o caminho do arquivo para remoção depois do commit, ou nulo se o material não existir.
     */
    fun excluirMaterial(id: UUID): String? {
        val caminho = jdbc.query("SELECT caminho_arquivo FROM material WHERE id = ?", { rs, _ -> rs.getString(1) }, id).firstOrNull() ?: return null
        jdbc.update("UPDATE registro_geracao SET material_id = NULL WHERE material_id = ?", id)
        jdbc.update("DELETE FROM material WHERE id = ?", id)  // fontes, trechos e questao_trecho saem em cascata
        return caminho
    }

    // ---- trechos
    /**
     * Conta os trechos do material.
     * @param materialId material.
     * @return quantidade de trechos.
     */
    fun totalTrechos(materialId: UUID): Int = jdbc.queryForObject("SELECT count(*) FROM trecho WHERE material_id = ?", Int::class.java, materialId)!!

    /**
     * Página de cada trecho, na ordem (posição 0 = trecho 1); nulo em DOCX e TXT. A tela usa para mostrar "págs. 1 a 14" nos tópicos.
     * @param materialId material.
     * @return números de página.
     */
    fun paginasDosTrechos(materialId: UUID): List<Int?> =
        jdbc.query("SELECT numero_pagina FROM trecho WHERE material_id = ? ORDER BY ordem", { rs, _ -> rs.getObject(1) as Int? }, materialId)

    /**
     * Grava os trechos de um lote com seus embeddings.
     * @param usuario dono.
     * @param materialId material.
     * @param modelo modelo de embedding.
     * @param trechos trechos do lote.
     * @param vetores um vetor por trecho.
     */
    fun inserirTrechos(usuario: UUID, materialId: UUID, modelo: String, trechos: List<TrechoExtraido>, vetores: List<List<Double>>) {
        jdbc.batchUpdate(
            "INSERT INTO trecho(id,usuario_id,material_id,ordem,numero_pagina,tipo_conteudo,conteudo,embedding,modelo_embedding) VALUES (?,?,?,?,?,'texto',?,?::vector,?)",
            trechos.indices.toList(), 100,
        ) { ps, i ->
            val t = trechos[i]
            ps.setObject(1, UUID.randomUUID()); ps.setObject(2, usuario); ps.setObject(3, materialId); ps.setInt(4, t.ordem)
            ps.setObject(5, t.numeroPagina); ps.setString(6, t.conteudo); ps.setString(7, vetores[i].joinToString(",", "[", "]")); ps.setString(8, modelo)
        }
    }

    /**
     * Prévia de um trecho.
     * @property ordem posição no material.
     * @property numeroPagina página, se houver.
     * @property previa início do texto.
     */
    data class TrechoPrevia(val ordem: Int, val numeroPagina: Int?, val previa: String)

    /**
     * Prévia dos trechos de um intervalo.
     * @param materialId material.
     * @param de primeira ordem.
     * @param ate última ordem.
     * @param previa máximo de caracteres de cada prévia.
     * @return os trechos do intervalo.
     */
    fun trechos(materialId: UUID, de: Int, ate: Int, previa: Int): List<TrechoPrevia> = jdbc.query(
        "SELECT ordem, numero_pagina, left(conteudo, ?) AS previa FROM trecho WHERE material_id = ? AND ordem BETWEEN ? AND ? ORDER BY ordem",
        { rs, _ -> TrechoPrevia(rs.getInt("ordem"), rs.getObject("numero_pagina") as Int?, rs.getString("previa")) }, previa, materialId, de, ate,
    )

    // ---- registro_geracao
    /**
     * Cria um registro de operação em andamento.
     * @param id identificador gerado pelo backend.
     * @param usuario dono.
     * @param materialId material.
     * @param operacao tipo da operação.
     * @param modelo modelo gravado.
     * @param progresso progresso inicial.
     */
    fun criarRegistro(id: UUID, usuario: UUID, materialId: UUID, operacao: String, modelo: String, progresso: Int) {
        jdbc.update(
            """INSERT INTO registro_geracao(id,usuario_id,material_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual)
               VALUES (?,?,?,?,'material',?,'em_andamento',0,0,0,0,0,0,?)""",
            id, usuario, materialId, operacao, modelo, progresso,
        )
    }

    /**
     * Nunca chega a 100 por aqui: 100 só quando concluída (CHECK do banco).
     * @param id registro.
     * @param pct percentual (limitado a 0..99).
     */
    fun progresso(id: UUID, pct: Int) {
        jdbc.update("UPDATE registro_geracao SET progresso_percentual = ? WHERE id = ? AND status = 'em_andamento'", pct.coerceIn(0, 99), id)
    }

    /**
     * Conclui o registro com tokens e custo.
     * @param id registro.
     * @param modelo modelo usado.
     * @param tokensEntrada tokens do prompt.
     * @param tokensSaida tokens gerados.
     * @param custo custo estimado em US$.
     */
    fun concluirRegistro(id: UUID, modelo: String, tokensEntrada: Int, tokensSaida: Int, custo: java.math.BigDecimal) {
        jdbc.update(
            "UPDATE registro_geracao SET status='concluida', progresso_percentual=100, modelo_ia=?, tokens_entrada=?, tokens_saida=?, custo_estimado_usd=?, finalizada_em=now() WHERE id=?",
            modelo, tokensEntrada, tokensSaida, custo, id,
        )
    }

    /**
     * Registro que chamou o modelo mas não deu resultado aproveitável: os tokens gastos ficam registrados.
     * @param id registro.
     * @param modelo modelo usado.
     * @param tokensEntrada tokens do prompt.
     * @param tokensSaida tokens gerados.
     * @param motivo código do motivo.
     * @param custo custo estimado em US$.
     */
    fun falharRegistro(id: UUID, modelo: String, tokensEntrada: Int, tokensSaida: Int, motivo: String? = null, custo: java.math.BigDecimal = java.math.BigDecimal.ZERO) {
        jdbc.update("UPDATE registro_geracao SET status='falhou', motivo_falha=?, modelo_ia=?, tokens_entrada=?, tokens_saida=?, custo_estimado_usd=?, finalizada_em=now() WHERE id=?", motivo, modelo, tokensEntrada, tokensSaida, custo, id)
    }

    /**
     * Resumo de cada trecho para o modelo (a tela de revisão não precisa do texto inteiro): ordem e início do texto. O nível de título não é guardado no banco.
     * @param materialId material.
     * @param previa máximo de caracteres do início.
     * @return um resumo por trecho.
     */
    fun resumosDosTrechos(materialId: UUID, previa: Int): List<ResumoTrecho> =
        jdbc.query("SELECT ordem, left(conteudo, ?) AS inicio FROM trecho WHERE material_id = ? ORDER BY ordem", { rs, _ -> ResumoTrecho(rs.getInt("ordem"), null, null, rs.getString("inicio")) }, previa, materialId)

    /**
     * Falha todos os registros ainda abertos do material (indexação e sugestão).
     * @param materialId material.
     * @param motivo código do motivo.
     */
    fun falharRegistrosAbertos(materialId: UUID, motivo: String? = null) {
        jdbc.update("UPDATE registro_geracao SET status='falhou', motivo_falha=?, finalizada_em=now() WHERE material_id = ? AND status IN ('pendente','em_andamento')", motivo, materialId)
    }

    /**
     * Registros de operação do material, do mais antigo ao mais novo.
     * @param materialId material.
     * @return os registros.
     */
    fun registros(materialId: UUID): List<RegistroLinha> = jdbc.query(
        "SELECT id, operacao, status, progresso_percentual, atualizado_em FROM registro_geracao WHERE material_id = ? ORDER BY criado_em",
        { rs, _ -> RegistroLinha(rs.getObject("id", UUID::class.java), rs.getString("operacao"), rs.getString("status"), rs.getInt("progresso_percentual"), rs.getTimestamp("atualizado_em").toInstant()) }, materialId,
    )
}
