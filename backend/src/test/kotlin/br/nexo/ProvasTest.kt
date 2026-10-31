package br.nexo

import br.nexo.estudo.CotaProva
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Divisão da quantidade pedida entre os assuntos da prova. */
class CotaProvaTest {
    @Test
    fun `divide igualmente e a sobra vai uma a uma aos primeiros assuntos`() {
        assertEquals(listOf(4, 3, 3), CotaProva.dividir(10, listOf(9, 9, 9)))
        assertEquals(listOf(5, 5), CotaProva.dividir(10, listOf(9, 9)))
        assertEquals(listOf(10), CotaProva.dividir(10, listOf(30)))
    }

    @Test
    fun `o que um assunto nao tem para dar e redistribuido entre os demais`() {
        assertEquals(listOf(1, 5, 4), CotaProva.dividir(10, listOf(1, 9, 9)))
        assertEquals(listOf(2, 2, 6), CotaProva.dividir(10, listOf(2, 2, 9)))
        assertEquals(listOf(2, 2), CotaProva.dividir(10, listOf(2, 2)), "sem questões bastantes, devolve tudo o que há")
    }
}

/** Provas por escopo: montagem (cota, tópico-limite, grupo), resposta, tempo, refazer e isolamento entre contas. */
class ProvasTest : BaseIntegracao() {
    @Autowired lateinit var relogio: java.time.Clock
    private val rel get() = relogio as RelogioTeste

    @AfterEach fun soltarRelogio() = rel.liberar()

    class Conta(val token: String, val id: UUID)

    private fun conta(): Conta {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        return Conta(token, dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!)
    }

    private fun pasta(c: Conta, nome: String) = chamar("POST", "/assuntos", """{"nome":"$nome"}""", c.token).corpo["id"].asString()
    private fun topico(c: Conta, pasta: String) = chamar("POST", "/topicos", """{"nome":"Tema ${UUID.randomUUID()}","assuntoId":"$pasta"}""", c.token).corpo["id"].asString()

    /** Grava [n] questões no tópico direto no banco (a geração tem os seus testes); a correta é sempre o índice 1. */
    private fun questoes(c: Conta, topico: String, n: Int): List<String> = (1..n).map {
        val id = UUID.randomUUID()
        dono.update(
            "INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding) VALUES (?,?,?::uuid,?,'[\"a\",\"b\",\"c\"]',1,?,array_fill(0.01::real, ARRAY[1536])::vector,'m')",
            id, c.id, topico, "Q $id", UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
        )
        id.toString()
    }

    /** Pasta com um tópico por quantidade pedida, cada tópico com essa quantidade de questões; devolve (pasta, tópicos). */
    private fun pastaCom(c: Conta, nome: String, vararg porTopico: Int): Pair<String, List<String>> {
        val p = pasta(c, nome)
        return p to porTopico.map { n -> topico(c, p).also { questoes(c, it, n) } }
    }

    private fun pedir(c: Conta, assuntos: String, quantidade: Int, separadas: Boolean = false, limite: Int? = null) =
        chamar("POST", "/provas", """{"assuntos":$assuntos,"quantidade":$quantidade,"separadas":$separadas${limite?.let { ""","limiteMinutos":$it""" } ?: ""}}""", c.token)

    private fun escopo(pasta: String, limite: String? = null) = """{"assuntoId":"$pasta"${limite?.let { ""","topicoLimiteId":"$it"""" } ?: ""}}"""
    private fun detalhe(c: Conta, prova: String) = chamar("GET", "/provas/$prova", token = c.token)
    private fun responder(c: Conta, questao: String, prova: String?, alt: Int = 1) =
        chamar("POST", "/questoes/$questao/respostas", """{"alternativa":$alt${prova?.let { ""","provaId":"$it"""" } ?: ""}}""", c.token)
    private fun questoesDaProva(prova: String) = dono.queryForList("SELECT questao_id::text FROM prova_questao WHERE prova_id = ?::uuid ORDER BY ordem", String::class.java, prova).filterNotNull()

