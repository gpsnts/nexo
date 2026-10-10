package br.nexo

import br.nexo.conta.EnviadorEmailSmtp
import br.nexo.conta.ErroNegocio
import br.nexo.conta.PoliticaSenha
import br.nexo.conta.SegundoPlano
import br.nexo.seguranca.GuardaDeAmbiente
import br.nexo.seguranca.LimiteTaxaFiltro
import br.nexo.seguranca.PasswordEncoderLimitado
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0017: erros de formato, limites de tamanho, política de senha, concorrência e auditoria. */
class RobustezTest : BaseIntegracao() {
    @Autowired lateinit var props: NexoProperties

    private fun mensagem(r: Resposta) = r.corpo["mensagem"]?.asString().orEmpty()

    // ---------- erros de formato (antes viravam 401 mudo) ----------

    @Test
    fun `erros de formato respondem com o status certo e mensagem`() {
        assertEquals(400, chamar("POST", "/auth/login", "{oops").status)
        assertTrue(mensagem(chamar("POST", "/auth/login", "{oops")).contains("inválido"))
        assertEquals(415, chamar("POST", "/auth/login", "x", tipo = "text/plain").status)
        val metodo = chamar("GET", "/auth/login")
        assertEquals(405, metodo.status)
        assertTrue(mensagem(metodo).isNotBlank())
        val token = token(cadastrar())
        val naoExiste = chamar("GET", "/nao-existe", token = token)
        assertEquals(404, naoExiste.status)
        assertEquals("Não encontrado.", mensagem(naoExiste))
        assertEquals("nao_encontrado", naoExiste.corpo["codigo"].asString())
        assertEquals("corpo_invalido", chamar("POST", "/auth/login", "{oops").corpo["codigo"].asString())
        assertEquals("dados_invalidos", cadastrarSemVerificar(email = "a@b").corpo["codigo"].asString())
        assertEquals(401, chamar("GET", "/nao-existe").status) // sem token continua 401
    }

    private fun token(r: Resposta) = r.corpo["token"].asString()

    // ---------- limites de tamanho e e-mail ----------

    @Test
    fun `campos grandes demais e e-mail sem dominio sao recusados`() {
        assertEquals(400, cadastrarSemVerificar(nome = "n".repeat(121)).status)
        assertEquals(201, cadastrarSemVerificar(nome = "n".repeat(120)).status)
        assertEquals(400, cadastrarSemVerificar(email = "a@b").status)
        assertEquals(400, cadastrarSemVerificar(email = "a".repeat(250) + "@x.com").status)
        assertEquals(400, login("a@x.com", "s".repeat(129)).status)
        val token = token(cadastrar())
        assertEquals(400, chamar("PATCH", "/perfil", """{"nome":"${"n".repeat(121)}"}""", token).status)
        assertEquals(400, chamar("DELETE", "/perfil", """{"senha":"x","confirmacao":"${"E".repeat(21)}"}""", token).status)
    }

    // ---------- política de senha ----------

    @Test
    fun `politica de senha recusa comum, repetida e com o e-mail dentro`() {
        assertNotEquals(null, PoliticaSenha.problema("senha123", null))
        assertNotEquals(null, PoliticaSenha.problema("PASSWORD1", null)) // sem diferenciar caixa
        assertNotEquals(null, PoliticaSenha.problema("aaaaaaaaaa", null))
        assertNotEquals(null, PoliticaSenha.problema("minha-ana.silva-1", "ana.silva@x.com"))
        assertNull(PoliticaSenha.problema("senha-forte-1", "ana@x.com")) // local curto (<4) não conta
        assertNull(PoliticaSenha.problema("cavalo-bateria-grampo", "ana.silva@x.com"))
        assertEquals(400, cadastrarSemVerificar(senha = "senha123").status)
        assertTrue(mensagem(cadastrarSemVerificar(senha = "12345678")).contains("comum"))
        assertEquals("senha_fraca", cadastrarSemVerificar(senha = "12345678").corpo["codigo"].asString())
    }

