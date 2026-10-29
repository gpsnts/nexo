package br.nexo

import br.nexo.material.GeracaoDados
import br.nexo.material.hashDoEnunciado
import br.nexo.seguranca.UsuarioAtual
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Resumo, questões e verificação de fidelidade: conteúdo-base restrito à faixa, só aprovadas gravadas, registros, limites e isolamento. */
class GeracaoTest : BaseIntegracao() {
    @Autowired lateinit var gDados: GeracaoDados
    @Autowired lateinit var transacao: TransactionTemplate

    class Conta(val email: String, val token: String, val id: UUID)

    private fun conta(): Conta {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        return Conta(email, token, dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!)
    }

    /** Material de 30 trechos ("Trecho N do material de teste.", ~31 caracteres): A = 1..5 (cabe no contexto de 300) e B = 6..30 (não cabe: usa a busca). */
    private fun material(c: Conta): Triple<String, String, String> {
        python.propostaTopicos = """[{"nome":"A","inicio":1,"fim":5},{"nome":"B","inicio":6,"fim":30}]"""
        val id = enviarArquivo(c.token, "m.pdf", "%PDF-1.4 PAGINAS=3 TRECHOS=30 nonce=${UUID.randomUUID()}".toByteArray()).corpo["materialId"].asString()
        val t = chamar("GET", "/materiais/$id/topicos", token = c.token).corpo.toList().map { it["id"].asString() }
        return Triple(id, t[0], t[1])
    }

    private fun livre(c: Conta) = chamar("POST", "/topicos", """{"nome":"Tema livre"}""", c.token).corpo["id"].asString()
    private fun resumo(c: Conta, t: String) = chamar("POST", "/topicos/$t/resumo", token = c.token)
    private fun questoes(c: Conta, t: String, n: Int = 5) = chamar("POST", "/topicos/$t/questoes", """{"quantidade":$n}""", c.token)
    private fun geracao(c: Conta, id: String) = chamar("GET", "/geracoes/$id", token = c.token).corpo
    private fun registros(t: String, op: String) = dono.queryForList("SELECT status||':'||progresso_percentual||':'||tokens_entrada||':'||tokens_saida||':'||questoes_aprovadas||':'||questoes_rejeitadas||':'||questoes_repetidas FROM registro_geracao WHERE topico_id = ?::uuid AND operacao = ? ORDER BY criado_em", String::class.java, t, op).filterNotNull()
    private fun nQuestoes(t: String) = dono.queryForObject("SELECT count(*) FROM questao WHERE topico_id = ?::uuid", Int::class.java, t)!!
    private fun trechosEnviados(rota: String) = python.corpos.last { it.startsWith("$rota:") }.substringAfter(":").let { mapper.readTree(it)["trechos"].toList().map { n -> n["conteudo"].asString() } }
    private fun numeros(textos: List<String>) = textos.map { Regex("Trecho (\\d+) ").find(it)!!.groupValues[1].toInt() }

    private fun roteiro(vararg enunciados: String) = enunciados.joinToString(",", "[", "]") { """{"enunciado":"$it","correta":"Certa $it","erradas":["E1 $it","E2 $it","E3 $it"],"ordens":[1]}""" }

    @AfterEach fun restaurar() {
        python.propostaTopicos = "[]"; python.statusGeracao = null; python.reprovar = { false }; python.semApoio = false; python.naoSustentada = false; python.roteiroQuestoes.clear(); python.aoReceber = null
    }

    @Test
    fun `dificuldade escolhida vai ao modelo, fica gravada na questao e aparece na lista e na revisao`() {
        val c = conta(); val t = livre(c)
        assertEquals(202, chamar("POST", "/topicos/$t/questoes", """{"quantidade":2,"dificuldade":"dificil"}""", c.token).status)
        assertTrue(python.corpos.last { it.startsWith("questoes:") }.contains("\"dificuldade\":\"dificil\""))
        assertEquals(listOf("dificil", "dificil"), dono.queryForList("SELECT dificuldade FROM questao WHERE topico_id = ?::uuid", String::class.java, t))
        assertEquals(listOf("dificil", "dificil"), chamar("GET", "/topicos/$t/questoes", token = c.token).corpo.toList().map { it["dificuldade"].asString() })
        assertEquals(202, questoes(c, t, 1).status, "sem dificuldade vale média")
        assertTrue(python.corpos.last { it.startsWith("questoes:") }.contains("\"dificuldade\":\"media\""))
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM questao WHERE topico_id = ?::uuid AND dificuldade = 'media'", Int::class.java, t))
        val facil = chamar("POST", "/topicos/$t/questoes", """{"quantidade":1,"dificuldade":"facil"}""", c.token)
        assertEquals(202, facil.status)
        // a revisão devolve o nível de cada questão vencida
        dono.update("INSERT INTO progresso_questao(id,usuario_id,questao_id,repeticoes,intervalo_dias,fator_facilidade,ultima_revisao,proxima_revisao) SELECT gen_random_uuid(), usuario_id, id, 1, 1, 2.50, current_date - 2, current_date - 1 FROM questao WHERE topico_id = ?::uuid AND dificuldade = 'facil'", t)
        assertEquals(listOf("facil"), chamar("GET", "/revisao", token = c.token).corpo["questoes"].toList().map { it["dificuldade"].asString() })
    }

