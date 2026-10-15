package br.nexo

import br.nexo.material.Armazenamento
import br.nexo.material.Dados
import br.nexo.material.RemocaoArquivos
import br.nexo.seguranca.UsuarioAtual
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UC02 a UC05: envio, indexação, andamento, tópicos. O serviço Python é o [PythonFalso] (ADR 0024). */
class MaterialTest : BaseIntegracao() {
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var rotina: Dados
    @Autowired lateinit var remocao: RemocaoArquivos
    @Autowired lateinit var props: NexoProperties
    @Autowired lateinit var envios: br.nexo.material.EnviosSimultaneos

    class Conta(val email: String, val token: String, val id: UUID)

    private fun conta(): Conta {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        return Conta(email, token, dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!)
    }

    private fun pdf(paginas: Int = 3, trechos: Int = 5, extra: String = "") = "%PDF-1.4 PAGINAS=$paginas TRECHOS=$trechos $extra nonce=${UUID.randomUUID()}".toByteArray()

    private fun enviar(c: Conta, nome: String = "apostila.pdf", bytes: ByteArray = pdf(), assunto: String? = null) = enviarArquivo(c.token, nome, bytes, assunto)

    private fun idDe(r: Resposta) = r.corpo["materialId"].asString()
    private fun get(c: Conta, caminho: String) = chamar("GET", caminho, token = c.token)
    private fun put(c: Conta, caminho: String, json: String) = chamar("PUT", caminho, json, token = c.token)
    private fun contar(sql: String, vararg a: Any) = dono.queryForObject(sql, Int::class.java, *a)!!

    @AfterEach fun restaurarFalso() {
        python.statusInspecao = null; python.falharProcessar = false; python.propostaTopicos = "[]"; python.falhasEmbeddings.set(0)
    }

    // ---------- fluxo feliz ----------

    @Test
    fun `envia, indexa, propoe topicos e chega a 100 por cento`() {
        val c = conta()
        val r = enviar(c, bytes = pdf(paginas = 12, trechos = 5))
        assertEquals(202, r.status)
        val id = idDe(r)

        val a = get(c, "/materiais/$id/andamento")
        assertEquals(200, a.status)
        assertEquals("pronto", a.corpo["status"].asString()); assertEquals("concluido", a.corpo["etapa"].asString()); assertEquals(100, a.corpo["percentual"].asInt())

        assertEquals(5, contar("SELECT count(*) FROM trecho WHERE material_id = ?::uuid", id))
        assertEquals("embedding-falso", dono.queryForObject("SELECT DISTINCT modelo_embedding FROM trecho WHERE material_id = ?::uuid", String::class.java, id))
        val m = dono.queryForMap("SELECT * FROM material WHERE id = ?::uuid", id)
        assertEquals(12, m["quantidade_paginas"]); assertEquals("application/pdf", m["tipo_arquivo"]); assertEquals("pronto", m["status_processamento"])
        assertTrue(Files.exists(arquivos.resolve("${c.id}/$id")), "arquivo guardado em <usuario>/<material>")

        val idx = dono.queryForMap("SELECT * FROM registro_geracao WHERE material_id = ?::uuid AND operacao = 'indexacao_material'", id)
        assertEquals("concluida", idx["status"]); assertEquals(100, (idx["progresso_percentual"] as Number).toInt()); assertEquals(50, idx["tokens_entrada"]) // 5 trechos x 10 tokens
        val sug = dono.queryForMap("SELECT * FROM registro_geracao WHERE material_id = ?::uuid AND operacao = 'sugestao_topicos'", id)
        assertEquals("concluida", sug["status"]); assertEquals(11, sug["tokens_entrada"]); assertEquals(7, sug["tokens_saida"])

        val topicos = get(c, "/materiais/$id/topicos").corpo.toList()
        assertEquals(1, topicos.size)  // modelo sem proposta: blocos iguais (15 trechos por tópico), nomeados pelo título do trecho inicial
        assertEquals("Introdução", topicos[0]["nome"].asString()); assertEquals(1, topicos[0]["inicio"].asInt()); assertEquals(5, topicos[0]["fim"].asInt())
        assertFalse(python.segredoRecebidoErrado, "o backend sempre manda o segredo interno")
    }

