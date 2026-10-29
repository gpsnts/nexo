package br.nexo

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Executor de geração de verdade (uma thread, fila, sem usuário no SecurityContext): a geração precisa gravar como o dono do tópico,
 * e uma tarefa cancelada ainda na fila não pode chegar a chamar o serviço de IA (nem o embedding do conteúdo-base).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["nexo.conta.assincrono=false", "nexo.material.assincrono=false", "nexo.geracao.assincrono=true", "nexo.geracao.threads=1", "nexo.limite-publico.requisicoes=100000"],
)
@org.springframework.context.annotation.Import(EmailTesteConfig::class)
class GeracaoAssincronaTest : BaseIntegracao() {
    class Conta(val token: String, val id: UUID)

    private fun conta(): Conta {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        return Conta(token, dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!)
    }

    private fun livre(c: Conta) = chamar("POST", "/topicos", """{"nome":"Tema ${UUID.randomUUID()}"}""", c.token).corpo["id"].asString()

    /** Material de 30 trechos com um tópico que NÃO cabe no contexto de 300 caracteres (6..30): a base precisa do embedding do nome. */
    private fun topicoGrande(c: Conta): String {
        python.propostaTopicos = """[{"nome":"A","inicio":1,"fim":5},{"nome":"B","inicio":6,"fim":30}]"""
        val id = enviarArquivo(c.token, "m.pdf", "%PDF-1.4 PAGINAS=3 TRECHOS=30 nonce=${UUID.randomUUID()}".toByteArray()).corpo["materialId"].asString()
        return chamar("GET", "/materiais/$id/topicos", token = c.token).corpo.toList()[1]["id"].asString()
    }

    private fun esperar(descricao: String, ate: Long = 15_000, condicao: () -> Boolean) {
        val limite = System.currentTimeMillis() + ate
        while (!condicao()) { check(System.currentTimeMillis() < limite) { "não aconteceu em ${ate / 1000} s: $descricao" }; Thread.sleep(20) }
    }

    private fun status(registro: String) = dono.queryForObject("SELECT status FROM registro_geracao WHERE id = ?::uuid", String::class.java, registro)!!

    @AfterEach fun restaurar() { python.aoReceber = null; python.propostaTopicos = "[]" }

    @Test
    fun `geracao em segundo plano grava com o usuario certo e a barra chega a 100`() {
        val c = conta(); val t = livre(c)
        val r = chamar("POST", "/topicos/$t/questoes", """{"quantidade":2}""", c.token)
        assertEquals(202, r.status)
        val id = r.corpo["registroId"].asString()
        val vistos = mutableListOf<Int>()
        esperar("terminar a geração") {
            val g = chamar("GET", "/geracoes/$id", token = c.token).corpo
            vistos += g["progresso"].asInt()
            check(g["status"].asString() != "falhou") { "falhou: ${g["mensagem"]}" }
            g["status"].asString() == "concluida"
        }
        assertEquals(100, vistos.last()); assertTrue(vistos.zipWithNext().all { (x, y) -> y >= x }, "o percentual nunca desce: $vistos")
        assertEquals(2, dono.queryForObject("SELECT count(*) FROM questao WHERE topico_id = ?::uuid AND usuario_id = ?", Int::class.java, t, c.id))
        assertEquals(404, chamar("GET", "/geracoes/$id", token = conta().token).status)
    }

    @Test
    fun `tarefa cancelada ainda na fila nao chama o servico de IA nem o embedding`() {
        val c = conta(); val primeiro = livre(c); val grande = topicoGrande(c); val ultimo = livre(c)
        val segura = CountDownLatch(1); val entrou = CountDownLatch(1); val primeira = AtomicBoolean(true)
        python.aoReceber = { rota -> if (rota == "resumo" && primeira.compareAndSet(true, false)) { entrou.countDown(); segura.await(20, TimeUnit.SECONDS) } }

        val r1 = chamar("POST", "/topicos/$primeiro/resumo", token = c.token)
        assertEquals(202, r1.status)
        assertTrue(entrou.await(10, TimeUnit.SECONDS), "a primeira geração ocupou a única thread")
        val embeddingsAntes = python.chamadas.count { it == "embeddings" }
        val resumosAntes = python.chamadas.count { it == "resumo" }

        val r2 = chamar("POST", "/topicos/$grande/resumo", token = c.token)   // fica na fila, atrás da primeira
        assertEquals(202, r2.status)
        val registro = r2.corpo["registroId"].asString()
        assertEquals("pendente", status(registro), "ainda não começou")
        val cancelada = chamar("POST", "/geracoes/$registro/cancelar", token = c.token)
        assertEquals(200, cancelada.status); assertEquals("cancelada", cancelada.corpo["motivo"].asString())

        val r3 = chamar("POST", "/topicos/$ultimo/resumo", token = c.token)   // marca o fim da fila: entra depois da cancelada
        segura.countDown()
        esperar("terminar a primeira e a última") { status(r1.corpo["registroId"].asString()) == "concluida" && status(r3.corpo["registroId"].asString()) == "concluida" }

        assertEquals(embeddingsAntes, python.chamadas.count { it == "embeddings" }, "a tarefa cancelada na fila não pagou o embedding do conteúdo-base")
        assertEquals(1, python.chamadas.count { it == "resumo" } - resumosAntes, "depois da primeira (já em curso) só a última chamou o modelo de resumo; a cancelada não")
        assertEquals("falhou", status(registro))
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM resumo WHERE topico_id = ?::uuid", Int::class.java, grande))
        assertEquals("0:0", dono.queryForObject("SELECT tokens_entrada||':'||tokens_saida FROM registro_geracao WHERE id = ?::uuid", String::class.java, registro))
    }

    @Test
    fun `um usuario tem no maximo duas geracoes abertas e isso nao afeta os outros usuarios`() {
        val a = conta(); val b = conta()
        val t1 = livre(a); val t2 = livre(a); val t3 = livre(a); val tb = livre(b)
        val segura = CountDownLatch(1); val entrou = CountDownLatch(1); val primeira = AtomicBoolean(true)
        python.aoReceber = { rota -> if (rota == "resumo" && primeira.compareAndSet(true, false)) { entrou.countDown(); segura.await(20, TimeUnit.SECONDS) } }

        val r1 = chamar("POST", "/topicos/$t1/resumo", token = a.token)
        assertEquals(202, r1.status); assertTrue(entrou.await(10, TimeUnit.SECONDS))
        assertEquals(202, chamar("POST", "/topicos/$t2/resumo", token = a.token).status, "a segunda fica na fila")
        val terceira = chamar("POST", "/topicos/$t3/resumo", token = a.token)
        assertEquals(409, terceira.status); assertEquals("geracoes_simultaneas", terceira.corpo["codigo"].asString())
        assertTrue(terceira.corpo["mensagem"].asString().contains("2 gerações em andamento"))
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM registro_geracao WHERE topico_id = ?::uuid", Int::class.java, t3), "recusado: nenhum registro")
        assertEquals(202, chamar("POST", "/topicos/$tb/resumo", token = b.token).status, "o limite é por usuário: B não é afetado")

        segura.countDown()
        esperar("A terminar as duas") { status(r1.corpo["registroId"].asString()) == "concluida" && dono.queryForList("SELECT status FROM registro_geracao WHERE topico_id = ?::uuid", String::class.java, t2).all { it == "concluida" } }
        assertEquals(202, chamar("POST", "/topicos/$t3/resumo", token = a.token).status, "terminadas, A pode pedir de novo")
    }
}