    @Test
    fun `prova mista divide a quantidade entre os assuntos e respeita o topico limite`() {
        val c = conta()
        val (hist, ht) = pastaCom(c, "História", 4, 4, 4)
        val (fis, _) = pastaCom(c, "Física", 6)
        val r = pedir(c, "[${escopo(hist, ht[1])},${escopo(fis)}]", 9)
        assertEquals(201, r.status); assertTrue(r.corpo["grupoId"].isNull); assertEquals(1, r.corpo["provas"].size())
        val prova = r.corpo["provas"][0]
        assertEquals("gerada", prova["status"].asString()); assertEquals(9, prova["quantidade"].asInt())
        val porAssunto = dono.queryForList(
            "SELECT t.assunto_id::text AS a, count(*) AS n FROM prova_questao pq JOIN questao q ON q.id = pq.questao_id JOIN topico t ON t.id = q.topico_id WHERE pq.prova_id = ?::uuid GROUP BY 1",
            prova["id"].asString(),
        ).associate { it["a"] as String to (it["n"] as Long).toInt() }
        assertEquals(mapOf(hist to 5, fis to 4), porAssunto, "9 = 5 + 4: a sobra da divisão vai ao primeiro assunto")
        val fora = dono.queryForObject(
            "SELECT count(*) FROM prova_questao pq JOIN questao q ON q.id = pq.questao_id WHERE pq.prova_id = ?::uuid AND q.topico_id = ?::uuid", Int::class.java, prova["id"].asString(), ht[2],
        )
        assertEquals(0, fora, "nada do tópico depois do limite")
        assertEquals(setOf(hist, fis), prova["assuntos"].toList().map { it["id"].asString() }.toSet())
        assertEquals(ht[1], prova["assuntos"].first { it["id"].asString() == hist }["topicoLimiteId"].asString())
    }

    @Test
    fun `assunto com poucas questoes tem a sobra redistribuida e a ordem das questoes e sorteada`() {
        val c = conta()
        val (a, _) = pastaCom(c, "Poucas", 1)
        val (b, _) = pastaCom(c, "Muitas", 20)
        val prova = pedir(c, "[${escopo(a)},${escopo(b)}]", 10).corpo["provas"][0]["id"].asString()
        val q = questoesDaProva(prova)
        assertEquals(10, q.size)
        val doA = dono.queryForObject("SELECT count(*) FROM prova_questao pq JOIN questao x ON x.id = pq.questao_id JOIN topico t ON t.id = x.topico_id WHERE pq.prova_id = ?::uuid AND t.assunto_id = ?::uuid", Int::class.java, prova, a)
        assertEquals(1, doA)
        assertEquals((1..10).toList(), dono.queryForList("SELECT ordem FROM prova_questao WHERE prova_id = ?::uuid ORDER BY ordem", Int::class.java, prova))
    }

    @Test
    fun `provas separadas recebem a quantidade total, o mesmo grupo e aparecem juntas na lista`() {
        val c = conta()
        val (a, _) = pastaCom(c, "A", 6)
        val (b, _) = pastaCom(c, "B", 6)
        val r = pedir(c, "[${escopo(a)},${escopo(b)}]", 5, separadas = true)
        assertEquals(201, r.status)
        val grupo = r.corpo["grupoId"].asString()
        val provas = r.corpo["provas"].toList()
        assertEquals(2, provas.size)
        provas.forEach { assertEquals(5, it["quantidade"].asInt()); assertEquals(grupo, it["grupoId"].asString()); assertEquals(1, it["assuntos"].size()) }
        assertEquals(2, dono.queryForObject("SELECT count(*) FROM prova WHERE grupo_id = ?::uuid", Int::class.java, grupo))
        val lista = chamar("GET", "/provas", token = c.token).corpo.toList()
        assertEquals(2, lista.count { it["grupoId"].asString() == grupo })
        // um só assunto "separado" é prova única: sem grupo
        assertTrue(pedir(c, "[${escopo(a)}]", 3, separadas = true).corpo["grupoId"].isNull)
    }