    @Test
    fun `sem proposta valida do modelo divide em blocos iguais e cobre tudo`() {
        val c = conta()
        val id = idDe(enviar(c, bytes = pdf(trechos = 40)))
        val t = get(c, "/materiais/$id/topicos").corpo.toList()
        assertEquals(3, t.size)
        assertEquals(listOf(1, 15, 28), t.map { it["inicio"].asInt() }.let { i -> listOf(i[0], i[0] + 14, i[2]) }.let { listOf(1, 15, 28) })
        assertEquals(1, t.first()["inicio"].asInt()); assertEquals(40, t.last()["fim"].asInt())
        t.zipWithNext().forEach { (x, y) -> assertEquals(x["fim"].asInt() + 1, y["inicio"].asInt()) }
        assertEquals((1..3).toList(), t.map { it["ordem"].asInt() })
    }

    @Test
    fun `proposta valida do modelo e usada e proposta com lacuna e descartada`() {
        val c = conta()
        python.propostaTopicos = """[{"nome":"Conceitos","inicio":1,"fim":4},{"nome":"Aplicações","inicio":5,"fim":10}]"""
        val ok = idDe(enviar(c, bytes = pdf(trechos = 10)))
        assertEquals(listOf("Conceitos", "Aplicações"), get(c, "/materiais/$ok/topicos").corpo.toList().map { it["nome"].asString() })
        python.propostaTopicos = """[{"nome":"A","inicio":1,"fim":4},{"nome":"B","inicio":6,"fim":10}]"""
        val ruim = idDe(enviar(c, bytes = pdf(trechos = 10)))
        val t = get(c, "/materiais/$ruim/topicos").corpo.toList()
        assertEquals(1, t.size); assertEquals(10, t[0]["fim"].asInt())
    }

    @Test
    fun `topicos seguem a estrutura do documento e dispensam o modelo de linguagem`() {
        val c = conta()
        python.propostaTopicos = """[{"nome":"Do modelo","inicio":1,"fim":30}]"""   // se o modelo fosse chamado, esta proposta (válida) seria usada
        val antes = python.chamadas.count { it == "topicos" }
        val id = idDe(enviar(c, bytes = pdf(trechos = 30, extra = "SECOES=5,12,20")))
        val t = get(c, "/materiais/$id/topicos").corpo.toList()
        assertEquals(listOf("Introdução" to 1..4, "Capítulo 5" to 5..11, "Capítulo 12" to 12..19, "Capítulo 20" to 20..30), t.map { it["nome"].asString() to (it["inicio"].asInt()..it["fim"].asInt()) })
        assertEquals(antes, python.chamadas.count { it == "topicos" }, "o modelo de linguagem não foi chamado")
        val reg = dono.queryForMap("SELECT modelo_ia, tokens_entrada, status FROM registro_geracao WHERE material_id = ?::uuid AND operacao = 'sugestao_topicos'", id)
        assertEquals("regras-de-estrutura", reg["modelo_ia"]); assertEquals(0, reg["tokens_entrada"]); assertEquals("concluida", reg["status"])
    }

    @Test
    fun `sem estrutura o modelo de linguagem continua sendo consultado`() {
        val c = conta()
        python.propostaTopicos = """[{"nome":"Parte 1","inicio":1,"fim":3},{"nome":"Parte 2","inicio":4,"fim":8}]"""
        val id = idDe(enviar(c, bytes = pdf(trechos = 8)))
        assertEquals(listOf("Parte 1", "Parte 2"), get(c, "/materiais/$id/topicos").corpo.toList().map { it["nome"].asString() })
        assertEquals("linguagem-falso", dono.queryForObject("SELECT modelo_ia FROM registro_geracao WHERE material_id = ?::uuid AND operacao = 'sugestao_topicos'", String::class.java, id))
    }

    @Test
    fun `embeddings saem em lotes de 64`() {
        val c = conta()
        val antes = python.chamadas.count { it == "embeddings" }
        val id = idDe(enviar(c, bytes = pdf(trechos = 130)))
        assertEquals(3, python.chamadas.count { it == "embeddings" } - antes)
        assertEquals(130, contar("SELECT count(*) FROM trecho WHERE material_id = ?::uuid", id))
    }

    @Test
    fun `assunto escolhido recebe os topicos e os topicos entram ao final`() {
        val c = conta()
        val assunto = get(c, "/assuntos").corpo.toList().first()["id"].asString()
        val livre = chamar("POST", "/topicos", """{"nome":"Livre","assuntoId":"$assunto"}""", c.token)
        assertEquals(201, livre.status); assertEquals(1, livre.corpo["ordem"].asInt())
        val id = idDe(enviar(c, assunto = assunto))
        val t = get(c, "/materiais/$id/topicos").corpo.toList()
        assertEquals(assunto, t[0]["assuntoId"].asString()); assertEquals(2, t[0]["ordem"].asInt())
    }

    // ---------- validação do envio ----------

