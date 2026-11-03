package br.nexo.estudo

import br.nexo.conta.ErroNegocio
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * O que pode ser exportado: o nome do arquivo, as colunas (na ordem em que saem) e a consulta que as produz.
 * As consultas rodam com o isolamento por conta ligado, então só devolvem linhas do usuário autenticado.
 *
 * @property arquivo nome base do arquivo baixado.
 * @property colunas nomes das colunas, na ordem das colunas da consulta.
 * @property consulta SQL que devolve uma linha por registro, com as colunas na mesma ordem.
 */
enum class ConteudoExportacao(val arquivo: String, val colunas: List<String>, val consulta: String) {
    /** Uma linha por resposta dada, da mais antiga à mais nova. */
    DESEMPENHO(
        "desempenho",
        listOf("respondida_em", "assunto", "topico", "questao_id", "alternativa_escolhida", "correta", "prova_id"),
        """SELECT r.respondida_em, a.nome, t.nome, q.id, r.alternativa_escolhida, r.correta, r.prova_id
           FROM resposta r JOIN questao q ON q.id = r.questao_id JOIN topico t ON t.id = q.topico_id JOIN assunto a ON a.id = t.assunto_id
           ORDER BY r.respondida_em, r.id""",
    ),

    /** Uma linha por questão gerada. */
    QUESTOES(
        "questoes",
        listOf("assunto", "topico", "questao_id", "enunciado", "alternativas", "resposta_correta", "dificuldade", "criada_em"),
        """SELECT a.nome, t.nome, q.id, q.enunciado, q.alternativas::text, q.resposta_correta, q.dificuldade, q.criado_em
           FROM questao q JOIN topico t ON t.id = q.topico_id JOIN assunto a ON a.id = t.assunto_id
           ORDER BY a.nome, t.nome, q.criado_em, q.id""",
    ),
}

/**
 * Exportação dos dados de estudo do próprio usuário em CSV ou JSON.
 * Monta o arquivo linha a linha num arquivo temporário enquanto lê o banco, então o uso de memória não cresce com o tamanho da exportação.
 *
 * @property jdbc acesso ao banco.
 * @property tx gerenciador de transação (liga o isolamento por conta).
 * @property json conversor de JSON.
 */
@Service
class Exportacao(private val jdbc: JdbcTemplate, private val tx: TransactionTemplate, private val json: JsonMapper) {
    /**
     * Escreve a exportação na resposta HTTP.
     * O arquivo é montado primeiro num arquivo temporário, dentro de uma só transação (a leitura é consistente e não passa pela memória), e só então enviado com o tamanho certo:
     * se a leitura falhar, a resposta é um erro de verdade e não um arquivo cortado com status 200.
     * @param conteudo o que exportar.
     * @param formato `csv` ou `json`.
     * @param resposta resposta HTTP que recebe cabeçalhos e corpo.
     * @throws ErroNegocio 400 se o formato não existir; 404 se não houver dados para exportar (nada é enviado nesse caso).
     */
    fun exportar(conteudo: ConteudoExportacao, formato: String, resposta: HttpServletResponse) {
        val csv = when (formato) { "csv" -> true; "json" -> false; else -> throw ErroNegocio(HttpStatus.BAD_REQUEST, "Escolha o formato csv ou json.", "dados_invalidos") }
        val temporario = Files.createTempFile("nexo-exportacao", ".tmp")
        try {
            var linhas = 0
            Files.newOutputStream(temporario).buffered().use { saida ->
                if (csv) saida.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))  // marca UTF-8 para o Excel abrir os acentos certos
                saida.write((if (csv) conteudo.colunas.joinToString(",") + "\r\n" else "[").toByteArray())
                tx.executeWithoutResult {
                    jdbc.query({ c -> c.prepareStatement(conteudo.consulta).apply { fetchSize = 500 } }, RowCallbackHandler { rs ->  // fetchSize: lê aos poucos, sem trazer tudo para a memória
                        val valores = conteudo.colunas.indices.map { valor(rs, it + 1, conteudo.colunas[it]) }
                        if (csv) saida.write(linhaCsv(valores).toByteArray())
                        else { if (linhas > 0) saida.write(",".toByteArray()); saida.write(json.writeValueAsString(conteudo.colunas.zip(valores).toMap()).toByteArray()) }
                        linhas++
                    })
                }
                if (!csv) saida.write("]".toByteArray())
            }
            if (linhas == 0) throw ErroNegocio(HttpStatus.NOT_FOUND, "Não há dados para exportar.", "sem_dados")
            resposta.contentType = if (csv) "text/csv;charset=UTF-8" else "application/json;charset=UTF-8"
            resposta.setHeader("Content-Disposition", "attachment; filename=\"nexo-${conteudo.arquivo}.$formato\"")
            resposta.setHeader("Cache-Control", "no-store")
            resposta.setContentLengthLong(Files.size(temporario))
            Files.copy(temporario, resposta.outputStream)
            resposta.outputStream.flush()
        } finally {
            Files.deleteIfExists(temporario)
        }
    }

    /**
     * Lê uma coluna como valor simples (texto, número ou booleano); datas e identificadores viram texto e as alternativas viram lista.
     * @param rs linha atual.
     * @param i posição da coluna (a partir de 1).
     * @param coluna nome da coluna.
     * @return o valor, ou nulo.
     */
    private fun valor(rs: ResultSet, i: Int, coluna: String): Any? = when (val v = rs.getObject(i)) {
        is OffsetDateTime -> v.toString()
        is java.sql.Timestamp -> v.toInstant().toString()
        is Boolean, is Number, null -> v
        is String -> if (coluna == "alternativas") json.readTree(v).values().asSequence().map { it.asString() }.toList() else v
        else -> v.toString()
    }

    /**
     * Monta uma linha de CSV (RFC 4180, fim de linha CRLF). Textos que começam como fórmula ganham um apóstrofo, para a planilha não os executar.
     * @param valores valores da linha.
     * @return a linha pronta.
     */
    private fun linhaCsv(valores: List<Any?>): String = valores.joinToString(",", postfix = "\r\n") { v ->
        val t = when (v) { null -> ""; is List<*> -> v.joinToString(" | "); else -> v.toString() }
        val seguro = if ((v is String || v is List<*>) && t.isNotEmpty() && t[0] in "=+-@\t\r") "'$t" else t
        if (seguro.any { it in ",\"\r\n" }) "\"" + seguro.replace("\"", "\"\"") + "\"" else seguro
    }
}

/** Rotas de exportação de dados do usuário. */
@RestController
class ExportacaoController(private val exportacao: Exportacao) {
    /**
     * Baixa os dados de estudo do usuário.
     * @param conteudo `desempenho` ou `questoes`.
     * @param formato `csv` ou `json`.
     * @param resposta resposta HTTP, escrita direto pelo serviço.
     */
    @GetMapping("/exportacao")
    fun exportar(@RequestParam conteudo: String, @RequestParam(defaultValue = "csv") formato: String, resposta: HttpServletResponse) {
        val c = ConteudoExportacao.entries.firstOrNull { it.arquivo == conteudo } ?: throw ErroNegocio(HttpStatus.BAD_REQUEST, "Escolha desempenho ou questões.", "dados_invalidos")
        exportacao.exportar(c, formato, resposta)
    }
}