    @Test
    fun `dificuldade invalida e recusada antes de criar registro ou chamar o modelo`() {
        val c = conta(); val t = livre(c)
        python.chamadas.clear()
        for (nivel in listOf("impossivel", "", "DIFICIL")) {
            val r = chamar("POST", "/topicos/$t/questoes", """{"quantidade":1,"dificuldade":"$nivel"}""", c.token)
            assertEquals(400, r.status, nivel); assertEquals("dados_invalidos", r.corpo["codigo"].asString())
        }
        assertTrue("questoes" !in python.chamadas); assertEquals(0, dono.queryForObject("SELECT count(*) FROM registro_geracao WHERE topico_id = ?::uuid", Int::class.java, t))
    }

    // ---------------------------------------------------------------- resumo e conteúdo-base
    @Test
    fun `resumo usa so a faixa do topico, registra a operacao e aparece no topico`() {
        val c = conta(); val (_, a, _) = material(c)
        python.chamadas.clear()
        val r = resumo(c, a)
        assertEquals(202, r.status, r.corpo.toString())
        assertEquals("concluida", geracao(c, r.corpo["registroId"].asString())["status"].asString())
        assertEquals(listOf(1, 2, 3, 4, 5), numeros(trechosEnviados("resumo")), "tópico A cabe no contexto: todos os trechos da faixa, em ordem, e nenhum de fora")
        assertTrue("embeddings" !in python.chamadas, "cabendo no contexto não há busca nem embedding")
        assertEquals(listOf("concluida:100:30:12:0:0:0"), registros(a, "resumo"))
        assertEquals("material", dono.queryForObject("SELECT tipo_entrada FROM registro_geracao WHERE topico_id = ?::uuid", String::class.java, a)!!)
        val t = chamar("GET", "/topicos/$a", token = c.token).corpo
        assertEquals("Resumo de teste", t["resumo"]["conteudo"].asString()); assertEquals(false, t["livre"].asBoolean()); assertEquals(1, t["fontes"].size())
    }

    @Test
    fun `topico que nao cabe no contexto usa busca exata dentro da faixa`() {
        val c = conta(); val (_, _, b) = material(c)
        python.chamadas.clear()
        assertEquals(202, resumo(c, b).status)
        val n = numeros(trechosEnviados("resumo"))
        assertTrue("embeddings" in python.chamadas, "embute o nome do tópico")
        assertTrue(n.isNotEmpty() && n.size < 25, "respeita o orçamento: $n")
        assertTrue(n.all { it in 6..30 }, "nunca sai da faixa do tópico: $n")
        assertEquals(n.sorted(), n, "reordenado pelo material")
        assertEquals(40, registros(b, "resumo").single().split(":")[2].toInt(), "30 do resumo + 10 do embedding do nome, somados ao registro da operação")
    }

    @Test
    fun `topico livre usa conhecimento geral e cada pedido cria um resumo novo`() {
        val c = conta(); val t = livre(c)
        resumo(c, t); resumo(c, t)
        assertTrue(trechosEnviados("resumo").isEmpty())
        assertEquals(2, dono.queryForObject("SELECT count(*) FROM resumo WHERE topico_id = ?::uuid", Int::class.java, t))
        val historico = chamar("GET", "/topicos/$t/resumos", token = c.token).corpo.toList()
        assertEquals(2, historico.size); assertTrue(historico[0]["criadoEm"].asString() >= historico[1]["criadoEm"].asString(), "do mais novo ao mais antigo")
        assertEquals(404, chamar("GET", "/topicos/$t/resumos", token = conta().token).status, "de outra conta")
        assertEquals("topico_livre", dono.queryForList("SELECT tipo_entrada FROM registro_geracao WHERE topico_id = ?::uuid", String::class.java, t).first())
        assertEquals(true, chamar("GET", "/topicos/$t", token = c.token).corpo["livre"].asBoolean())
    }

