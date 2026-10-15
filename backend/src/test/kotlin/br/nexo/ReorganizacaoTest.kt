package br.nexo

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Reorganizar com IA": rascunho validado, sem gravar; registro da operação; congelados; isolamento. */
class ReorganizacaoTest : BaseIntegracao() {
    class Conta(val email: String, val token: String, val id: UUID)

    private fun conta(): Conta {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        return Conta(email, token, dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!)
    }

    /** Material de 10 trechos com dois tópicos propostos pelo "modelo": A = 1..5 e B = 6..10. */
    private fun material(c: Conta): Pair<String, List<String>> {
        python.propostaTopicos = """[{"nome":"A","inicio":1,"fim":5},{"nome":"B","inicio":6,"fim":10}]"""
        val id = enviarArquivo(c.token, "m.pdf", "%PDF-1.4 PAGINAS=3 TRECHOS=10 nonce=${UUID.randomUUID()}".toByteArray()).corpo["materialId"].asString()
        return id to chamar("GET", "/materiais/$id/topicos", token = c.token).corpo.toList().map { it["id"].asString() }
    }

    private fun reorganizar(c: Conta, id: String) = chamar("POST", "/materiais/$id/topicos/reorganizar", token = c.token)
    private fun nomes(c: Conta) = dono.queryForList("SELECT nome FROM topico WHERE usuario_id = ? ORDER BY ordem", String::class.java, c.id)
    private fun registros(id: String) = dono.queryForList("SELECT status || ':' || tokens_entrada || ':' || tokens_saida FROM registro_geracao WHERE material_id = ?::uuid AND operacao = 'sugestao_topicos' ORDER BY criado_em", String::class.java, id)

    @AfterEach fun restaurar() {
        python.propostaTopicos = "[]"; python.propostaReorganizar = "[]"; python.statusReorganizar = null; python.atrasoReorganizarMs = 0; python.falharProcessar = false
    }

    @Test
    fun `proposta valida volta como rascunho, registra a operacao e nao grava nada`() {
        val c = conta(); val (id, ids) = material(c)
        python.propostaReorganizar = """[{"nome":"Tudo junto","inicio":1,"fim":10}]"""
        val r = reorganizar(c, id)
        assertEquals(200, r.status, r.corpo.toString())
        val t = r.corpo["topicos"].toList()
        assertEquals(1, t.size); assertEquals("Tudo junto", t[0]["nome"].asString()); assertTrue(t[0]["id"].isNull, "faixa nova: sem id")
        assertTrue(r.corpo["mudou"].asBoolean()); assertEquals(21, r.corpo["tokensEntrada"].asInt()); assertEquals("linguagem-falso", r.corpo["modelo"].asString())
        assertEquals(listOf("A", "B"), nomes(c), "nada foi gravado: o usuário ainda precisa confirmar")
        assertEquals("concluida:21:9", registros(id).last())
        assertEquals(2, registros(id).size, "um registro da sugestão inicial e um da reorganização")
        assertTrue(ids.isNotEmpty())
    }

    @Test
    fun `faixas iguais mantem o id e so renomear conta como mudanca`() {
        val c = conta(); val (id, ids) = material(c)
        python.propostaReorganizar = """[{"nome":"A renomeado","inicio":1,"fim":5},{"nome":"B","inicio":6,"fim":10}]"""
        val r = reorganizar(c, id)
        assertEquals(ids, r.corpo["topicos"].toList().map { it["id"].asString() })
        assertTrue(r.corpo["mudou"].asBoolean())
        python.propostaReorganizar = """[{"nome":"A","inicio":1,"fim":5},{"nome":"B","inicio":6,"fim":10}]"""
        assertFalse(reorganizar(c, id).corpo["mudou"].asBoolean(), "a IA manteve a divisão")
    }

    @Test
    fun `o rascunho pode ser confirmado pelo PUT de sempre`() {
        val c = conta(); val (id, ids) = material(c)
        python.propostaReorganizar = """[{"nome":"Parte 1","inicio":1,"fim":3},{"nome":"Parte 2","inicio":4,"fim":10}]"""
        val rascunho = reorganizar(c, id).corpo["topicos"].toList()
        val corpo = """{"topicos":[${rascunho.joinToString(",") { """{"id":${if (it["id"].isNull) "null" else "\"${it["id"].asString()}\""},"nome":"${it["nome"].asString()}","inicio":${it["inicio"].asInt()},"fim":${it["fim"].asInt()}}""" }}]}"""
        assertEquals(200, chamar("PUT", "/materiais/$id/topicos", corpo, c.token).status)
        assertEquals(listOf("Parte 1", "Parte 2"), nomes(c))
        assertTrue(ids.isNotEmpty())
    }

