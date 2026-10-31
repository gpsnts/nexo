package br.nexo

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lista e ordem dos tópicos de uma pasta, fontes de vários materiais num tópico, exclusão de material e isolamento entre contas. */
class TopicosFontesTest : BaseIntegracao() {
    class Conta(val token: String, val id: UUID)

    private fun conta(): Conta {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        return Conta(token, dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!)
    }

    private fun pasta(c: Conta, nome: String) = chamar("POST", "/assuntos", """{"nome":"$nome"}""", c.token).corpo["id"].asString()
    private fun livre(c: Conta, assunto: String? = null) = chamar("POST", "/topicos", """{"nome":"Tema ${UUID.randomUUID()}"${assunto?.let { ""","assuntoId":"$it"""" } ?: ""}}""", c.token).corpo["id"].asString()

    /**
     * Material pronto de 10 trechos cujos tópicos são os dados (nome, início, fim). A proposta automática cobre tudo; a revisão posterior deixa os trechos
     * fora das faixas de fora, que é o que permite acrescentar fontes depois.
     */
    private fun material(c: Conta, vararg faixas: Triple<String, Int, Int>, assunto: String? = null): String {
        python.propostaTopicos = """[{"nome":"Tudo","inicio":1,"fim":10}]"""
        val id = enviarArquivo(c.token, "apostila.pdf", "%PDF-1.4 PAGINAS=2 TRECHOS=10 nonce=${UUID.randomUUID()}".toByteArray(), assunto).corpo["materialId"].asString()
        val primeiro = topicosDe(c, id)[0]["id"].asString()
        val lista = faixas.mapIndexed { i, f -> """{${if (i == 0) "\"id\":\"$primeiro\"," else ""}"nome":"${f.first}","inicio":${f.second},"fim":${f.third}}""" }
        val r = chamar("PUT", "/materiais/$id/topicos", """{"topicos":${lista.joinToString(",", "[", "]")}}""", c.token)
        check(r.status == 200) { "revisão falhou: ${r.status} ${r.corpo}" }
        return id
    }

    private fun topicosDe(c: Conta, material: String) = chamar("GET", "/materiais/$material/topicos", token = c.token).corpo.toList()
    private fun daPasta(c: Conta, pasta: String) = chamar("GET", "/assuntos/$pasta/topicos", token = c.token).corpo.toList()
    private fun fonte(c: Conta, topico: String, material: String, ini: Int, fim: Int) =
        chamar("POST", "/topicos/$topico/fontes", """{"materialId":"$material","inicio":$ini,"fim":$fim}""", c.token)
    private fun questao(c: Conta, topico: String) = dono.update(
        "INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding) VALUES (?,?,?::uuid,'Q?','[\"a\",\"b\"]',0,?,array_fill(0.01::real, ARRAY[1536])::vector,'m')",
        UUID.randomUUID(), c.id, topico, UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
    )