    // ---------------------------------------------------------------- questões e verificação
    @Test
    fun `questoes aprovadas sao gravadas com apoio nos trechos da faixa e os dois registros fecham`() {
        val c = conta(); val (_, a, _) = material(c)
        python.corpos.clear()
        val r = questoes(c, a, 5)
        assertEquals(202, r.status)
        val g = geracao(c, r.corpo["registroId"].asString())
        assertEquals("concluida", g["status"].asString()); assertEquals(100, g["progresso"].asInt()); assertEquals(5, g["gravadas"].asInt()); assertEquals(r.corpo["verificacaoId"].asString(), g["verificacaoId"].asString())
        assertEquals(5, nQuestoes(a))
        assertTrue(python.corpos.filter { it.startsWith("verificar:") }.all { c -> mapper.readTree(c.substringAfter(":"))["trechos"].size() == 1 }, "o verificador recebe só os trechos citados, não o contexto inteiro (5 trechos)")
        assertEquals(listOf("concluida:100:100:40:0:0:0"), registros(a, "questoes"), "geração 50 + 5 embeddings de enunciado (10 cada) em tokens de entrada")
        val v = registros(a, "verificacao_questao").single().split(":")
        assertEquals(listOf("concluida", "100"), v.take(2)); assertEquals(5, v[4].toInt()); assertEquals(0, v[5].toInt())
        val fora = dono.queryForObject(
            """SELECT count(*) FROM questao_trecho qt JOIN trecho t ON t.id = qt.trecho_id JOIN questao q ON q.id = qt.questao_id
               WHERE q.topico_id = ?::uuid AND t.ordem NOT BETWEEN 1 AND 5""", Int::class.java, a)
        assertEquals(0, fora, "questao_trecho só aponta para trechos da faixa do tópico")
        assertTrue(dono.queryForObject("SELECT count(*) FROM questao_trecho qt JOIN questao q ON q.id = qt.questao_id WHERE q.topico_id = ?::uuid", Int::class.java, a)!! >= 5)
        val lista = chamar("GET", "/topicos/$a/questoes", token = c.token).corpo.toList()
        assertEquals(5, lista.size)
        lista.forEach { q ->
            assertEquals(4, q["alternativas"].size()); assertTrue(q["respostaCorreta"].isNull, "o gabarito só sai depois de responder"); assertEquals(false, q["respondida"].asBoolean())
            val certa = dono.queryForObject("SELECT resposta_correta FROM questao WHERE id = ?::uuid", Int::class.java, q["id"].asString())!!
            assertTrue(q["alternativas"][certa].asString().startsWith("Certa"), "resposta_correta aponta a alternativa certa embaralhada")
        }
        assertEquals(5, chamar("GET", "/topicos/$a", token = c.token).corpo["totalQuestoes"].asInt())
        assertEquals(hashDoEnunciado(lista[0]["enunciado"].asString()), dono.queryForObject("SELECT enunciado_hash FROM questao WHERE id = ?::uuid", String::class.java, lista[0]["id"].asString()))
    }

    @Test
    fun `reprovada e descartada, outra e gerada na rodada seguinte e a taxa de rejeicao sai do registro`() {
        val c = conta(); val (_, a, _) = material(c)
        python.reprovar = { "RUIM" in it }
        python.roteiroQuestoes += roteiro("Boa um", "RUIM um", "Boa dois", "RUIM dois", "Boa tres")
        val g = geracao(c, questoes(c, a, 5).corpo["registroId"].asString())
        assertEquals(5, g["gravadas"].asInt()); assertEquals(2, g["rejeitadas"].asInt()); assertEquals(5, g["aprovadas"].asInt(), "3 na primeira rodada e 2 na segunda")
    }

    @Test
    fun `tentativas esgotadas deixam menos questoes, nada rejeitado e gravado e a taxa e calculavel por SQL`() {
        val c = conta(); val (_, a, _) = material(c)
        python.reprovar = { "RUIM" in it }
        python.roteiroQuestoes += roteiro("Boa um", "RUIM um", "Boa dois", "RUIM dois", "Boa tres")
        python.roteiroQuestoes += roteiro("RUIM tres", "RUIM quatro")
        python.roteiroQuestoes += roteiro("RUIM cinco", "RUIM seis")
        val g = geracao(c, questoes(c, a, 5).corpo["registroId"].asString())
        assertEquals("concluida", g["status"].asString()); assertEquals(3, g["gravadas"].asInt()); assertEquals(3, g["aprovadas"].asInt()); assertEquals(6, g["rejeitadas"].asInt())
        assertEquals(3, nQuestoes(a))
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM questao WHERE topico_id = ?::uuid AND enunciado LIKE '%RUIM%'", Int::class.java, a), "rejeitadas não são armazenadas")
        val taxa = dono.queryForObject("SELECT sum(questoes_rejeitadas)::float / sum(questoes_aprovadas + questoes_rejeitadas) FROM registro_geracao WHERE operacao = 'verificacao_questao' AND topico_id = ?::uuid", Double::class.java, a)!!
        assertEquals(6.0 / 9.0, taxa, 1e-9); assertEquals(taxa, g["taxaRejeicao"].asDouble(), 1e-9)
    }