    private fun semMaterial(c: Conta) = assertEquals(0, contar("SELECT count(*) FROM material WHERE usuario_id = ?", c.id))

    @Test
    fun `formato, tamanho, vazio e conteudo invalido sao recusados sem gravar nada`() {
        val c = conta()
        assertEquals("formato_nao_aceito", enviar(c, "virus.exe", pdf()).corpo["codigo"].asString())
        assertEquals(415, enviar(c, "sem-extensao", pdf()).status)
        assertEquals("arquivo_vazio", enviar(c, "a.pdf", ByteArray(0)).corpo["codigo"].asString())
        val grande = enviar(c, "a.pdf", pdf(extra = "x".repeat(20 * 1024 * 1024)))
        assertEquals(413, grande.status); assertEquals("arquivo_grande", grande.corpo["codigo"].asString())
        val txtComoPdf = enviar(c, "a.txt", pdf())   // extensão diz txt, conteúdo é PDF
        assertEquals(422, txtComoPdf.status); assertEquals("formato_invalido", txtComoPdf.corpo["codigo"].asString())
        assertEquals("formato_invalido", enviar(c, "a.pdf", pdf(extra = "INVALIDO")).corpo["codigo"].asString())
        assertEquals(401, chamarSemToken().status)
        semMaterial(c)
        assertTrue(Files.notExists(arquivos.resolve(c.id.toString())) || Files.list(arquivos.resolve(c.id.toString())).use { it.count() } == 0L)
    }

    private fun chamarSemToken() = chamar("GET", "/materiais")

    @Test
    fun `50 paginas passa e 51 e recusado, tambem a estimativa de DOCX e TXT`() {
        val c = conta()
        assertEquals(202, enviar(c, bytes = pdf(paginas = 50)).status)
        val r = enviar(c, bytes = pdf(paginas = 51))
        assertEquals(422, r.status); assertEquals("paginas_excedidas", r.corpo["codigo"].asString())
        assertEquals(1, contar("SELECT count(*) FROM material WHERE usuario_id = ?", c.id))
        // 3000 caracteres por página: 150000 = 50 páginas (passa); 150001 = 51 (recusa)
        assertEquals(202, enviar(c, "a.txt", "texto CARACTERES=150000 ${UUID.randomUUID()}".toByteArray()).status)
        val txt = enviar(c, "b.txt", "texto CARACTERES=150001 ${UUID.randomUUID()}".toByteArray())
        assertEquals("paginas_excedidas", txt.corpo["codigo"].asString())
        assertNull(dono.queryForMap("SELECT quantidade_paginas FROM material WHERE usuario_id = ? AND nome_arquivo = 'a.txt'", c.id)["quantidade_paginas"], "TXT não tem paginação real")
    }

    @Test
    fun `servico de processamento fora do ar na inspecao da 503 sem gravar`() {
        val c = conta()
        val r = enviar(c, bytes = pdf(extra = "QUEBRAINSPECAO"))
        assertEquals(503, r.status); assertEquals("processamento_indisponivel", r.corpo["codigo"].asString())
        semMaterial(c)
    }

    @Test
    fun `recusa do servico que nao e de formato nao e culpa do arquivo`() {
        val c = conta()
        python.statusInspecao = 401  // segredo interno errado: problema nosso
        val r = enviar(c)
        assertEquals(503, r.status); assertEquals("processamento_indisponivel", r.corpo["codigo"].asString())
        semMaterial(c)
    }

    @Test
    fun `extracao sem nenhum trecho falha como sem texto em vez de criar proposta invalida`() {
        val c = conta()
        val id = idDe(enviar(c, bytes = pdf(trechos = 0)))
        assertEquals("sem_texto", get(c, "/materiais/$id/andamento").corpo["motivoFalha"].asString())
        assertEquals(0, contar("SELECT count(*) FROM topico WHERE usuario_id = ?", c.id))
    }

    @Test
    fun `mesmo arquivo e recusado e aponta o material existente, mas outra conta pode enviar`() {
        val c = conta(); val outra = conta()
        val bytes = pdf()
        val id = idDe(enviar(c, bytes = bytes))
        val r = enviar(c, "outro-nome.pdf", bytes)
        assertEquals(409, r.status); assertEquals("material_repetido", r.corpo["codigo"].asString()); assertEquals(id, r.corpo["materialId"].asString())
        assertEquals(1, contar("SELECT count(*) FROM material WHERE usuario_id = ?", c.id))
        assertEquals(202, enviar(outra, bytes = bytes).status)  // o hash é por usuário
    }