    @Test
    fun `lista os topicos da pasta com materiais e reordena numa transacao`() {
        val c = conta(); val p = pasta(c, "Estudos")
        val m = material(c, Triple("Causas", 1, 5), Triple("Batalhas", 6, 10), assunto = p)
        val l = livre(c, p)
        val antes = daPasta(c, p)
        assertEquals(3, antes.size); assertEquals(listOf(1, 2, 3), antes.map { it["ordem"].asInt() })
        assertEquals(listOf(0, 0, 0), antes.map { it["totalQuestoes"].asInt() })
        questao(c, antes[0]["id"].asString()); questao(c, antes[0]["id"].asString())
        assertEquals(2, daPasta(c, p)[0]["totalQuestoes"].asInt(), "conta as questões do tópico")
        assertTrue(antes.last()["livre"].asBoolean()); assertEquals(m, antes.first()["materiais"][0]["id"].asString())
        val nova = listOf(antes[2], antes[0], antes[1]).map { it["id"].asString() }
        val r = chamar("PUT", "/assuntos/$p/topicos/ordem", """{"ids":${nova.joinToString(",", "[", "]") { "\"$it\"" }}}""", c.token)
        assertEquals(200, r.status); assertEquals(nova, r.corpo.toList().map { it["id"].asString() })
        assertEquals(l, daPasta(c, p)[0]["id"].asString())
        assertEquals("ordem_invalida", chamar("PUT", "/assuntos/$p/topicos/ordem", """{"ids":["${nova[0]}"]}""", c.token).corpo["codigo"].asString(), "faltam tópicos")
        assertEquals("ordem_invalida", chamar("PUT", "/assuntos/$p/topicos/ordem", """{"ids":["${nova[0]}","${nova[0]}","${nova[1]}"]}""", c.token).corpo["codigo"].asString(), "repetido")
        assertEquals(nova, daPasta(c, p).map { it["id"].asString() }, "pedido inválido não mexe na ordem")
        assertEquals(404, chamar("GET", "/assuntos/${UUID.randomUUID()}/topicos", token = c.token).status)
    }

    @Test
    fun `topico ganha fonte de outro material, a geracao usa as duas e remover volta a uma`() {
        val c = conta()
        val m1 = material(c, Triple("Causas", 1, 2))
        val m2 = material(c, Triple("Outro", 1, 5))
        val causas = topicosDe(c, m1)[0]["id"].asString()
        val r = fonte(c, causas, m2, 8, 9)
        assertEquals(201, r.status); assertEquals(setOf(m1, m2), r.corpo.toList().map { it["materialId"].asString() }.toSet())
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM topico_fonte WHERE topico_id = ?::uuid AND material_id = ?::uuid", Int::class.java, causas, m2))
        assertEquals(setOf(causas), topicosDe(c, m2).map { it["id"].asString() }.toSet().intersect(setOf(causas)), "o tópico também aparece no segundo material")
        assertEquals(202, chamar("POST", "/topicos/$causas/resumo", token = c.token).status)
        val enviados = python.corpos.last { it.startsWith("resumo:") }.substringAfter(":").let { mapper.readTree(it)["trechos"].toList().map { n -> n["conteudo"].asString() } }
        assertEquals(listOf(1, 2, 8, 9), enviados.map { Regex("Trecho (\\d+) ").find(it)!!.groupValues[1].toInt() }.sorted(), "trechos das duas fontes")
    }

    @Test
    fun `as questoes de um topico com dois materiais saem dos trechos das duas fontes`() {
        val c = conta()
        val m1 = material(c, Triple("Causas", 1, 2))
        val m2 = material(c, Triple("Outro", 1, 5))
        val causas = topicosDe(c, m1)[0]["id"].asString()
        assertEquals(201, fonte(c, causas, m2, 8, 9).status)
        assertEquals(202, chamar("POST", "/topicos/$causas/questoes", """{"quantidade":2}""", c.token).status)
        val enviados = python.corpos.last { it.startsWith("questoes:") }.substringAfter(":").let { mapper.readTree(it)["trechos"].toList().map { n -> n["conteudo"].asString() } }
        assertEquals(listOf(1, 2, 8, 9), enviados.map { Regex("Trecho (\\d+) ").find(it)!!.groupValues[1].toInt() }.sorted(), "trechos das duas fontes")
    }