    @Test
    fun `aprovacao exige apoio nos trechos e resposta unica, salvo em topico livre`() {
        val c = conta(); val (_, a, _) = material(c)
        python.semApoio = true
        val g1 = geracao(c, questoes(c, a, 2).corpo["registroId"].asString())
        assertEquals(0, g1["gravadas"].asInt()); assertEquals(6, g1["rejeitadas"].asInt(), "2 por rodada, 3 rodadas"); assertEquals("concluida", g1["status"].asString())
        python.semApoio = false; python.naoSustentada = true
        assertEquals(0, geracao(c, questoes(c, a, 1).corpo["registroId"].asString())["gravadas"].asInt())
        assertEquals(0, nQuestoes(a))
        python.naoSustentada = false; python.semApoio = true
        val t = livre(c)
        assertEquals(2, geracao(c, questoes(c, t, 2).corpo["registroId"].asString())["gravadas"].asInt(), "tópico livre: consistência interna, sem trechos")
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM questao_trecho WHERE usuario_id = ?", Int::class.java, c.id))
    }

    @Test
    fun `enunciado repetido nao e gravado, conta como repetida e a rodada seguinte gera outro`() {
        val c = conta(); val (_, a, _) = material(c)
        python.roteiroQuestoes += roteiro("Mesmo enunciado", "Mesmo enunciado!", "Outro")
        val g = geracao(c, questoes(c, a, 3).corpo["registroId"].asString())
        assertEquals(3, g["gravadas"].asInt()); assertEquals(1, g["repetidas"].asInt(), "mesmo hash depois de normalizar: 'Mesmo enunciado!' = 'Mesmo enunciado'"); assertEquals(4, g["aprovadas"].asInt())
        assertEquals(3, nQuestoes(a))
        python.roteiroQuestoes += roteiro("mesmo ENUNCIADO")
        val g2 = geracao(c, questoes(c, a, 1).corpo["registroId"].asString())
        assertEquals(1, g2["repetidas"].asInt(), "repetida de uma geração anterior do mesmo tópico"); assertEquals(1, g2["gravadas"].asInt())
        assertEquals(1, dono.queryForObject("SELECT sum(questoes_repetidas) FROM registro_geracao WHERE operacao = 'questoes' AND topico_id = ?::uuid AND criado_em = (SELECT max(criado_em) FROM registro_geracao WHERE operacao = 'questoes' AND topico_id = ?::uuid)", Int::class.java, a, a))
    }