    @Test
    fun `questoes insuficientes respondem 409 com o total disponivel e nada e gravado`() {
        val c = conta()
        val (a, _) = pastaCom(c, "A", 3)
        val (b, _) = pastaCom(c, "B", 2)
        val antes = dono.queryForObject("SELECT count(*) FROM prova WHERE usuario_id = ?", Int::class.java, c.id)
        val r = pedir(c, "[${escopo(a)},${escopo(b)}]", 6)
        assertEquals(409, r.status); assertEquals("questoes_insuficientes", r.corpo["codigo"].asString()); assertEquals(5, r.corpo["disponiveis"].asInt()); assertEquals(6, r.corpo["pedidas"].asInt())
        val s = pedir(c, "[${escopo(a)},${escopo(b)}]", 3, separadas = true)
        assertEquals("questoes_insuficientes", s.corpo["codigo"].asString(), "separadas: cada assunto precisa ter a quantidade toda"); assertEquals(2, s.corpo["disponiveis"].asInt())
        assertEquals(antes, dono.queryForObject("SELECT count(*) FROM prova WHERE usuario_id = ?", Int::class.java, c.id))
    }

    @Test
    fun `pedido invalido e recusado`() {
        val c = conta(); val (a, t) = pastaCom(c, "A", 5); val (b, tb) = pastaCom(c, "B", 5)
        assertEquals(400, pedir(c, "[]", 3).status)
        assertEquals(400, pedir(c, "[${escopo(a)}]", 0).status)
        assertEquals(400, pedir(c, "[${escopo(a)}]", 51).status, "máximo configurado")
        assertEquals(400, pedir(c, "[${escopo(a)},${escopo(a)}]", 3).status, "assunto repetido")
        assertEquals(400, pedir(c, "[${escopo(a)}]", 3, limite = 0).status)
        assertEquals(400, pedir(c, "[${escopo(a)}]", 3, limite = 481).status)
        assertEquals("topico_limite_invalido", pedir(c, "[${escopo(a, tb[0])}]", 3).corpo["codigo"].asString(), "tópico-limite de outro assunto")
        assertEquals(404, pedir(c, "[${escopo(UUID.randomUUID().toString())}]", 3).status)
        assertEquals(201, pedir(c, "[${escopo(b, tb[0])}]", 3).status)
        assertTrue(t.isNotEmpty())
    }

    @Test
    fun `responder na prova esconde o gabarito ate responder, agenda a revisao e conclui no fim`() {
        val c = conta(); val (a, _) = pastaCom(c, "A", 3)
        val prova = pedir(c, "[${escopo(a)}]", 3).corpo["provas"][0]["id"].asString()
        val d = detalhe(c, prova).corpo
        assertEquals(3, d["questoes"].size())
        d["questoes"].forEach { assertTrue(it["respostaCorreta"].isNull); assertEquals(false, it["respondida"].asBoolean()) }
        val q = d["questoes"].toList().map { it["id"].asString() }
        val r = responder(c, q[0], prova, 1)
        assertEquals(200, r.status); assertTrue(r.corpo["correta"].asBoolean())
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM progresso_questao WHERE questao_id = ?::uuid", Int::class.java, q[0]), "o agendamento de revisão vale para respostas de prova")
        val meio = detalhe(c, prova).corpo
        assertEquals("em_andamento", meio["prova"]["status"].asString()); assertNotEquals(null, meio["prova"]["iniciadaEm"].asStringOrNull())
        val primeira = meio["questoes"][0]
        assertTrue(primeira["respondida"].asBoolean()); assertEquals(1, primeira["respostaCorreta"].asInt()); assertEquals(1, primeira["alternativaEscolhida"].asInt())
        assertTrue(meio["questoes"][1]["respostaCorreta"].isNull)
        assertEquals("ja_respondida", responder(c, q[0], prova).corpo["codigo"].asString())
        responder(c, q[1], prova, 0); responder(c, q[2], prova, 1)
        val fim = detalhe(c, prova).corpo["prova"]
        assertEquals("concluida", fim["status"].asString()); assertEquals(3, fim["respondidas"].asInt()); assertEquals(2, fim["acertos"].asInt()); assertNotEquals(null, fim["concluidaEm"].asStringOrNull())
        assertEquals("prova_encerrada", responder(c, q[0], prova).corpo["codigo"].asString())
        assertEquals(3, dono.queryForObject("SELECT count(*) FROM resposta WHERE prova_id = ?::uuid", Int::class.java, prova))
    }

    @Test
    fun `questao de fora da prova e prova inexistente respondem 404`() {
        val c = conta(); val (a, _) = pastaCom(c, "A", 2)
        val prova = pedir(c, "[${escopo(a)}]", 2).corpo["provas"][0]["id"].asString()
        val fora = questoes(c, topico(c, a), 1)[0]
        assertEquals(404, responder(c, fora, prova).status)
        assertEquals(404, responder(c, questoesDaProva(prova)[0], UUID.randomUUID().toString()).status)
    }