    @Test
    fun `candidatos a receber uma faixa do material sao da mesma pasta, sem conteudo e sem o material, e a divisao continua salvando`() {
        val c = conta(); val p = pasta(c, "Estudos")
        val m = material(c, Triple("A", 1, 3), Triple("B", 5, 6), assunto = p)
        val livre = livre(c, p); val comConteudo = livre(c, p); val outraPasta = livre(c, pasta(c, "Outra"))
        questao(c, comConteudo)
        val ids = chamar("GET", "/materiais/$m/topicos-candidatos", token = c.token).corpo.toList().map { it["id"].asString() }
        assertEquals(listOf(livre), ids, "só o tópico livre da mesma pasta e sem conteúdo (os do próprio material já o usam)")
        assertTrue(outraPasta !in ids)
        assertEquals(201, fonte(c, livre, m, 4, 4).status)
        assertEquals(emptyList(), chamar("GET", "/materiais/$m/topicos-candidatos", token = c.token).corpo.toList().map { it["id"].asString() }, "depois de usar o material, o tópico sai da lista")
        val lista = topicosDe(c, m)
        assertEquals(3, lista.size, "o tópico que recebeu a faixa passa a fazer parte do material")
        val corpo = lista.joinToString(",", "[", "]") { """{"id":"${it["id"].asString()}","nome":"${it["nome"].asString()}","inicio":${it["inicio"].asInt()},"fim":${it["fim"].asInt()}}""" }
        assertEquals(200, chamar("PUT", "/materiais/$m/topicos", """{"topicos":$corpo}""", c.token).status, "a revisão da divisão segue funcionando")
        val b = conta()
        assertEquals(404, chamar("GET", "/materiais/$m/topicos-candidatos", token = b.token).status)
    }

    @Test
    fun `historico lista todos os topicos, de material ou livres, e so os da conta`() {
        val a = conta(); val b = conta()
        val m = material(a, Triple("Causas", 1, 5), Triple("Batalhas", 6, 10))
        val l = livre(a)
        livre(b)
        val itens = chamar("GET", "/topicos", token = a.token).corpo.toList()
        assertEquals(3, itens.size)
        assertEquals(l, itens[0]["id"].asString(), "do mais novo ao mais antigo")
        assertEquals(true, itens[0]["livre"].asBoolean())
        assertEquals(listOf(m), itens.last()["materiais"].toList().map { it["id"].asString() })
        assertEquals(1, chamar("GET", "/topicos", token = b.token).corpo.size())
        assertEquals(1, chamar("GET", "/topicos?limite=1", token = a.token).corpo.size())
    }

    @Test
    fun `lista de pastas traz a contagem de topicos e de materiais`() {
        val c = conta(); val p = pasta(c, "Estudos")
        material(c, Triple("A", 1, 5), Triple("B", 6, 10), assunto = p); livre(c, p)
        val pastas = chamar("GET", "/assuntos", token = c.token).corpo.toList()
        val estudos = pastas.first { it["id"].asString() == p }
        assertEquals(3, estudos["topicos"].asInt()); assertEquals(1, estudos["materiais"].asInt())
        assertEquals(0, pastas.first { it["padrao"].asBoolean() }["topicos"].asInt())
        assertTrue(pastas[0]["padrao"].asBoolean(), "o padrão vem primeiro")
        assertEquals(0, chamar("GET", "/assuntos", token = conta().token).corpo.toList().first { !it["padrao"].isNull && it["padrao"].asBoolean() }["topicos"].asInt(), "a contagem não vaza entre contas")
    }