    @Test
    fun `proposta invalida e recusada, os tokens ficam no registro e nada muda`() {
        val c = conta(); val (id, _) = material(c)
        for (ruim in listOf("""[{"nome":"A","inicio":1,"fim":4},{"nome":"B","inicio":6,"fim":10}]""", """[{"nome":"A","inicio":1,"fim":5}]""", """[{"nome":" ","inicio":1,"fim":10}]""", "[]")) {
            python.propostaReorganizar = ruim
            val r = reorganizar(c, id)
            assertEquals(422, r.status, ruim); assertEquals("proposta_ia_invalida", r.corpo["codigo"].asString())
        }
        assertEquals(listOf("A", "B"), nomes(c))
        assertTrue(registros(id).drop(1).all { it == "falhou:21:9" }, registros(id).toString())
    }

    @Test
    fun `topico com conteudo precisa continuar com a mesma faixa na proposta`() {
        val c = conta(); val (id, ids) = material(c)
        dono.update("INSERT INTO resumo(id,usuario_id,topico_id,conteudo) VALUES (?,?,?::uuid,'r')", UUID.randomUUID(), c.id, ids[0])   // A (1..5) congelado
        python.propostaReorganizar = """[{"nome":"A","inicio":1,"fim":3},{"nome":"B","inicio":4,"fim":10}]"""   // mexe no congelado
        assertEquals("proposta_ia_invalida", reorganizar(c, id).corpo["codigo"].asString())
        python.propostaReorganizar = """[{"nome":"A","inicio":1,"fim":5},{"nome":"B1","inicio":6,"fim":8},{"nome":"B2","inicio":9,"fim":10}]"""   // só divide o livre
        val r = reorganizar(c, id)
        assertEquals(200, r.status, r.corpo.toString())
        assertEquals(listOf(true, false, false), r.corpo["topicos"].toList().map { it["temConteudo"].asBoolean() })
        assertEquals(ids[0], r.corpo["topicos"][0]["id"].asString())
    }

    @Test
    fun `falha do modelo responde 503 e registra a falha`() {
        val c = conta(); val (id, _) = material(c)
        python.statusReorganizar = 500
        val r = reorganizar(c, id)
        assertEquals(503, r.status); assertEquals("ia_indisponivel", r.corpo["codigo"].asString())
        assertEquals("falhou:0:0", registros(id).last())
        assertEquals(listOf("A", "B"), nomes(c))
    }

    @Test
    fun `material de outra conta ou nao pronto nao reorganiza`() {
        val a = conta(); val b = conta(); val (id, _) = material(a)
        assertEquals(404, reorganizar(b, id).status)
        python.falharProcessar = true
        val falho = enviarArquivo(a.token, "f.pdf", "%PDF-1.4 PAGINAS=2 nonce=${UUID.randomUUID()}".toByteArray()).corpo["materialId"].asString()
        assertEquals(409, reorganizar(a, falho).status)
        assertEquals(0, registros(id).count { it!!.startsWith("falhou") })
    }

    @Test
    fun `clique repetido enquanto o primeiro pedido roda nao gasta uma segunda chamada`() {
        val c = conta(); val (id, _) = material(c)
        python.propostaReorganizar = """[{"nome":"A","inicio":1,"fim":5},{"nome":"B","inicio":6,"fim":10}]"""
        python.atrasoReorganizarMs = 700
        val antes = python.chamadas.count { it == "reorganizar" }
        val primeiro = java.util.concurrent.Executors.newSingleThreadExecutor().submit<Resposta> { reorganizar(c, id) }
        Thread.sleep(250)
        val segundo = reorganizar(c, id)
        assertEquals(409, segundo.status); assertEquals("reorganizacao_em_andamento", segundo.corpo["codigo"].asString())
        assertEquals(200, primeiro.get().status)
        assertEquals(1, python.chamadas.count { it == "reorganizar" } - antes)
    }
}