    @Test
    fun `limite de tempo conta a partir do inicio e encerra a prova`() {
        val c = conta(); val (a, _) = pastaCom(c, "A", 3)
        rel.fixar("2026-03-02T12:00:00Z")
        val criada = pedir(c, "[${escopo(a)}]", 3, limite = 30).corpo["provas"][0]
        val prova = criada["id"].asString()
        assertEquals(30, criada["limiteMinutos"].asInt()); assertTrue(criada["prazo"].isNull, "o relógio só roda depois de iniciar")
        rel.fixar("2026-03-02T12:10:00Z")
        val iniciada = chamar("POST", "/provas/$prova/iniciar", token = c.token).corpo
        assertEquals("em_andamento", iniciada["prova"]["status"].asString())
        assertEquals("2026-03-02T12:10:00Z", iniciada["prova"]["iniciadaEm"].asString()); assertEquals("2026-03-02T12:40:00Z", iniciada["prova"]["prazo"].asString())
        rel.fixar("2026-03-02T12:20:00Z")
        assertEquals("2026-03-02T12:10:00Z", chamar("POST", "/provas/$prova/iniciar", token = c.token).corpo["prova"]["iniciadaEm"].asString(), "iniciar de novo não reinicia o relógio")
        val q = questoesDaProva(prova)
        assertEquals(200, responder(c, q[0], prova).status)
        rel.fixar("2026-03-02T12:41:00Z")
        val r = responder(c, q[1], prova)
        assertEquals(409, r.status); assertEquals("prova_encerrada", r.corpo["codigo"].asString())
        val fim = detalhe(c, prova).corpo["prova"]
        assertEquals("concluida", fim["status"].asString()); assertEquals("2026-03-02T12:40:00Z", fim["concluidaEm"].asString(), "conclui no fim do prazo, não na hora em que alguém olhou")
        assertEquals(1, fim["respondidas"].asInt())
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM resposta WHERE questao_id = ?::uuid", Int::class.java, q[1]))
    }

    @Test
    fun `a lista tambem conclui a prova que passou do tempo`() {
        val c = conta(); val (a, _) = pastaCom(c, "A", 2)
        rel.fixar("2026-03-02T12:00:00Z")
        val prova = pedir(c, "[${escopo(a)}]", 2, limite = 5).corpo["provas"][0]["id"].asString()
        chamar("POST", "/provas/$prova/iniciar", token = c.token)
        rel.fixar("2026-03-02T12:04:59Z")
        assertEquals("em_andamento", chamar("GET", "/provas", token = c.token).corpo[0]["status"].asString(), "ainda dentro do prazo")
        rel.fixar("2026-03-02T12:05:00Z")
        val item = chamar("GET", "/provas", token = c.token).corpo[0]
        assertEquals("concluida", item["status"].asString()); assertEquals("2026-03-02T12:05:00Z", item["concluidaEm"].asString())
    }

    @Test
    fun `refazer cria outra prova com as mesmas questoes e so depois de terminar`() {
        val c = conta(); val (a, _) = pastaCom(c, "A", 4)
        val prova = pedir(c, "[${escopo(a)}]", 4, limite = 20).corpo["provas"][0]["id"].asString()
        assertEquals("prova_nao_concluida", chamar("POST", "/provas/$prova/refazer", token = c.token).corpo["codigo"].asString())
        questoesDaProva(prova).forEach { responder(c, it, prova, 0) }
        val nova = chamar("POST", "/provas/$prova/refazer", token = c.token)
        assertEquals(201, nova.status)
        assertNotEquals(prova, nova.corpo["id"].asString())
        assertEquals("gerada", nova.corpo["status"].asString()); assertEquals(0, nova.corpo["respondidas"].asInt()); assertEquals(20, nova.corpo["limiteMinutos"].asInt())
        assertEquals(questoesDaProva(prova).sorted(), questoesDaProva(nova.corpo["id"].asString()).sorted())
        assertEquals(1, nova.corpo["assuntos"].size())
        assertEquals("concluida", detalhe(c, prova).corpo["prova"]["status"].asString(), "a original não muda")
        questoesDaProva(nova.corpo["id"].asString()).forEach { assertEquals(200, responder(c, it, nova.corpo["id"].asString()).status) }
    }