    @Test
    fun `faixa invalida, sobreposta, repetida e topico com conteudo recusam a fonte`() {
        val c = conta()
        val m1 = material(c, Triple("A", 1, 3))
        val m2 = material(c, Triple("B", 1, 5))
        val a = topicosDe(c, m1)[0]["id"].asString()
        val livre = livre(c)
        assertEquals("faixa_sobreposta", fonte(c, livre, m2, 4, 7).corpo["codigo"].asString(), "trechos 4 e 5 são do tópico B")
        assertEquals("faixa_invalida", fonte(c, livre, m2, 8, 11).corpo["codigo"].asString())
        assertEquals("faixa_invalida", fonte(c, livre, m2, 9, 8).corpo["codigo"].asString())
        assertEquals(400, fonte(c, livre, m2, 0, 3).status)
        assertEquals(201, fonte(c, livre, m2, 7, 8).status)
        assertEquals("fonte_duplicada", fonte(c, livre, m2, 9, 10).corpo["codigo"].asString())
        assertEquals("faixa_sobreposta", fonte(c, a, m2, 8, 9).corpo["codigo"].asString())
        questao(c, a)
        assertEquals("topico_com_conteudo", fonte(c, a, m2, 9, 10).corpo["codigo"].asString())
        assertEquals("topico_com_conteudo", chamar("DELETE", "/topicos/$a/fontes/$m1", token = c.token).corpo["codigo"].asString())
        assertEquals(404, fonte(c, livre, UUID.randomUUID().toString(), 1, 2).status)
        val pendente = UUID.randomUUID()
        dono.update("INSERT INTO material(id,usuario_id,nome_arquivo,tipo_arquivo,tamanho_bytes,hash_arquivo,caminho_arquivo,status_processamento) VALUES (?,?,'x.pdf','pdf',1,?,'x','processando')", pendente, c.id, UUID.randomUUID().toString())
        assertEquals("material_nao_pronto", fonte(c, livre, pendente.toString(), 1, 2).corpo["codigo"].asString())
    }

    @Test
    fun `remover a ultima fonte torna o topico livre e a faixa fica disponivel`() {
        val c = conta()
        val m = material(c, Triple("A", 1, 4))
        val t = topicosDe(c, m)[0]["id"].asString()
        assertEquals(204, chamar("DELETE", "/topicos/$t/fontes/$m", token = c.token).status)
        assertEquals(404, chamar("DELETE", "/topicos/$t/fontes/$m", token = c.token).status)
        assertEquals(true, chamar("GET", "/topicos/$t", token = c.token).corpo["livre"].asBoolean())
        val outro = livre(c)
        assertEquals(201, fonte(c, outro, m, 2, 3).status, "os trechos soltos podem ser usados")
        assertEquals(1, chamar("GET", "/topicos/$outro/fontes", token = c.token).corpo.size())
    }

    @Test
    fun `revisar a divisao com topicos em pastas diferentes ou com varias fontes explica o motivo`() {
        val c = conta(); val p = pasta(c, "Outra")
        val m = material(c, Triple("A", 1, 3), Triple("B", 4, 6))
        val ts = topicosDe(c, m)
        chamar("PUT", "/topicos/${ts[1]["id"].asString()}/assunto", """{"assuntoId":"$p"}""", c.token)
        val corpo = """{"topicos":[{"id":"${ts[0]["id"].asString()}","nome":"A","inicio":1,"fim":3},{"id":"${ts[1]["id"].asString()}","nome":"B","inicio":4,"fim":6}]}"""
        assertEquals("assuntos_diferentes", chamar("PUT", "/materiais/$m/topicos", corpo, c.token).corpo["codigo"].asString())
        val outro = material(c, Triple("C", 1, 2))
        val b = ts[1]["id"].asString()
        chamar("PUT", "/topicos/$b/assunto", """{"assuntoId":null}""", c.token)
        fonte(c, b, outro, 5, 6)
        val unir = """{"topicos":[{"id":"${ts[0]["id"].asString()}","nome":"A","inicio":1,"fim":6}]}"""
        assertEquals("topico_com_varias_fontes", chamar("PUT", "/materiais/$m/topicos", unir, c.token).corpo["codigo"].asString())
    }