    @Test
    fun `redefinir ou trocar para senha fraca e recusado e o link continua valendo`() {
        val email = "${UUID.randomUUID()}@x.com"
        val a = cadastrar(email = email)
        assertEquals(400, chamar("PUT", "/perfil/senha", """{"senhaAtual":"senha-forte-1","novaSenha":"password1"}""", token(a)).status)
        chamar("POST", "/auth/esqueci-senha", """{"email":"$email"}""")
        val link = emails.com(email, "Redefinir").single().token
        assertEquals(400, chamar("POST", "/auth/redefinir-senha", """{"token":"$link","novaSenha":"qwerty123"}""").status)
        assertEquals(204, chamar("POST", "/auth/redefinir-senha", """{"token":"$link","novaSenha":"cavalo-bateria-grampo"}""").status)
    }

    // ---------- limite de hashes simultâneos ----------

    @Test
    fun `no maximo N hashes ao mesmo tempo e o excedente recebe 503 depois da espera`() {
        val emAndamento = AtomicInteger(); val maximo = AtomicInteger()
        val lento = object : PasswordEncoder {
            override fun encode(rawPassword: CharSequence?) = "h"
            override fun matches(rawPassword: CharSequence?, encodedPassword: String?): Boolean {
                val n = emAndamento.incrementAndGet()
                maximo.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                Thread.sleep(60)
                emAndamento.decrementAndGet()
                return true
            }
        }
        val limitado = PasswordEncoderLimitado(lento, permissoes = 2, espera = Duration.ofSeconds(5))
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) { pool.submit { limitado.matches("x", "h") } }
        pool.shutdown(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(2, maximo.get())

        val travado = PasswordEncoderLimitado(object : PasswordEncoder {
            override fun encode(rawPassword: CharSequence?) = "h"
            override fun matches(rawPassword: CharSequence?, encodedPassword: String?): Boolean { Thread.sleep(400); return true }
        }, permissoes = 1, espera = Duration.ofMillis(50))
        val primeiro = Thread { travado.matches("x", "h") }.also { it.start() }
        Thread.sleep(100)
        val erro = assertFailsWith<ErroNegocio> { travado.matches("x", "h") }
        assertEquals(503, erro.status.value())
        primeiro.join()
    }

    // ---------- fila do segundo plano ----------

    @Test
    fun `fila cheia descarta o excesso sem lancar excecao`() {
        val sp = SegundoPlano(assincrono = true, capacidade = 1)
        val liberar = CountDownLatch(1); val executadas = AtomicInteger()
        val ocupadas = CountDownLatch(2)
        repeat(2) { sp.executar { ocupadas.countDown(); liberar.await(); executadas.incrementAndGet() } } // as 2 threads
        assertTrue(ocupadas.await(2, TimeUnit.SECONDS))
        sp.executar { executadas.incrementAndGet() } // ocupa a única vaga da fila
        repeat(5) { sp.executar { executadas.incrementAndGet() } } // sem vaga: descartadas, sem exceção
        liberar.countDown()
        Thread.sleep(300)
        assertEquals(3, executadas.get())
        sp.destroy()
    }

    // ---------- IPv6 ----------

    @Test
    fun `IPv6 conta por bloco 64 e IPv4 por endereco`() {
        assertEquals(LimiteTaxaFiltro.chaveIp("2001:db8:1:2:aaaa::1"), LimiteTaxaFiltro.chaveIp("2001:db8:1:2:bbbb:cccc:dddd:2"))
        assertNotEquals(LimiteTaxaFiltro.chaveIp("2001:db8:1:2::1"), LimiteTaxaFiltro.chaveIp("2001:db8:1:3::1"))
        assertEquals("203.0.113.7", LimiteTaxaFiltro.chaveIp("203.0.113.7"))
    }

    // ---------- guarda de produção ----------

    @Test
    fun `producao recusa configuracao so de desenvolvimento`() {
        val prod = props.copy(ambiente = "producao")
        val problemas = GuardaDeAmbiente(prod).problemas()
        assertTrue(problemas.any { "provedor=falso" in it } && problemas.any { "https" in it })
        assertFailsWith<IllegalStateException> { GuardaDeAmbiente(prod).validar() }
        val ok = prod.copy(email = prod.email.copy(provedor = "smtp"), conta = prod.conta.copy(urlFrontend = "https://nexo.exemplo.br", assincrono = true))
        assertTrue(GuardaDeAmbiente(ok).problemas().isEmpty())
        assertTrue(GuardaDeAmbiente(props.copy(ambiente = "desenvolvimento")).problemas().isEmpty())
    }

