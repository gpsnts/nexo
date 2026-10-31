package br.nexo

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pastas (assuntos geridos pelo usuário) e nome do estudo: CRUD, mover, excluir sem perder estudos, sugestão de nome e isolamento. */
class OrganizacaoTest : BaseIntegracao() {
    class Conta(val token: String, val id: UUID)

    @AfterEach fun restaurar() { python.statusGeracao = null }

    private fun conta(): Conta {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        return Conta(token, dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!)
    }

    private fun pasta(c: Conta, nome: String) = chamar("POST", "/assuntos", """{"nome":"$nome"}""", c.token).corpo["id"].asString()
    private fun geral(c: Conta) = chamar("GET", "/assuntos", token = c.token).corpo.toList().first { it["padrao"].asBoolean() }["id"].asString()
    private fun livre(c: Conta, nome: String = "Tema ${UUID.randomUUID()}", assunto: String? = null) =
        chamar("POST", "/topicos", """{"nome":"$nome"${assunto?.let { ""","assuntoId":"$it"""" } ?: ""}}""", c.token).corpo["id"].asString()

    /** Material pronto com dois tópicos (1..5 e 6..10), na pasta pedida. */
    private fun material(c: Conta, assunto: String? = null): String {
        python.propostaTopicos = """[{"nome":"Causas","inicio":1,"fim":5},{"nome":"Batalhas","inicio":6,"fim":10}]"""
        return enviarArquivo(c.token, "apostila.pdf", "%PDF-1.4 PAGINAS=2 TRECHOS=10 nonce=${UUID.randomUUID()}".toByteArray(), assunto).corpo["materialId"].asString()
    }

    private fun assuntosDosTopicos(materialId: String) = dono.queryForList("SELECT DISTINCT t.assunto_id::text FROM topico t JOIN topico_fonte f ON f.topico_id=t.id WHERE f.material_id=?::uuid", String::class.java, materialId).filterNotNull()
    private fun materialDto(c: Conta, id: String) = chamar("GET", "/materiais/$id", token = c.token).corpo
    private fun registros(c: Conta) = dono.queryForList("SELECT status || ':' || tokens_entrada FROM registro_geracao WHERE usuario_id = ? AND operacao = 'nome_estudo' ORDER BY criado_em", String::class.java, c.id).filterNotNull()

    @Test
    fun `cria, renomeia e valida pastas, e Geral e intocavel`() {
        val c = conta(); val g = geral(c)
        val id = pasta(c, "História")
        assertTrue(chamar("GET", "/assuntos", token = c.token).corpo.toList().any { it["id"].asString() == id && !it["padrao"].asBoolean() })
        assertEquals("assunto_duplicado", chamar("POST", "/assuntos", """{"nome":"história"}""", c.token).corpo["codigo"].asString(), "sem diferenciar maiúsculas")
        assertEquals(400, chamar("POST", "/assuntos", """{"nome":"  "}""", c.token).status)
        assertEquals(400, chamar("POST", "/assuntos", """{"nome":"${"x".repeat(61)}"}""", c.token).status)
        assertEquals("Brasil Colônia", chamar("PUT", "/assuntos/$id", """{"nome":" Brasil Colônia "}""", c.token).corpo["nome"].asString())
        assertEquals(409, chamar("PUT", "/assuntos/$g", """{"nome":"Outro"}""", c.token).status)
        assertEquals("assunto_padrao", chamar("DELETE", "/assuntos/$g", token = c.token).corpo["codigo"].asString())
        pasta(c, "Física")
        assertEquals("assunto_duplicado", chamar("PUT", "/assuntos/$id", """{"nome":"Física"}""", c.token).corpo["codigo"].asString())
    }

    @Test
    fun `estudo pode nascer na pasta escolhida, mudar de pasta e voltar para Geral`() {
        val c = conta(); val g = geral(c); val historia = pasta(c, "História"); val fisica = pasta(c, "Física")
        val m = material(c, historia)
        assertEquals(historia, materialDto(c, m)["assuntoId"].asString())
        assertEquals(setOf(historia), assuntosDosTopicos(m).toSet())
        assertEquals(200, chamar("PUT", "/materiais/$m/assunto", """{"assuntoId":"$fisica"}""", c.token).status)
        assertEquals(setOf(fisica), assuntosDosTopicos(m).toSet(), "os dois tópicos mudam juntos")
        assertEquals(fisica, chamar("GET", "/materiais", token = c.token).corpo.toList().first { it["id"].asString() == m }["assuntoId"].asString())
        chamar("PUT", "/materiais/$m/assunto", """{"assuntoId":null}""", c.token)
        assertEquals(g, materialDto(c, m)["assuntoId"].asString(), "sem pasta = Geral")
        val ordens = dono.queryForList("SELECT ordem FROM topico WHERE assunto_id = ?::uuid ORDER BY ordem", Int::class.java, g).filterNotNull()
        assertEquals(ordens.indices.map { ordens[0] + it }, ordens, "ordens consecutivas e sem repetir")
    }