    @Test
    fun `o hash normaliza maiusculas, acentos, pontuacao e espacos`() {
        assertEquals(hashDoEnunciado("Qual é a  Capital do Brasil?"), hashDoEnunciado("qual e a capital do brasil"))
        assertNotEquals(hashDoEnunciado("Qual a capital?"), hashDoEnunciado("Qual a capital da França?"))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(hashDoEnunciado("x")))
    }

    @Test
    fun `pedidos sucessivos acumulam ate o teto do topico e levam as perguntas existentes a evitar`() {
        val c = conta(); val t = livre(c)
        questoes(c, t, 5); questoes(c, t, 5)
        assertEquals(10, nQuestoes(t))
        assertTrue(python.corpos.last { it.startsWith("questoes:") }.contains("Pergunta única"), "as perguntas já gravadas vão no pedido, para não repetir")
        repeat(4) { assertEquals(202, questoes(c, t, 10).status) }
        assertEquals(50, nQuestoes(t))
        val estouro = questoes(c, t, 1)
        assertEquals(409, estouro.status); assertEquals("limite_questoes_topico", estouro.corpo["codigo"].asString())
        assertEquals(400, questoes(c, t, 11).status); assertEquals(400, questoes(c, t, 0).status)
    }

    // ---------------------------------------------------------------- falha do modelo, concorrência, recuperação
    @Test
    fun `falha do modelo vira falhou com mensagem clara, nada e gravado e uma nova tentativa funciona`() {
        val c = conta(); val (_, a, _) = material(c)
        python.statusGeracao = 500
        val g = geracao(c, resumo(c, a).corpo["registroId"].asString())
        assertEquals("falhou", g["status"].asString()); assertTrue(g["mensagem"].asString().contains("Tente novamente"))
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM resumo WHERE topico_id = ?::uuid", Int::class.java, a))
        val q = geracao(c, questoes(c, a, 2).corpo["registroId"].asString())
        assertEquals("falhou", q["status"].asString()); assertEquals(0, nQuestoes(a))
        assertEquals(listOf("falhou", "falhou"), dono.queryForList("SELECT status FROM registro_geracao WHERE topico_id = ?::uuid AND operacao IN ('questoes','verificacao_questao')", String::class.java, a))
        python.statusGeracao = null
        assertEquals("concluida", geracao(c, resumo(c, a).corpo["registroId"].asString())["status"].asString())
        assertEquals("concluida", geracao(c, questoes(c, a, 2).corpo["registroId"].asString())["status"].asString())
    }

    @Test
    fun `modelo que nao devolve nenhuma questao valida e falha, nao conclusao silenciosa`() {
        val c = conta(); val (_, a, _) = material(c)
        python.roteiroQuestoes += "[]"; python.roteiroQuestoes += "[]"; python.roteiroQuestoes += "[]"
        assertEquals("falhou", geracao(c, questoes(c, a, 2).corpo["registroId"].asString())["status"].asString())
    }

    private fun registroAberto(c: Conta, topico: String, minutosAtras: Int): UUID = UUID.randomUUID().also {
        dono.update(
            """INSERT INTO registro_geracao(id,usuario_id,topico_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual,atualizado_em)
               VALUES (?,?,?::uuid,'resumo','material','m','em_andamento',0,0,0,0,0,0,10,now() - make_interval(mins => ?))""", it, c.id, topico, minutosAtras,
        )
    }

    @Test
    fun `um pedido por topico por vez, e operacao abandonada vira falhou na consulta`() {
        val c = conta(); val (_, a, _) = material(c)
        val aberto = registroAberto(c, a, 1)
        val r = resumo(c, a)
        assertEquals(409, r.status); assertEquals("geracao_em_andamento", r.corpo["codigo"].asString())
        assertEquals(aberto.toString(), chamar("GET", "/topicos/$a", token = c.token).corpo["geracaoAberta"].asString())
        dono.update("UPDATE registro_geracao SET status='falhou', finalizada_em=now() WHERE id = ?", aberto)
        val velho = registroAberto(c, a, 10)  // sem tarefa viva e sem pulso há 10 min (> 5)
        assertEquals("falhou", geracao(c, velho.toString())["status"].asString())
        assertEquals(202, resumo(c, a).status)
    }

    // ---------------------------------------------------------------- cancelamento
    private fun abertoDe(c: Conta, op: String) = dono.queryForObject("SELECT id FROM registro_geracao WHERE usuario_id = ? AND operacao = ? AND status IN ('pendente','em_andamento')", String::class.java, c.id, op)!!
    private fun cancelar(c: Conta, id: String) = chamar("POST", "/geracoes/$id/cancelar", token = c.token)
    private fun motivos(t: String) = dono.queryForList("SELECT operacao||':'||status||':'||coalesce(motivo_falha,'-') FROM registro_geracao WHERE topico_id = ?::uuid ORDER BY operacao", String::class.java, t).filterNotNull()

    /** Executa [acao] uma única vez, quando a rota [rota] do modelo for chamada pela [n]-ésima vez (o usuário age no meio da geração). */
    private fun noMeioDa(rota: String, n: Int = 1, acao: () -> Unit) {
        var vez = 0
        python.aoReceber = { r -> if (r == rota && ++vez == n) { python.aoReceber = null; acao() } }
    }

    @Test
    fun `cancelar no meio da geracao de questoes para sem gravar, guarda o consumo e o motivo`() {
        val c = conta(); val (_, a, _) = material(c)
        python.chamadas.clear()
        var resposta: Resposta? = null
        noMeioDa("questoes") { resposta = cancelar(c, abertoDe(c, "questoes")) }
        val r = questoes(c, a, 3)
        assertEquals(202, r.status)
        assertEquals("falhou", resposta!!.corpo["status"].asString()); assertEquals("cancelada", resposta.corpo["motivo"].asString()); assertEquals("Geração cancelada.", resposta.corpo["mensagem"].asString())
        assertEquals(0, nQuestoes(a)); assertTrue("verificar" !in python.chamadas, "depois do cancelamento nada mais é chamado")
        assertEquals(listOf("questoes:falhou:cancelada", "verificacao_questao:falhou:cancelada"), motivos(a))
        assertEquals("50:40", dono.queryForObject("SELECT tokens_entrada||':'||tokens_saida FROM registro_geracao WHERE topico_id = ?::uuid AND operacao = 'questoes'", String::class.java, a), "a chamada em curso não se interrompe: os tokens dela entram no registro")
        val g = geracao(c, r.corpo["registroId"].asString())
        assertEquals("falhou", g["status"].asString()); assertEquals("cancelada", g["motivo"].asString())
        assertEquals(202, questoes(c, a, 1).status, "depois de cancelar o tópico fica livre para um novo pedido")
    }

    @Test
    fun `cancelar no meio da verificacao mantem as questoes ja gravadas`() {
        val c = conta(); val (_, a, _) = material(c)
        noMeioDa("verificar", 2) { cancelar(c, abertoDe(c, "questoes")) }
        val r = questoes(c, a, 3)
        val g = geracao(c, r.corpo["registroId"].asString())
        assertEquals(1, nQuestoes(a)); assertEquals("cancelada", g["motivo"].asString())
        assertEquals("Geração cancelada. As questões já gravadas foram mantidas.", g["mensagem"].asString())
        assertEquals(1, g["gravadas"].asInt())
    }

    @Test
    fun `cancelar o resumo durante a chamada ao modelo nao grava resumo`() {
        val c = conta(); val (_, a, _) = material(c)
        noMeioDa("resumo") { cancelar(c, abertoDe(c, "resumo")) }
        assertEquals(202, resumo(c, a).status)
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM resumo WHERE topico_id = ?::uuid", Int::class.java, a))
        assertEquals(listOf("resumo:falhou:cancelada"), motivos(a))
    }

    @Test
    fun `cancelar operacao ja terminada nao muda nada e id alheio ou de verificacao e 404`() {
        val c = conta(); val outra = conta(); val t = livre(c)
        val q = questoes(c, t, 1).corpo
        val antes = geracao(c, q["registroId"].asString())["status"].asString()
        val r = cancelar(c, q["registroId"].asString())
        assertEquals(200, r.status); assertEquals(antes, r.corpo["status"].asString()); assertEquals("concluida", r.corpo["status"].asString())
        assertEquals(404, cancelar(outra, q["registroId"].asString()).status, "de outra conta")
        assertEquals(404, cancelar(c, q["verificacaoId"].asString()).status, "o registro de verificação não se cancela sozinho")
        assertEquals(404, cancelar(c, UUID.randomUUID().toString()).status)
        assertEquals(401, chamar("POST", "/geracoes/${q["registroId"].asString()}/cancelar").status)
        assertEquals(listOf("questoes:concluida:-", "verificacao_questao:concluida:-"), motivos(t))
    }

    @Test
    fun `motivos de falha ficam registrados`() {
        val c = conta(); val t = livre(c)
        python.statusGeracao = 500
        assertEquals("falha_ia", geracao(c, resumo(c, t).corpo["registroId"].asString())["motivo"].asString())
        python.statusGeracao = null
        val velho = registroAberto(c, t, 10)
        assertEquals("interrompido", geracao(c, velho.toString())["motivo"].asString())
    }

    @Test
    fun `cancelar deixa rastro no log de auditoria sem dado sensivel`() {
        val logger = LoggerFactory.getLogger("auditoria") as Logger
        val anexo = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        try {
            val c = conta(); val (_, a, _) = material(c)
            noMeioDa("resumo") { cancelar(c, abertoDe(c, "resumo")) }
            resumo(c, a)
            val linhas = anexo.list.map { it.formattedMessage }
            assertTrue(linhas.any { "evento=geracao_cancelada " in it && "operacao=resumo" in it }, "faltou o evento: $linhas")
            assertTrue(linhas.none { c.token in it })
            val antes = linhas.size
            val terminada = questoes(c, a, 1).corpo["registroId"].asString()
            cancelar(c, terminada)  // já terminada: nada foi cancelado, nada a registrar
            assertEquals(0, anexo.list.map { it.formattedMessage }.drop(antes).count { "geracao_cancelada" in it })
        } finally {
            logger.detachAppender(anexo)
        }
    }

    // ---------------------------------------------------------------- listas e teto de resumos
    @Test
    fun `so os resumos mais recentes ficam, ate o teto do topico`() {
        val c = conta(); val t = livre(c)
        resumo(c, t)
        val primeiro = dono.queryForObject("SELECT id::text FROM resumo WHERE topico_id = ?::uuid", String::class.java, t)!!
        repeat(11) { resumo(c, t) }   // 12 no total, teto 10
        assertEquals(10, dono.queryForObject("SELECT count(*) FROM resumo WHERE topico_id = ?::uuid", Int::class.java, t))
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM resumo WHERE id = ?::uuid", Int::class.java, primeiro), "o mais antigo saiu")
        assertEquals(10, chamar("GET", "/topicos/$t/resumos", token = c.token).corpo.size())
        assertEquals(10, chamar("GET", "/topicos/$t", token = c.token).corpo["maximoResumos"].asInt())
    }

    @Test
    fun `listas aceitam limite, devolvem os mais novos primeiro e o limite e contido`() {
        val c = conta()
        val a = livre(c); val b = livre(c); val d = livre(c)
        val tres = chamar("GET", "/topicos/livres", token = c.token).corpo.toList().map { it["id"].asString() }
        assertEquals(listOf(d, b, a), tres)
        assertEquals(listOf(d), chamar("GET", "/topicos/livres?limite=1", token = c.token).corpo.toList().map { it["id"].asString() })
        assertEquals(1, chamar("GET", "/topicos/livres?limite=0", token = c.token).corpo.size(), "limite mínimo 1")
        assertEquals(3, chamar("GET", "/topicos/livres?limite=99999", token = c.token).corpo.size())
        assertEquals(400, chamar("GET", "/topicos/livres?limite=abc", token = c.token).status)
        material(c); material(c)
        assertEquals(1, chamar("GET", "/materiais?limite=1", token = c.token).corpo.size())
        assertEquals(2, chamar("GET", "/materiais", token = c.token).corpo.size())
    }

    @Test
    fun `as listas dizem quando ha geracao em andamento, ignorando a abandonada`() {
        val c = conta(); val (m, a, _) = material(c); val t = livre(c)
        fun gerandoLivre() = chamar("GET", "/topicos/livres", token = c.token).corpo.toList().single { it["id"].asString() == t }["gerando"].asBoolean()
        fun gerandoMaterial() = chamar("GET", "/materiais", token = c.token).corpo.toList().single { it["id"].asString() == m }["gerando"].asBoolean()
        assertEquals(false, gerandoLivre()); assertEquals(false, gerandoMaterial())
        val rl = registroAberto(c, t, 1); val rm = registroAberto(c, a, 1)
        assertEquals(true, gerandoLivre(), "operação aberta e recente"); assertEquals(true, gerandoMaterial(), "o material vê as operações de qualquer tópico dele")
        dono.update("UPDATE registro_geracao SET status='falhou', finalizada_em=now() WHERE id IN (?,?)", rl, rm)
        assertEquals(false, gerandoLivre()); assertEquals(false, gerandoMaterial())
        registroAberto(c, t, 30); registroAberto(c, a, 30)   // abertas, mas sem pulso há 30 min (reinício): não contam
        assertEquals(false, gerandoLivre()); assertEquals(false, gerandoMaterial())
    }

    // ---------------------------------------------------------------- limites
    private fun encher(c: Conta, n: Int, custo: String = "0") = repeat(n) {
        dono.update(
            """INSERT INTO registro_geracao(id,usuario_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual,finalizada_em)
               VALUES (?,?,'resumo','topico_livre','m','concluida',0,0,?::numeric,0,0,0,100,now())""", UUID.randomUUID(), c.id, custo,
        )
    }

    @Test
    fun `limite de operacoes por hora recusa resumo e questoes sem criar registro nem chamar o modelo`() {
        val c = conta(); val t = livre(c)
        encher(c, 30)
        python.chamadas.clear()
        for (r in listOf(resumo(c, t), questoes(c, t))) { assertEquals(429, r.status); assertEquals("limite_operacoes", r.corpo["codigo"].asString()) }
        assertEquals(30, dono.queryForObject("SELECT count(*) FROM registro_geracao WHERE usuario_id = ?", Int::class.java, c.id))
        assertTrue(python.chamadas.none { it in setOf("resumo", "questoes", "verificar") })
    }

    @Test
    fun `cota semanal esgotada recusa com mensagem clara`() {
        val c = conta(); val t = livre(c)
        encher(c, 1, "10.000000")   // US$ 10 = 100 mil créditos: muito além da cota de 300
        val r = questoes(c, t)
        assertEquals(429, r.status); assertEquals("limite_creditos", r.corpo["codigo"].asString()); assertTrue(r.corpo["mensagem"].asString().isNotBlank())
        assertEquals(0, nQuestoes(t))
    }

    @Test
    fun `um pedido de questoes conta duas operacoes`() {
        val c = conta(); val t = livre(c)
        questoes(c, t, 1)
        assertEquals(2, chamar("GET", "/uso", token = c.token).corpo["operacoes"].asInt())
    }

    // ---------------------------------------------------------------- isolamento
    @Test
    fun `outra conta nao ve nem gera conteudo nos topicos de A`() {
        val a = conta(); val b = conta()
        val (_, ta, _) = material(a)
        val r = questoes(a, ta, 2).corpo["registroId"].asString()
        resumo(a, ta)
        val antes = nQuestoes(ta)
        python.chamadas.clear()
        for (resp in listOf(
            chamar("GET", "/topicos/$ta", token = b.token), chamar("GET", "/topicos/$ta/questoes", token = b.token),
            resumo(b, ta), questoes(b, ta, 2), chamar("GET", "/geracoes/$r", token = b.token),
        )) { assertEquals(404, resp.status, resp.corpo.toString()) }
        assertEquals(antes, nQuestoes(ta)); assertTrue(python.chamadas.none { it in setOf("resumo", "questoes", "verificar", "saude") }, "nenhuma chamada ao serviço de IA, nem ao /saude, para o pedido de B")
        val tb = livre(b)
        assertEquals(0, chamar("GET", "/topicos/$tb/questoes", token = b.token).corpo.size())
        assertEquals(null, chamar("GET", "/topicos/$tb", token = b.token).corpo["resumo"].let { if (it.isNull) null else it })
    }

    @Test
    fun `a busca vetorial no contexto de B nao enxerga trechos de A e sem contexto nao enxerga nada`() {
        val a = conta(); val b = conta()
        val (_, _, tb) = material(a)
        val vetor = List(1536) { 0.01 }
        val doB = UsuarioAtual.como(b.id) { transacao.execute { gDados.trechosMaisSimilares(UUID.fromString(tb), vetor, 10) + gDados.trechosDasFaixas(UUID.fromString(tb)) } }
        assertEquals(0, doB!!.size)
        val doA = UsuarioAtual.como(a.id) { transacao.execute { gDados.trechosMaisSimilares(UUID.fromString(tb), vetor, 10) } }
        assertTrue(doA!!.isNotEmpty() && doA.size <= 10)
        assertEquals(0, transacao.execute { gDados.trechosMaisSimilares(UUID.fromString(tb), vetor, 10).size })
    }

    @Test
    fun `em topico grande os pedidos de questoes seguintes preferem trechos ainda nao citados`() {
        val c = conta(); val (_, _, b) = material(c)
        questoes(c, b, 1)
        val primeiro = numeros(trechosEnviados("questoes"))
        questoes(c, b, 1)
        val segundo = numeros(trechosEnviados("questoes"))
        assertEquals(6, primeiro.first(), "sem questões ainda, vale a ordem de similaridade (empate: ordem do material)")
        assertTrue(6 !in segundo && 15 in segundo, "o trecho 6 foi citado pela primeira questão e fica para o fim: $primeiro -> $segundo")
        python.chamadas.clear()
        resumo(c, b)
        assertEquals(primeiro, numeros(trechosEnviados("resumo")), "o resumo não varia: usa sempre os mais similares")
    }

    @Test
    fun `questoes trazem a fonte e o topico mostra o resultado da ultima geracao`() {
        val c = conta(); val (_, a, _) = material(c)
        assertEquals(true, chamar("GET", "/topicos/$a", token = c.token).corpo["ultimaGeracao"].isNull)
        questoes(c, a, 3)
        val q = chamar("GET", "/topicos/$a/questoes", token = c.token).corpo.toList()
        q.forEach { x -> assertTrue(x["apoio"].size() >= 1); x["apoio"].forEach { ap -> assertEquals("m.pdf", ap["nomeArquivo"].asString()); assertTrue(ap["ordem"].asInt() in 1..5 && ap["numeroPagina"].asInt() >= 1) } }
        val ultima = chamar("GET", "/topicos/$a", token = c.token).corpo["ultimaGeracao"]
        assertEquals("concluida", ultima["status"].asString()); assertEquals(3, ultima["gravadas"].asInt()); assertEquals("questoes", ultima["operacao"].asString())
        assertEquals(0, chamar("GET", "/topicos/${livre(c)}/questoes", token = c.token).corpo.size())
    }

    @Test
    fun `lista so os topicos livres da propria conta`() {
        val a = conta(); val b = conta()
        val (_, doMaterial, _) = material(a)
        val t = livre(a); livre(b)
        resumo(a, t)
        val lista = chamar("GET", "/topicos/livres", token = a.token).corpo.toList()
        assertEquals(listOf(t), lista.map { it["id"].asString() }, "tópico de material e tópico de B não entram")
        assertTrue(lista[0]["temResumo"].asBoolean()); assertEquals(0, lista[0]["totalQuestoes"].asInt())
        assertTrue(doMaterial.isNotEmpty() && chamar("GET", "/topicos/livres", token = b.token).corpo.size() == 1)
    }

    @Test
    fun `rotas exigem autenticacao`() {
        val id = UUID.randomUUID()
        assertEquals(401, chamar("GET", "/topicos/$id").status); assertEquals(401, chamar("POST", "/topicos/$id/resumo").status); assertEquals(401, chamar("GET", "/geracoes/$id").status)
    }

    @Test
    fun `topico com resumo ou questoes fica congelado`() {
        val c = conta(); val (m, a, _) = material(c)
        resumo(c, a)
        val corpo = """{"topicos":[{"id":"$a","nome":"A","inicio":1,"fim":4},{"nome":"B","inicio":5,"fim":30}]}"""
        assertEquals(409, chamar("PUT", "/materiais/$m/topicos", corpo, c.token).status)
    }
}