    @Test
    fun `prova bloqueia mover o topico limite para outra pasta`() {
        val c = conta(); val (a, t) = pastaCom(c, "A", 2, 2); val outra = pasta(c, "Outra")
        pedir(c, "[${escopo(a, t[1])}]", 2)
        val r = chamar("PUT", "/topicos/${t[1]}/assunto", """{"assuntoId":"$outra"}""", c.token)
        assertEquals(409, r.status); assertEquals("topico_limite_de_prova", r.corpo["codigo"].asString()); assertEquals(1, r.corpo["provas"].asInt())
        assertEquals(200, chamar("PUT", "/topicos/${t[0]}/assunto", """{"assuntoId":"$outra"}""", c.token).status, "só o tópico-limite fica preso")
        assertEquals("assunto_em_uso", chamar("DELETE", "/assuntos/$a", token = c.token).corpo["codigo"].asString())
    }

    @Test
    fun `excluir a prova mantem as respostas no historico e solta o topico limite e a pasta`() {
        val c = conta(); val (a, t) = pastaCom(c, "A", 2, 2); val outra = pasta(c, "Outra")
        val prova = pedir(c, "[${escopo(a, t[1])}]", 3).corpo["provas"][0]["id"].asString()
        val q = questoesDaProva(prova)
        responder(c, q[0], prova); responder(c, q[1], prova)
        assertEquals(409, chamar("PUT", "/topicos/${t[1]}/assunto", """{"assuntoId":"$outra"}""", c.token).status)
        assertEquals(204, chamar("DELETE", "/provas/$prova", token = c.token).status)
        assertEquals(404, detalhe(c, prova).status)
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM prova_questao WHERE prova_id = ?::uuid", Int::class.java, prova))
        assertEquals(2, dono.queryForObject("SELECT count(*) FROM resposta WHERE questao_id IN (?::uuid, ?::uuid) AND prova_id IS NULL", Int::class.java, q[0], q[1]), "as respostas viram avulsas")
        assertEquals(200, chamar("PUT", "/topicos/${t[1]}/assunto", """{"assuntoId":"$outra"}""", c.token).status)
        assertEquals(204, chamar("DELETE", "/assuntos/$a", token = c.token).status, "a pasta deixa de estar em uso")
        assertEquals(404, chamar("DELETE", "/provas/$prova", token = c.token).status)
    }

    @Test
    fun `uma conta nao monta, ve, inicia, responde nem refaz prova de outra`() {
        val a = conta(); val b = conta()
        val (pa, ta) = pastaCom(a, "A", 3)
        val (pb, _) = pastaCom(b, "B", 3)
        val prova = pedir(a, "[${escopo(pa)}]", 3).corpo["provas"][0]["id"].asString()
        val q = questoesDaProva(prova)
        responder(a, q[0], prova)
        assertEquals(404, pedir(b, "[${escopo(pa)}]", 1).status, "assunto de A")
        val alheio = pedir(b, "[${escopo(pb, ta[0])}]", 1)
        assertEquals(400, alheio.status); assertEquals("topico_limite_invalido", alheio.corpo["codigo"].asString(), "tópico-limite de A")
        assertEquals(404, detalhe(b, prova).status)
        assertEquals(404, chamar("POST", "/provas/$prova/iniciar", token = b.token).status)
        assertEquals(404, chamar("POST", "/provas/$prova/refazer", token = b.token).status)
        assertEquals(404, chamar("DELETE", "/provas/$prova", token = b.token).status)
        assertEquals(404, responder(b, q[1], prova).status)
        assertEquals(404, responder(b, q[1], null).status, "nem avulsa")
        assertEquals(0, chamar("GET", "/provas", token = b.token).corpo.size())
        assertEquals(1, chamar("GET", "/provas", token = a.token).corpo.size())
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM resposta WHERE prova_id = ?::uuid", Int::class.java, prova), "só a resposta de A")
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM prova WHERE id = ?::uuid", Int::class.java, prova), "a prova de A continua")
    }

    private fun tools.jackson.databind.JsonNode.asStringOrNull(): String? = if (isNull) null else asString()
}