    @Test
    fun `topico livre e topico de material mudam de pasta sozinhos`() {
        val c = conta(); val g = geral(c); val p = pasta(c, "Física")
        val t = livre(c)
        assertEquals(g, chamar("GET", "/topicos/livres", token = c.token).corpo[0]["assuntoId"].asString())
        assertEquals("Física", chamar("PUT", "/topicos/$t/assunto", """{"assuntoId":"$p"}""", c.token).corpo["nome"].asString())
        assertEquals(p, chamar("GET", "/topicos/livres", token = c.token).corpo[0]["assuntoId"].asString())
        val m = material(c)
        val doMaterial = dono.queryForObject("SELECT t.id::text FROM topico t JOIN topico_fonte f ON f.topico_id=t.id WHERE f.material_id=?::uuid LIMIT 1", String::class.java, m)!!
        assertEquals(200, chamar("PUT", "/topicos/$doMaterial/assunto", """{"assuntoId":"$p"}""", c.token).status)
        assertEquals(setOf(g, p), assuntosDosTopicos(m).toSet(), "o material fica com tópicos em duas pastas")
        assertEquals(setOf(g, p), materialDto(c, m)["assuntoIds"].toList().map { it.asString() }.toSet())
    }

    @Test
    fun `material ainda sem topicos nao muda de pasta`() {
        val c = conta(); val p = pasta(c, "Física"); val id = UUID.randomUUID()
        dono.update("INSERT INTO material(id,usuario_id,nome_arquivo,tipo_arquivo,tamanho_bytes,hash_arquivo,caminho_arquivo,status_processamento) VALUES (?,?,'a.pdf','application/pdf',1,?,'x','pendente')", id, c.id, UUID.randomUUID().toString())
        val r = chamar("PUT", "/materiais/$id/assunto", """{"assuntoId":"$p"}""", c.token)
        assertEquals(409, r.status); assertEquals("material_nao_pronto", r.corpo["codigo"].asString())
        assertEquals(null, materialDto(c, id.toString())["assuntoId"].let { if (it == null || it.isNull) null else it.asString() })
    }

    @Test
    fun `excluir pasta devolve os estudos para Geral sem perder nada`() {
        val c = conta(); val g = geral(c); val p = pasta(c, "Temporária")
        val m = material(c, p); val t = livre(c, assunto = p)
        val tem = dono.queryForObject("SELECT count(*) FROM topico WHERE usuario_id = ?", Int::class.java, c.id)
        assertEquals(204, chamar("DELETE", "/assuntos/$p", token = c.token).status)
        assertEquals(tem, dono.queryForObject("SELECT count(*) FROM topico WHERE usuario_id = ?", Int::class.java, c.id), "nenhum tópico some")
        assertEquals(setOf(g), assuntosDosTopicos(m).toSet())
        assertEquals(g, chamar("GET", "/topicos/livres", token = c.token).corpo.toList().first { it["id"].asString() == t }["assuntoId"].asString())
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM assunto WHERE id = ?::uuid", Int::class.java, p))
        assertEquals(404, chamar("DELETE", "/assuntos/$p", token = c.token).status)
    }

    @Test
    fun `A nao mexe nas pastas nem nos estudos de B`() {
        val a = conta(); val b = conta(); val pb = pasta(b, "Pasta de B"); val mb = material(b, pb); val tb = livre(b)
        assertEquals(404, chamar("PUT", "/assuntos/$pb", """{"nome":"Invadida"}""", a.token).status)
        assertEquals(404, chamar("DELETE", "/assuntos/$pb", token = a.token).status)
        assertEquals(404, chamar("PUT", "/materiais/$mb/assunto", """{"assuntoId":"${geral(a)}"}""", a.token).status)
        assertEquals(404, chamar("PUT", "/topicos/$tb/assunto", """{"assuntoId":"${geral(a)}"}""", a.token).status)
        assertEquals(404, chamar("PUT", "/materiais/$mb", """{"titulo":"x"}""", a.token).status)
        assertEquals(404, chamar("POST", "/materiais/$mb/sugerir-nome", token = a.token).status)
        val pa = pasta(a, "Pasta de A"); val ma = material(a)
        assertEquals("assunto_nao_encontrado", chamar("PUT", "/materiais/$ma/assunto", """{"assuntoId":"$pb"}""", a.token).corpo["codigo"].asString(), "nem para a pasta de B")
        assertEquals(false, chamar("GET", "/assuntos", token = a.token).corpo.toList().any { it["id"].asString() == pb })
        assertEquals("Pasta de B", dono.queryForObject("SELECT nome FROM assunto WHERE id = ?::uuid", String::class.java, pb))
        assertEquals(setOf(pa).size, 1)
    }

