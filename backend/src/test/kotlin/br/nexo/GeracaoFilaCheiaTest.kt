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

/** Fila de geração cheia (1 thread, fila de 1): 503 claro e o pedido recusado não deixa registro nem gasta a cota de operações do usuário. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["nexo.conta.assincrono=false", "nexo.material.assincrono=false", "nexo.geracao.assincrono=true", "nexo.geracao.threads=1", "nexo.geracao.fila=1", "nexo.geracao.simultaneas-por-usuario=10", "nexo.limite-publico.requisicoes=100000"],
)
@org.springframework.context.annotation.Import(EmailTesteConfig::class)
class GeracaoFilaCheiaTest : BaseIntegracao() {
    private fun livre(token: String) = chamar("POST", "/topicos", """{"nome":"Tema ${UUID.randomUUID()}"}""", token).corpo["id"].asString()

    @AfterEach fun restaurar() { python.aoReceber = null }

    @Test
    fun `fila cheia recusa com 503, sem registro e sem gastar a cota, e depois funciona`() {
        val token = cadastrar().corpo["token"].asString()
        val t1 = livre(token); val t2 = livre(token); val t3 = livre(token)
        val segura = CountDownLatch(1); val entrou = CountDownLatch(1); val primeira = AtomicBoolean(true)
        python.aoReceber = { rota -> if (rota == "resumo" && primeira.compareAndSet(true, false)) { entrou.countDown(); segura.await(20, TimeUnit.SECONDS) } }

        assertEquals(202, chamar("POST", "/topicos/$t1/resumo", token = token).status); assertTrue(entrou.await(10, TimeUnit.SECONDS))
        assertEquals(202, chamar("POST", "/topicos/$t2/resumo", token = token).status, "ocupa a única vaga da fila")
        val usoAntes = chamar("GET", "/uso", token = token).corpo["operacoes"].asInt()

        for (tentativa in 1..2) {
            val r = chamar("POST", "/topicos/$t3/resumo", token = token)
            assertEquals(503, r.status, "tentativa $tentativa"); assertEquals("fila_cheia", r.corpo["codigo"].asString())
        }
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM registro_geracao WHERE topico_id = ?::uuid", Int::class.java, t3), "pedido que nunca começou não deixa registro")
        assertEquals(usoAntes, chamar("GET", "/uso", token = token).corpo["operacoes"].asInt(), "e não conta no limite de operações por hora")

        segura.countDown()
        val limite = System.currentTimeMillis() + 15_000
        while (dono.queryForList("SELECT status FROM registro_geracao WHERE topico_id IN (?::uuid, ?::uuid)", String::class.java, t1, t2).any { it != "concluida" }) {
            check(System.currentTimeMillis() < limite) { "as duas gerações não terminaram" }; Thread.sleep(20)
        }
        assertEquals(202, chamar("POST", "/topicos/$t3/resumo", token = token).status, "com a fila livre, o mesmo tópico é aceito")
    }
}
