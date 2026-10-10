package br.nexo

import br.nexo.conta.LimpezaConta
import br.nexo.conta.SegundoPlano
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Limite por IP nas rotas públicas (ADR 0012): contexto próprio, com limite de 3 por janela. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["nexo.conta.assincrono=false", "nexo.limite-publico.requisicoes=3"],
)
@org.springframework.context.annotation.Import(EmailTesteConfig::class)
class LimiteTaxaTest : BaseIntegracao() {
    @Test
    fun `a quarta chamada seguida a rota publica do mesmo IP devolve 429 com Retry-After`() {
        repeat(3) { assertEquals(401, chamar("POST", "/auth/login", """{"email":"a@x.com","senha":"qualquer-senha"}""").status) }
        val r = chamar("POST", "/auth/login", """{"email":"a@x.com","senha":"qualquer-senha"}""")
        assertEquals(429, r.status)
        assertTrue(r.corpo["mensagem"].asString().contains("Aguarde"))
        assertEquals("limite_taxa", r.corpo["codigo"].asString())
        assertEquals(r.cabecalhos.firstValue("Retry-After").get().toInt(), r.corpo["esperaSegundos"].asInt()) // o frontend usa para a contagem regressiva
        assertTrue(r.cabecalhos.firstValue("Retry-After").get().toInt() in 1..60)
        // o bucket é por IP, não por rota: outra rota pública também é barrada
        assertEquals(429, chamar("POST", "/auth/esqueci-senha", """{"email":"a@x.com"}""").status)
    }

    @Test
    fun `rotas autenticadas e o refresh nao entram no limite`() {
        repeat(5) { assertEquals(401, chamar("POST", "/auth/refresh").status) }
        repeat(5) { assertEquals(401, chamar("GET", "/assuntos").status) }
    }
}

class SegundoPlanoTest {
    @Test
    fun `roda fora da thread de quem chamou e uma falha nao escapa`() {
        val sp = SegundoPlano(assincrono = true)
        val chamador = Thread.currentThread()
        val executou = CountDownLatch(1)
        var thread: Thread? = null
        sp.executar { thread = Thread.currentThread(); executou.countDown() }
        assertTrue(executou.await(2, TimeUnit.SECONDS))
        assertNotEquals(chamador, thread)
        sp.executar { error("falha esperada") } // não pode propagar
        sp.destroy()
    }
}

class LimpezaTest : BaseIntegracao() {
    @Autowired lateinit var limpeza: LimpezaConta

    @Test
    fun `apaga sessoes e tokens vencidos e preserva os validos`() {
        val id = UUID.randomUUID()
        dono.update("INSERT INTO usuario(id,nome,email,senha_hash,email_verificado_em) VALUES (?,?,?,?, now())", id, "u", "$id@x.com", "h")
        fun sessao(hash: String, intervalo: String) =
            dono.update("INSERT INTO sessao(id,usuario_id,familia_id,token_hash,expira_em) VALUES (?,?,?,?, now() + interval '$intervalo')", UUID.randomUUID(), id, UUID.randomUUID(), hash)
        fun token(hash: String, intervalo: String) =
            dono.update("INSERT INTO token_conta(id,usuario_id,tipo,token_hash,expira_em) VALUES (?,?, 'redefinicao_senha', ?, now() + interval '$intervalo')", UUID.randomUUID(), id, hash)
        sessao("s-velha-$id", "-1 day"); sessao("s-viva-$id", "1 day")
        token("t-velho-$id", "-1 minute"); token("t-vivo-$id", "10 minutes")
        // contas: velha e nunca verificada (apaga), velha e verificada (fica), nova e nunca verificada (fica)
        fun conta(nome: String, criadoHa: String, verificada: Boolean) = UUID.randomUUID().also {
            dono.update("INSERT INTO usuario(id,nome,email,senha_hash,criado_em,email_verificado_em) VALUES (?,?,?,?, now() - interval '$criadoHa', ${if (verificada) "now()" else "NULL"})", it, nome, "$it@x.com", "h")
        }
        val velhaSemVerificar = conta("a", "40 days", false); val velhaVerificada = conta("b", "40 days", true); val novaSemVerificar = conta("c", "1 day", false)
        assertTrue(limpeza.executar() >= 3)
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM usuario WHERE id = ?", Int::class.java, velhaSemVerificar))
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM usuario WHERE id = ?", Int::class.java, velhaVerificada))
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM usuario WHERE id = ?", Int::class.java, novaSemVerificar))
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM sessao WHERE usuario_id = ?", Int::class.java, id))
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM token_conta WHERE usuario_id = ?", Int::class.java, id))
    }
}