    @Test
    fun `renomear o estudo guarda o titulo, em branco volta ao nome do arquivo`() {
        val c = conta(); val m = material(c)
        assertEquals(null, materialDto(c, m)["titulo"].let { if (it == null || it.isNull) null else it.asString() })
        val r = chamar("PUT", "/materiais/$m", """{"titulo":"  Segunda Guerra  "}""", c.token)
        assertEquals(200, r.status); assertEquals("Segunda Guerra", r.corpo["titulo"].asString()); assertEquals("apostila.pdf", r.corpo["nomeArquivo"].asString())
        assertEquals("Segunda Guerra", chamar("GET", "/materiais", token = c.token).corpo[0]["titulo"].asString())
        assertEquals(400, chamar("PUT", "/materiais/$m", """{"titulo":"${"x".repeat(121)}"}""", c.token).status)
        assertEquals(true, chamar("PUT", "/materiais/$m", """{"titulo":"   "}""", c.token).corpo["titulo"].isNull)
        assertEquals(true, chamar("PUT", "/materiais/$m", "{}", c.token).corpo["titulo"].isNull)
    }

    @Test
    fun `o topico de material traz o nome do estudo nas fontes`() {
        val c = conta(); val m = material(c)
        val t = dono.queryForObject("SELECT t.id::text FROM topico t JOIN topico_fonte f ON f.topico_id=t.id WHERE f.material_id=?::uuid LIMIT 1", String::class.java, m)!!
        assertEquals(true, chamar("GET", "/topicos/$t", token = c.token).corpo["fontes"][0]["titulo"].isNull)
        chamar("PUT", "/materiais/$m", """{"titulo":"Catalogação 1"}""", c.token)
        val f = chamar("GET", "/topicos/$t", token = c.token).corpo["fontes"][0]
        assertEquals("Catalogação 1", f["titulo"].asString()); assertEquals("apostila.pdf", f["nomeArquivo"].asString())
    }

    @Test
    fun `sugestao de nome da IA nao grava, deixa registro, respeita limite e falha com 503`() {
        val c = conta(); val m = material(c)
        val antes = python.chamadas.count { it == "nome" }
        val r = chamar("POST", "/materiais/$m/sugerir-nome", token = c.token)
        assertEquals(200, r.status); assertEquals("Nome sugerido de teste", r.corpo["nome"].asString())
        assertEquals(antes + 1, python.chamadas.count { it == "nome" })
        assertEquals(listOf("concluida:25"), registros(c))
        assertEquals(true, materialDto(c, m)["titulo"].isNull, "a sugestão só vira nome se o estudante salvar")
        val corpo = python.corpos.last { it.startsWith("nome:") }
        assertTrue(corpo.contains("Causas") && corpo.contains("Batalhas"), "vai com os nomes dos tópicos")

        python.statusGeracao = 500
        assertEquals("ia_indisponivel", chamar("POST", "/materiais/$m/sugerir-nome", token = c.token).corpo["codigo"].asString())
        assertTrue(registros(c).last().startsWith("falhou"))
        python.statusGeracao = null
        repeat(30) { dono.update("INSERT INTO registro_geracao(id,usuario_id,material_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual,finalizada_em) VALUES (gen_random_uuid(),?,?::uuid,'nome_estudo','material','m','concluida',0,0,0,0,0,0,100,now())", c.id, m) }
        val n = python.chamadas.count { it == "nome" }
        assertEquals("limite_operacoes", chamar("POST", "/materiais/$m/sugerir-nome", token = c.token).corpo["codigo"].asString())
        assertEquals(n, python.chamadas.count { it == "nome" })
    }

    @Test
    fun `material que nao esta pronto nao recebe sugestao de nome`() {
        val c = conta(); val id = UUID.randomUUID()
        dono.update("INSERT INTO material(id,usuario_id,nome_arquivo,tipo_arquivo,tamanho_bytes,hash_arquivo,caminho_arquivo,status_processamento) VALUES (?,?,'a.pdf','application/pdf',1,?,'x','processando')", id, c.id, UUID.randomUUID().toString())
        assertEquals("material_nao_pronto", chamar("POST", "/materiais/$id/sugerir-nome", token = c.token).corpo["codigo"].asString())
    }

    @Test
    fun `sem login as rotas de organizacao respondem 401`() {
        val id = UUID.randomUUID()
        assertEquals(401, chamar("POST", "/assuntos", """{"nome":"x"}""").status)
        assertEquals(401, chamar("PUT", "/assuntos/$id", """{"nome":"x"}""").status)
        assertEquals(401, chamar("DELETE", "/assuntos/$id").status)
        assertEquals(401, chamar("PUT", "/materiais/$id/assunto", "{}").status)
        assertEquals(401, chamar("PUT", "/materiais/$id", "{}").status)
        assertEquals(401, chamar("POST", "/materiais/$id/sugerir-nome").status)
    }
}