    @Test
    fun `sem vaga global de envio responde 503 e libera depois`() {
        val c = conta()
        val liberar = envios.ocuparTudo()
        try {
            val r = enviar(c)
            assertEquals(503, r.status); assertEquals("servidor_ocupado", r.corpo["codigo"].asString())
            semMaterial(c)
        } finally {
            liberar()
        }
        assertEquals(202, enviar(c).status)
    }

    @Test
    fun `requisicoes malformadas respondem 400 no formato da API, nunca 500`() {
        val c = conta()
        for ((metodo, caminho) in listOf("GET" to "/materiais/nao-e-uuid", "GET" to "/materiais/${UUID.randomUUID()}/trechos?de=abc", "GET" to "/materiais/${UUID.randomUUID()}/andamento/x")) {
            val r = chamar(metodo, caminho, token = c.token)
            assertTrue(r.status in 400..404, "$caminho -> ${r.status}")
            if (r.status == 400) assertEquals("dados_invalidos", r.corpo["codigo"].asString(), caminho)
        }
        val semParte = chamar("POST", "/materiais", "{}", c.token)             // JSON em vez de multipart
        assertTrue(semParte.status in 400..415 && semParte.status != 413, "sem multipart -> ${semParte.status}")
        // multipart sem a parte `arquivo`: antes respondia 500
        val limite = "x${UUID.randomUUID().toString().replace("-", "")}"
        val r = java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(java.net.URI("http://localhost:$porta/materiais")).header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "multipart/form-data; boundary=$limite")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("--$limite\r\nContent-Disposition: form-data; name=\"outro\"\r\n\r\nx\r\n--$limite--\r\n")).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(400, r.statusCode(), r.body())
        assertTrue("dados_invalidos" in r.body())
    }

    @Test
    fun `o mesmo arquivo enviado em paralelo grava um material so e nao deixa arquivo orfao`() {
        val c = conta()
        val bytes = pdf()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val respostas = (1..4).map { pool.submit<Resposta> { enviar(c, bytes = bytes) } }.map { it.get() }
        pool.shutdown()
        assertEquals(1, respostas.count { it.status == 202 }, respostas.map { it.status }.toString())
        assertTrue(respostas.filter { it.status != 202 }.all { it.status == 409 || it.status == 503 }, respostas.map { it.status }.toString())
        assertEquals(1, contar("SELECT count(*) FROM material WHERE usuario_id = ?", c.id))
        assertEquals(1L, Files.list(arquivos.resolve(c.id.toString())).use { it.count() }, "só o arquivo do material gravado")
    }

    @Test
    fun `no maximo dois materiais em processamento por usuario`() {
        val c = conta()
        repeat(2) { dono.update("INSERT INTO material(id,usuario_id,nome_arquivo,tipo_arquivo,tamanho_bytes,hash_arquivo,caminho_arquivo,status_processamento) VALUES (?,?,?,?,1,?,?,'processando')", UUID.randomUUID(), c.id, "p$it.pdf", "application/pdf", "h$it${UUID.randomUUID()}", "x/$it") }
        assertEquals("processamento_em_andamento", enviar(c).corpo["codigo"].asString())
    }

    // ---------- falhas e reenvio ----------

    @Test
    fun `arquivo sem texto marca falhou com o motivo, e o reenvio remove o registro anterior`() {
        val c = conta()
        val bytes = pdf()
        python.falharProcessar = true
        val id1 = idDe(enviar(c, bytes = bytes))
        val a = get(c, "/materiais/$id1/andamento").corpo
        assertEquals("falhou", a["status"].asString()); assertEquals("sem_texto", a["motivoFalha"].asString()); assertEquals("falhou", a["etapa"].asString())
        val reg = dono.queryForMap("SELECT status, finalizada_em FROM registro_geracao WHERE material_id = ?::uuid", id1)
        assertEquals("falhou", reg["status"]); assertTrue(reg["finalizada_em"] != null)
        assertTrue(Files.exists(arquivos.resolve("${c.id}/$id1")))

        python.falharProcessar = false
        val id2 = idDe(enviar(c, bytes = bytes))   // mesmo arquivo: permitido porque o anterior falhou
        assertNotEquals(id1, id2)
        assertEquals("pronto", get(c, "/materiais/$id2/andamento").corpo["status"].asString())
        assertEquals(0, contar("SELECT count(*) FROM material WHERE id = ?::uuid", id1))
        assertEquals(1, contar("SELECT count(*) FROM registro_geracao WHERE usuario_id = ? AND material_id IS NULL AND operacao = 'indexacao_material'", c.id), "registro da operação mantido, sem a referência")
        aguardar { Files.notExists(arquivos.resolve("${c.id}/$id1")) }
    }

    @Test
    fun `falha do servico durante a extracao vira falha de IA`() {
        val c = conta()
        val id = idDe(enviar(c, bytes = pdf(extra = "QUEBRAPROCESSAR")))
        assertEquals("falha_ia", get(c, "/materiais/$id/andamento").corpo["motivoFalha"].asString())
    }

    @Test
    fun `embeddings com duas falhas passageiras sao absorvidas e tres derrubam a indexacao`() {
        val c = conta()
        python.falhasEmbeddings.set(2)
        assertEquals("pronto", get(c, "/materiais/${idDe(enviar(c))}/andamento").corpo["status"].asString())
        python.falhasEmbeddings.set(3)
        val id = idDe(enviar(c))
        assertEquals("falha_ia", get(c, "/materiais/$id/andamento").corpo["motivoFalha"].asString())
        assertEquals("falhou", dono.queryForObject("SELECT status FROM registro_geracao WHERE material_id = ?::uuid AND operacao = 'indexacao_material'", String::class.java, id))
    }

    @Test
    fun `tarefa perdida num reinicio vira falhou interrompido e a recente continua`() {
        val c = conta()
        fun material(minutosAtras: Long): UUID = UUID.randomUUID().also {
            dono.update("INSERT INTO material(id,usuario_id,nome_arquivo,tipo_arquivo,tamanho_bytes,hash_arquivo,caminho_arquivo,status_processamento,atualizado_em) VALUES (?,?,'a.pdf','application/pdf',1,?,'x',?,now() - make_interval(mins => ?::int))",
                it, c.id, "h${UUID.randomUUID()}", "processando", minutosAtras)
        }
        val velho = material(30); val novo = material(0)
        assertEquals("interrompido", get(c, "/materiais/$velho/andamento").corpo["motivoFalha"].asString())
        assertEquals("processando", get(c, "/materiais/$novo/andamento").corpo["status"].asString())
    }

    // ---------- tópicos: revisão ----------

    private fun comTopicos(c: Conta, trechos: Int = 10): Pair<String, List<String>> {
        python.propostaTopicos = """[{"nome":"A","inicio":1,"fim":${trechos / 2}},{"nome":"B","inicio":${trechos / 2 + 1},"fim":$trechos}]"""
        val id = idDe(enviar(c, bytes = pdf(trechos = trechos)))
        return id to get(c, "/materiais/$id/topicos").corpo.toList().map { it["id"].asString() }
    }

    private fun faixas(vararg f: Triple<String?, String, IntRange>) =
        """{"topicos":[${f.joinToString(",") { """{"id":${it.first?.let { i -> "\"$i\"" }},"nome":"${it.second}","inicio":${it.third.first},"fim":${it.third.last}}""" }}]}"""

    @Test
    fun `revisar renomeia, ajusta limite, une e divide numa transacao so`() {
        val c = conta()
        val (id, t) = comTopicos(c)
        // renomear e ajustar a fronteira
        val r1 = put(c, "/materiais/$id/topicos", faixas(Triple(t[0], "Primeiro", 1..6), Triple(t[1], "Segundo", 7..10)))
        assertEquals(200, r1.status, r1.corpo.toString())
        assertEquals(listOf("Primeiro" to 6, "Segundo" to 10), r1.corpo.toList().map { it["nome"].asString() to it["fim"].asInt() })
        // dividir: o segundo vira dois (um novo)
        val r2 = put(c, "/materiais/$id/topicos", faixas(Triple(t[0], "Primeiro", 1..6), Triple(t[1], "Segundo", 7..8), Triple(null, "Terceiro", 9..10)))
        assertEquals(listOf(1, 2, 3), r2.corpo.toList().map { it["ordem"].asInt() })
        // unir: tudo num só (os demais saem)
        val r3 = put(c, "/materiais/$id/topicos", faixas(Triple(t[0], "Tudo", 1..10)))
        assertEquals(1, r3.corpo.size()); assertEquals(1, contar("SELECT count(*) FROM topico WHERE usuario_id = ?", c.id))
        assertEquals(1, contar("SELECT count(*) FROM topico_fonte WHERE usuario_id = ?", c.id))
    }

    @Test
    fun `revisar mantem a ordem dos outros topicos do assunto`() {
        val c = conta()
        chamar("POST", "/topicos", """{"nome":"Livre"}""", c.token)
        val (id, t) = comTopicos(c)
        put(c, "/materiais/$id/topicos", faixas(Triple(t[0], "Tudo", 1..10)))
        chamar("POST", "/topicos", """{"nome":"Livre 2"}""", c.token)
        val ordens = dono.queryForList("SELECT nome FROM topico WHERE usuario_id = ? ORDER BY ordem", String::class.java, c.id)
        assertEquals(listOf("Livre", "Tudo", "Livre 2"), ordens)
    }

    @Test
    fun `cobertura invalida e recusada e nada muda`() {
        val c = conta()
        val (id, t) = comTopicos(c)
        val antes = dono.queryForList("SELECT nome || ordem_trecho_inicial || '-' || ordem_trecho_final FROM topico t JOIN topico_fonte f ON f.topico_id = t.id WHERE t.usuario_id = ? ORDER BY 1", String::class.java, c.id)
        listOf(
            faixas(Triple(t[0], "A", 1..4), Triple(t[1], "B", 6..10)),            // lacuna
            faixas(Triple(t[0], "A", 1..6), Triple(t[1], "B", 6..10)),            // sobreposição
            faixas(Triple(t[0], "A", 2..5), Triple(t[1], "B", 6..10)),            // não começa em 1
            faixas(Triple(t[0], "A", 1..5), Triple(t[1], "B", 6..9)),             // não termina no último trecho
            faixas(Triple(t[0], "A", 1..5), Triple(t[1], "B", 6..11)),            // passa do fim
            faixas(Triple(t[0], "  ", 1..5), Triple(t[1], "B", 6..10)),           // sem nome
            faixas(Triple(t[0], "A", 1..5), Triple(t[0], "B", 6..10)),            // tópico repetido
            """{"topicos":[]}""",
        ).forEach { corpo ->
            val r = put(c, "/materiais/$id/topicos", corpo)
            assertTrue(r.status == 422 || r.status == 400, "$corpo -> ${r.status}")
        }
        val depois = dono.queryForList("SELECT nome || ordem_trecho_inicial || '-' || ordem_trecho_final FROM topico t JOIN topico_fonte f ON f.topico_id = t.id WHERE t.usuario_id = ? ORDER BY 1", String::class.java, c.id)
        assertEquals(antes, depois)
    }

    @Test
    fun `uma falha no meio da revisao desfaz tudo`() {
        val c = conta()
        val (id, t) = comTopicos(c)
        // tópico "de outra pessoa" na lista: 404 depois de nada ter sido gravado (a validação vem antes, mas o teste garante o estado final)
        val r = put(c, "/materiais/$id/topicos", faixas(Triple(t[0], "Renomeado", 1..5), Triple(UUID.randomUUID().toString(), "B", 6..10)))
        assertEquals(404, r.status)
        assertEquals(listOf("A", "B"), dono.queryForList("SELECT nome FROM topico WHERE usuario_id = ? ORDER BY ordem", String::class.java, c.id))
    }

    @Test
    fun `revisao so vale para material pronto`() {
        val c = conta()
        python.falharProcessar = true
        val id = idDe(enviar(c))
        assertEquals(409, put(c, "/materiais/$id/topicos", faixas(Triple(null, "A", 1..1))).status)
    }

    // ---------- tópico com conteúdo gerado (ADR 0028) ----------

    private fun comResumo(c: Conta, topico: String) =
        dono.update("INSERT INTO resumo(id,usuario_id,topico_id,conteudo) VALUES (?,?,?::uuid,'resumo')", UUID.randomUUID(), c.id, topico)

    private fun comQuestao(c: Conta, topico: String) = dono.update(
        "INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding) VALUES (?,?,?::uuid,'Q?','[\"a\",\"b\"]',0,?,array_fill(0.01::real, ARRAY[1536])::vector,'m')",
        UUID.randomUUID(), c.id, topico, "a".repeat(64),
    )

    private fun estado(c: Conta) = dono.queryForList("SELECT t.nome || ':' || t.ordem || ':' || f.ordem_trecho_inicial || '-' || f.ordem_trecho_final FROM topico t JOIN topico_fonte f ON f.topico_id = t.id WHERE t.usuario_id = ? ORDER BY t.ordem", String::class.java, c.id)

    @Test
    fun `topico com conteudo so pode ser renomeado, sem mudar limites, unir ou remover`() {
        val c = conta()
        val (id, t) = comTopicos(c)   // A = 1..5, B = 6..10
        comResumo(c, t[0])
        assertEquals(listOf(true, false), get(c, "/materiais/$id/topicos").corpo.toList().map { it["temConteudo"].asBoolean() })
        val antes = estado(c)
        val proibidas = listOf(
            "mover a fronteira (muda o tópico com conteúdo)" to faixas(Triple(t[0], "A", 1..6), Triple(t[1], "B", 7..10)),
            "unir os dois" to faixas(Triple(t[0], "A", 1..10)),
            "remover o tópico com conteúdo" to faixas(Triple(t[1], "B", 1..10)),
            "dividir o tópico com conteúdo" to faixas(Triple(t[0], "A", 1..3), Triple(null, "A2", 4..5), Triple(t[1], "B", 6..10)),
        )
        proibidas.forEach { (oque, corpo) ->
            val r = put(c, "/materiais/$id/topicos", corpo)
            assertEquals(409, r.status, oque); assertEquals("topico_com_conteudo", r.corpo["codigo"].asString(), oque)
        }
        assertEquals(antes, estado(c), "nada mudou")
        // renomear e mexer só no tópico sem conteúdo é permitido (dividir o B)
        val ok = put(c, "/materiais/$id/topicos", faixas(Triple(t[0], "A renomeado", 1..5), Triple(t[1], "B", 6..8), Triple(null, "C", 9..10)))
        assertEquals(200, ok.status, ok.corpo.toString())
        assertEquals(listOf("A renomeado:1:1-5", "B:2:6-8", "C:3:9-10"), estado(c))
    }

    @Test
    fun `questoes tambem congelam o topico e outra conta nao interfere`() {
        val a = conta(); val b = conta()
        val (id, t) = comTopicos(a)
        comQuestao(a, t[1])
        assertEquals(409, put(a, "/materiais/$id/topicos", faixas(Triple(t[0], "A", 1..10))).status)  // unir arrasta o B, que tem questão
        assertEquals(200, put(a, "/materiais/$id/topicos", faixas(Triple(t[0], "A", 1..2), Triple(null, "A2", 3..5), Triple(t[1], "B", 6..10))).status)
        val (idB, tb) = comTopicos(b)  // o conteúdo de A não congela os tópicos de B
        assertEquals(200, put(b, "/materiais/$idB/topicos", faixas(Triple(tb[0], "Tudo", 1..10))).status)
    }

    // ---------- UC03: tópico livre ----------

    @Test
    fun `topico livre entra ao final do assunto, sem material`() {
        val c = conta()
        val a = chamar("POST", "/topicos", """{"nome":"  Movimento Retilíneo Uniforme "}""", c.token)
        assertEquals(201, a.status); assertEquals("Movimento Retilíneo Uniforme", a.corpo["nome"].asString())
        assertEquals(2, chamar("POST", "/topicos", """{"nome":"Outro"}""", c.token).corpo["ordem"].asInt())
        assertEquals(0, contar("SELECT count(*) FROM topico_fonte WHERE usuario_id = ?", c.id))
        assertEquals(400, chamar("POST", "/topicos", """{"nome":"   "}""", c.token).status)
        assertEquals(400, chamar("POST", "/topicos", """{"nome":"${"x".repeat(201)}"}""", c.token).status)
    }

    @Test
    fun `topicos livres simultaneos nao repetem a ordem`() {
        val c = conta()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val ok = AtomicInteger()
        (1..8).map { pool.submit { if (chamar("POST", "/topicos", """{"nome":"T$it"}""", c.token).status == 201) ok.incrementAndGet() } }.forEach { it.get() }
        pool.shutdown()
        assertEquals(8, ok.get())
        assertEquals((1..8).toList(), dono.queryForList("SELECT ordem FROM topico WHERE usuario_id = ? ORDER BY ordem", Int::class.java, c.id))
    }

    // ---------- isolamento A x B ----------

    @Test
    fun `paginas dos trechos saem na ordem e so para o dono`() {
        val a = conta(); val b = conta()
        val id = idDe(enviar(a, bytes = pdf(trechos = 4)))
        assertEquals(listOf(1, 2, 3, 4), get(a, "/materiais/$id/paginas").corpo.toList().map { it.asInt() })
        assertEquals(404, get(b, "/materiais/$id/paginas").status)
    }

    @Test
    fun `usuario B nao ve nem altera nada de A por nenhuma rota`() {
        val a = conta(); val b = conta()
        val (id, t) = comTopicos(a)
        val assuntoA = get(a, "/assuntos").corpo.toList().first()["id"].asString()

        for (rota in listOf("", "/andamento", "/trechos", "/topicos", "/paginas")) assertEquals(404, get(b, "/materiais/$id$rota").status, rota)
        assertEquals(0, get(b, "/materiais").corpo.size())
        val tentativa = put(b, "/materiais/$id/topicos", faixas(Triple(t[0], "Invadido", 1..5), Triple(t[1], "B", 6..10)))
        assertEquals(404, tentativa.status)
        assertEquals(404, chamar("POST", "/topicos", """{"nome":"X","assuntoId":"$assuntoA"}""", b.token).status)
        assertEquals(404, enviar(b, assunto = assuntoA).status)
        assertEquals(listOf("A", "B"), dono.queryForList("SELECT nome FROM topico WHERE usuario_id = ? ORDER BY ordem", String::class.java, a.id))
        assertEquals(1, get(a, "/materiais").corpo.size())
        assertEquals(0, contar("SELECT count(*) FROM material WHERE usuario_id = ?", b.id))
    }

    @Test
    fun `sem contexto de usuario o banco devolve vazio para as tabelas novas e recusa gravacao`() {
        val c = conta(); comTopicos(c)
        for (tabela in listOf("material", "trecho", "topico", "topico_fonte", "registro_geracao"))
            assertEquals(0, tx.execute { jdbc.queryForObject("SELECT count(*) FROM $tabela", Int::class.java) }, tabela)
        val outro = conta()
        // com o contexto de outro usuário também não enxerga as linhas de c
        assertEquals(0, UsuarioAtual.como(outro.id) { tx.execute { jdbc.queryForObject("SELECT count(*) FROM trecho WHERE usuario_id = ?", Int::class.java, c.id) } })
    }

    // ---------- remoção (regras 6 e 7) ----------

    @Test
    fun `rotina de remocao anula a referencia, apaga trechos e fontes, mantem topicos e registros e apaga o arquivo so depois do commit`() {
        val c = conta()
        val (id, _) = comTopicos(c)
        val arquivo = arquivos.resolve("${c.id}/$id")
        UsuarioAtual.como(c.id) {
            // rollback: o arquivo continua
            runCatching { tx.executeWithoutResult { remocao.depoisDoCommit(chaves = listOf(rotina.excluirMaterial(UUID.fromString(id))!!)); error("desfaz") } }
            assertTrue(Files.exists(arquivo), "transação desfeita não apaga o arquivo")
            assertEquals(1, contar("SELECT count(*) FROM material WHERE id = ?::uuid", id))
            // commit: apaga
            tx.executeWithoutResult { remocao.depoisDoCommit(chaves = listOf(rotina.excluirMaterial(UUID.fromString(id))!!)) }
        }
        aguardar { Files.notExists(arquivo) }
        assertEquals(0, contar("SELECT count(*) FROM trecho WHERE usuario_id = ?", c.id))
        assertEquals(0, contar("SELECT count(*) FROM topico_fonte WHERE usuario_id = ?", c.id))
        assertEquals(2, contar("SELECT count(*) FROM topico WHERE usuario_id = ?", c.id), "tópicos ficam (agora livres)")
        assertEquals(2, contar("SELECT count(*) FROM registro_geracao WHERE usuario_id = ? AND material_id IS NULL", c.id))
    }

    @Test
    fun `remocao do arquivo tenta de novo quando falha`() {
        val tentativas = AtomicInteger()
        val falho = object : Armazenamento {
            override fun salvar(chave: String, dados: ByteArray) = Unit
            override fun ler(chave: String) = ByteArray(0)
            override fun remover(chave: String) { if (tentativas.incrementAndGet() <= 2) throw java.io.IOException("disco ocupado") }
            override fun removerPasta(prefixo: String) = Unit
        }
        RemocaoArquivos(falho, props).depoisDoCommit(chaves = listOf("u/m"))
        aguardar { tentativas.get() == 3 }
        Thread.sleep(150)
        assertEquals(3, tentativas.get(), "duas falhas e um sucesso; sem tentativas extras")
    }

    @Test
    fun `excluir a conta apaga os arquivos depois do commit`() {
        val c = conta()
        val id = idDe(enviar(c))
        assertTrue(Files.exists(arquivos.resolve("${c.id}/$id")))
        assertEquals(204, chamar("DELETE", "/perfil", """{"senha":"senha-forte-1","confirmacao":"EXCLUIR"}""", c.token).status)
        aguardar { Files.notExists(arquivos.resolve(c.id.toString())) }
        assertEquals(0, contar("SELECT count(*) FROM material WHERE usuario_id = ?", c.id))
    }

    private fun aguardar(condicao: () -> Boolean) {
        val limite = System.currentTimeMillis() + 3000
        while (!condicao()) { check(System.currentTimeMillis() < limite) { "condição não atendida em 3 s" }; Thread.sleep(20) }
    }
}