    @Test
    fun `excluir material mantem topicos, questoes e o outro material, remove o arquivo e anula o registro`() {
        val c = conta()
        val m1 = material(c, Triple("Causas", 1, 5))
        val m2 = material(c, Triple("Outro", 1, 3))
        val t = topicosDe(c, m1)[0]["id"].asString()
        assertEquals(201, fonte(c, livre(c), m2, 6, 7).status)
        val misto = livre(c)
        assertEquals(201, fonte(c, misto, m1, 8, 9).status)
        assertEquals(201, fonte(c, misto, m2, 8, 9).status)
        questao(c, t)
        val arquivo = dono.queryForObject("SELECT caminho_arquivo FROM material WHERE id = ?::uuid", String::class.java, m1)!!
        assertTrue(java.nio.file.Files.exists(arquivos.resolve(arquivo)))
        val registros = dono.queryForObject("SELECT count(*) FROM registro_geracao WHERE material_id = ?::uuid", Int::class.java, m1)!!
        assertTrue(registros > 0)

        assertEquals(204, chamar("DELETE", "/materiais/$m1", token = c.token).status)
        assertEquals(404, chamar("GET", "/materiais/$m1", token = c.token).status)
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM trecho WHERE material_id = ?::uuid", Int::class.java, m1))
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM registro_geracao WHERE material_id = ?::uuid", Int::class.java, m1))
        assertEquals(registros, dono.queryForObject("SELECT count(*) FROM registro_geracao WHERE usuario_id = ? AND material_id IS NULL AND operacao IN ('indexacao_material','sugestao_topicos')", Int::class.java, c.id)!!, "registros mantidos, sem a referência")
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM questao WHERE topico_id = ?::uuid", Int::class.java, t), "a questão fica")
        assertEquals(true, chamar("GET", "/topicos/$t", token = c.token).corpo["livre"].asBoolean(), "o tópico que só tinha esse material vira livre")
        assertEquals(listOf(m2), chamar("GET", "/topicos/$misto/fontes", token = c.token).corpo.toList().map { it["materialId"].asString() }, "o tópico misto continua com o outro")
        Thread.sleep(300)
        assertTrue(!java.nio.file.Files.exists(arquivos.resolve(arquivo)), "arquivo removido depois do commit")
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM material WHERE id = ?::uuid", Int::class.java, m2))
    }

    @Test
    fun `material em processamento nao pode ser excluido`() {
        val c = conta(); val id = UUID.randomUUID()
        dono.update("INSERT INTO material(id,usuario_id,nome_arquivo,tipo_arquivo,tamanho_bytes,hash_arquivo,caminho_arquivo,status_processamento) VALUES (?,?,'x.pdf','pdf',1,?,'x','processando')", id, c.id, UUID.randomUUID().toString())
        assertEquals("material_processando", chamar("DELETE", "/materiais/$id", token = c.token).corpo["codigo"].asString())
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM material WHERE id = ?", Int::class.java, id))
    }

    @Test
    fun `uma conta nao ve, ordena, acrescenta fonte nem exclui o que e de outra`() {
        val a = conta(); val b = conta(); val pa = pasta(a, "Pasta de A")
        val ma = material(a, Triple("A1", 1, 5), assunto = pa)
        val ta = topicosDe(a, ma)[0]["id"].asString()
        val mb = material(b, Triple("B1", 1, 5))
        val tb = topicosDe(b, mb)[0]["id"].asString()
        assertEquals(404, chamar("GET", "/assuntos/$pa/topicos", token = b.token).status)
        assertEquals(404, chamar("PUT", "/assuntos/$pa/topicos/ordem", """{"ids":["$ta"]}""", b.token).status)
        assertEquals(404, chamar("GET", "/topicos/$ta/fontes", token = b.token).status)
        assertEquals(404, fonte(b, ta, mb, 7, 8).status, "tópico de A")
        assertEquals(404, fonte(b, tb, ma, 7, 8).status, "material de A")
        assertEquals(404, chamar("DELETE", "/topicos/$ta/fontes/$ma", token = b.token).status)
        assertEquals(404, chamar("DELETE", "/materiais/$ma", token = b.token).status)
        assertEquals(404, chamar("PUT", "/topicos/$tb/assunto", """{"assuntoId":"$pa"}""", b.token).status, "pasta de A")
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM material WHERE id = ?::uuid", Int::class.java, ma))
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM topico_fonte WHERE topico_id = ?::uuid", Int::class.java, ta))
    }
}