    // ---------- concorrência ----------

    @Test
    fun `duas renovacoes simultaneas com o mesmo cookie, uma vence e a familia cai`() {
        val a = cadastrar()
        val cookie = refreshDe(a)
        val pool = Executors.newFixedThreadPool(2)
        val largada = CountDownLatch(1)
        val resultados = (1..2).map { pool.submit<Resposta> { largada.await(); chamar("POST", "/auth/refresh", cookie = cookie) } }
        largada.countDown()
        val respostas = resultados.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(listOf(200, 401), respostas.map { it.status }.sorted())
        val vencedora = respostas.first { it.status == 200 }
        assertEquals(401, chamar("POST", "/auth/refresh", cookie = refreshDe(vencedora)).status) // reuso detectado: família revogada
    }

    @Test
    fun `dois cadastros simultaneos do mesmo e-mail, um 201, os outros 409, uma linha no banco`() {
        val email = "${UUID.randomUUID()}@x.com"
        val pool = Executors.newFixedThreadPool(6)
        val largada = CountDownLatch(1)
        val resultados = (1..6).map { pool.submit<Resposta> { largada.await(); cadastrarSemVerificar(email = email) } }
        largada.countDown()
        val status = resultados.map { it.get(20, TimeUnit.SECONDS).status }
        pool.shutdown()
        assertEquals(1, status.count { it == 201 }, "status=$status")
        assertTrue(status.all { it == 201 || it == 409 || it == 429 }, "status=$status")
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM usuario WHERE email = ?", Int::class.java, email))
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM assunto a JOIN usuario u ON u.id = a.usuario_id WHERE u.email = ?", Int::class.java, email))
    }

    // ---------- auditoria ----------

    @Test
    fun `eventos de seguranca sao registrados sem e-mail, senha nem token`() {
        val logger = LoggerFactory.getLogger("auditoria") as Logger
        val anexo = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        try {
            val email = "auditoria-${UUID.randomUUID()}@x.com"
            val a = cadastrar(email = email)
            repeat(5) { login(email, "errada-$it-xx") } // bloqueia
            dono.update("UPDATE usuario SET bloqueado_ate = NULL, falhas_login = 0 WHERE email = ?", email)
            val cookie = refreshDe(a)
            chamar("POST", "/auth/refresh", cookie = cookie)
            chamar("POST", "/auth/refresh", cookie = cookie) // reuso
            val b = login(email)
            chamar("PUT", "/perfil/senha", """{"senhaAtual":"senha-forte-1","novaSenha":"cavalo-bateria-grampo"}""", token(b))
            val linhas = anexo.list.map { it.formattedMessage }
            for (evento in listOf("cadastro", "email_verificado", "login_ok", "login_falha", "conta_bloqueada", "refresh_reuso", "senha_alterada"))
                assertTrue(linhas.any { "evento=$evento " in it }, "faltou $evento em $linhas")
            assertTrue(linhas.none { email in it || "senha-forte-1" in it || "cavalo-bateria" in it }, "dado sensível no log de auditoria")
            assertTrue(linhas.all { "ip=" in it })
        } finally {
            logger.detachAppender(anexo)
        }
    }

    // ---------- SMTP de verdade ----------

    @Test
    fun `o envio por SMTP entrega a mensagem a um servidor de verdade`() {
        val greenMail = GreenMail(ServerSetupTest.SMTP)
        greenMail.start()
        try {
            val sender = JavaMailSenderImpl().apply { host = "127.0.0.1"; port = ServerSetupTest.SMTP.port }
            EnviadorEmailSmtp(sender, props).enviar("dono@x.com", "Confirme seu e-mail no Nexo", "Olá!\nhttp://localhost:3000/verificar-email?token=abc")
            assertTrue(greenMail.waitForIncomingEmail(5000, 1))
            val msg = greenMail.receivedMessages.single()
            assertEquals("Confirme seu e-mail no Nexo", msg.subject)
            assertEquals("dono@x.com", msg.allRecipients.single().toString())
            assertEquals(props.email.remetente, msg.from.single().toString())
            assertTrue(msg.content.toString().contains("token=abc")) // content já vem decodificado (o corpo bruto usa quoted-printable: "=3D")
        } finally {
            greenMail.stop()
        }
    }
}
