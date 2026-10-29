package br.nexo

import br.nexo.conta.descreverDispositivo
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FIREFOX_LINUX = "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0"
private const val CHROME_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
private const val SAFARI_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

/** Verificação de e-mail no cadastro. */
class VerificacaoTest : BaseIntegracao() {
    private fun verificadoEm(email: String) = dono.queryForMap("SELECT email_verificado_em FROM usuario WHERE email = ?", email)["email_verificado_em"]

    @Test
    fun `cadastro nao inicia sessao, envia o link e so entra depois de confirmar`() {
        val email = "${UUID.randomUUID()}@x.com"
        val r = cadastrarSemVerificar(email = email)
        assertEquals(201, r.status)
        assertNull(r.corpo.get("token"))
        assertTrue(r.cabecalhos.allValues("Set-Cookie").isEmpty())
        assertNull(verificadoEm(email))
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM assunto a JOIN usuario u ON u.id = a.usuario_id WHERE u.email = ?", Int::class.java, email)) // "Geral" já existe
        val mail = emails.com(email, "Confirme seu e-mail").single()
        assertTrue(mail.texto.contains("/verificar-email?token="))
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM token_conta WHERE token_hash = ?", Int::class.java, mail.token)) // só o hash

        val bloqueado = login(email)
        assertEquals(403, bloqueado.status)
        assertEquals("email_nao_verificado", bloqueado.corpo["codigo"].asString())
        // senha errada não revela o estado da conta: é o 401 de sempre
        assertEquals(401, login(email, "errada-xxxx").status)

        assertEquals(204, chamar("POST", "/auth/verificar-email", """{"token":"${mail.token}"}""").status)
        assertNotNull(verificadoEm(email))
        assertEquals(200, login(email).status)
        assertEquals(400, chamar("POST", "/auth/verificar-email", """{"token":"${mail.token}"}""").status) // uso único
    }

    @Test
    fun `link de verificacao expirado ou inventado e recusado`() {
        val email = "${UUID.randomUUID()}@x.com"
        cadastrarSemVerificar(email = email)
        val token = emails.com(email, "Confirme seu e-mail").single().token
        dono.update("UPDATE token_conta SET expira_em = now() - interval '1 second' WHERE usuario_id = (SELECT id FROM usuario WHERE email = ?)", email)
        assertEquals(400, chamar("POST", "/auth/verificar-email", """{"token":"$token"}""").status)
        assertEquals(400, chamar("POST", "/auth/verificar-email", """{"token":"inventado"}""").status)
        assertEquals(403, login(email).status)
    }

    @Test
    fun `reenviar responde igual para qualquer e-mail, so envia para conta nao verificada e respeita o limite por hora`() {
        val email = "${UUID.randomUUID()}@x.com"
        cadastrarSemVerificar(email = email) // 1 e-mail
        val desconhecido = "${UUID.randomUUID()}@x.com"
        assertEquals(202, chamar("POST", "/auth/reenviar-verificacao", """{"email":"$desconhecido"}""").status)
        assertTrue(emails.para(desconhecido).isEmpty())
        repeat(5) { assertEquals(202, chamar("POST", "/auth/reenviar-verificacao", """{"email":"${email.uppercase()}"}""").status) }
        assertEquals(3, emails.com(email, "Confirme seu e-mail").size) // cadastro + 2 reenvios = limite de 3 por hora
        val verificada = "${UUID.randomUUID()}@x.com"
        cadastrar(email = verificada)
        chamar("POST", "/auth/reenviar-verificacao", """{"email":"$verificada"}""")
        assertEquals(1, emails.com(verificada, "Confirme seu e-mail").size) // já verificada: nada novo
    }

    @Test
    fun `redefinir a senha confirma o e-mail e libera o dono verdadeiro de uma conta registrada por outra pessoa`() {
        val email = "${UUID.randomUUID()}@x.com"
        cadastrarSemVerificar(email = email, senha = "senha-do-invasor")
        assertEquals(403, login(email, "senha-do-invasor").status)
        chamar("POST", "/auth/esqueci-senha", """{"email":"$email"}""")
        val token = emails.com(email, "Redefinir").single().token
        assertEquals(204, chamar("POST", "/auth/redefinir-senha", """{"token":"$token","novaSenha":"senha-do-dono-1"}""").status)
        assertNotNull(verificadoEm(email))
        assertEquals(200, login(email, "senha-do-dono-1").status)
        assertEquals(401, login(email, "senha-do-invasor").status)
    }
}

/** Lista de sessões ativas. */
class SessoesTest : BaseIntegracao() {
    private fun lista(token: String) = chamar("GET", "/perfil/sessoes", token = token).corpo.toList()
    private fun token(r: Resposta) = r.corpo["token"].asString()

    @Test
    fun `lista mostra o dispositivo, marca a sessao atual e continua com uma linha por login depois de renovar`() {
        val email = "${UUID.randomUUID()}@x.com"
        val a = cadastrar(email = email, agente = FIREFOX_LINUX)
        val b = login(email, agente = CHROME_WINDOWS)
        val itens = lista(token(a))
        assertEquals(2, itens.size)
        val atual = itens.single { it["atual"].asBoolean() }
        assertEquals("Firefox em Linux", atual["dispositivo"].asString())
        assertEquals("Chrome em Windows", itens.single { !it["atual"].asBoolean() }["dispositivo"].asString())
        assertTrue(atual["ip"].asString().isNotBlank())
        // renovar troca o refresh, mas é o mesmo login: segue 1 linha por dispositivo e o "atual" não muda
        val renovado = chamar("POST", "/auth/refresh", cookie = refreshDe(a), agente = FIREFOX_LINUX)
        val depois = lista(token(renovado))
        assertEquals(2, depois.size)
        assertEquals(atual["id"].asString(), depois.single { it["atual"].asBoolean() }["id"].asString())
        assertEquals(atual["inicio"].asString(), depois.single { it["atual"].asBoolean() }["inicio"].asString())
        assertNotNull(b)
    }

    @Test
    fun `encerrar uma sessao derruba so ela`() {
        val email = "${UUID.randomUUID()}@x.com"
        val a = cadastrar(email = email, agente = FIREFOX_LINUX)
        val b = login(email, agente = CHROME_WINDOWS)
        val idDeB = lista(token(a)).single { !it["atual"].asBoolean() }["id"].asString()
        assertEquals(204, chamar("DELETE", "/perfil/sessoes/$idDeB", token = token(a)).status)
        assertEquals(401, chamar("POST", "/auth/refresh", cookie = refreshDe(b)).status)
        assertEquals(1, lista(token(a)).size)
        assertEquals(200, chamar("POST", "/auth/refresh", cookie = refreshDe(a)).status)
        assertEquals(404, chamar("DELETE", "/perfil/sessoes/$idDeB", token = token(a)).status) // já encerrada
    }

    @Test
    fun `sair de todos os outros dispositivos preserva a sessao atual`() {
        val email = "${UUID.randomUUID()}@x.com"
        val a = cadastrar(email = email)
        val outras = listOf(login(email, agente = CHROME_WINDOWS), login(email, agente = SAFARI_IPHONE))
        assertEquals(3, lista(token(a)).size)
        assertEquals(204, chamar("DELETE", "/perfil/sessoes", token = token(a)).status)
        outras.forEach { assertEquals(401, chamar("POST", "/auth/refresh", cookie = refreshDe(it)).status) }
        assertEquals(200, chamar("POST", "/auth/refresh", cookie = refreshDe(a)).status)
        assertEquals(1, lista(token(a)).size)
    }

    @Test
    fun `A nao ve nem encerra sessoes de B`() {
        val (_, a) = "${UUID.randomUUID()}@x.com".let { it to cadastrar(email = it) }
        val emailB = "${UUID.randomUUID()}@x.com"
        val b = cadastrar(email = emailB)
        val idDeB = lista(token(b)).single()["id"].asString()
        assertTrue(lista(token(a)).none { it["id"].asString() == idDeB })
        assertEquals(404, chamar("DELETE", "/perfil/sessoes/$idDeB", token = token(a)).status)
        val bRenovado = chamar("POST", "/auth/refresh", cookie = refreshDe(b))
        assertEquals(200, bRenovado.status) // a sessão de B segue de pé
        // "sair dos outros" de A também não encosta em B
        assertEquals(204, chamar("DELETE", "/perfil/sessoes", token = token(a)).status)
        assertEquals(200, chamar("POST", "/auth/refresh", cookie = refreshDe(bRenovado)).status)
        assertEquals(1, lista(token(bRenovado)).size)
    }

    @Test
    fun `sessao expirada ou revogada nao aparece`() {
        val email = "${UUID.randomUUID()}@x.com"
        val a = cadastrar(email = email)
        login(email, agente = CHROME_WINDOWS)
        dono.update("UPDATE sessao SET expira_em = now() - interval '1 second' WHERE usuario_id = (SELECT id FROM usuario WHERE email = ?) AND dispositivo = 'Chrome em Windows'", email)
        assertEquals(1, lista(token(a)).size)
    }

    @Test
    fun `descreve o dispositivo pelo User-Agent`() {
        assertEquals("Firefox em Linux", descreverDispositivo(FIREFOX_LINUX))
        assertEquals("Chrome em Windows", descreverDispositivo(CHROME_WINDOWS))
        assertEquals("Safari em iOS", descreverDispositivo(SAFARI_IPHONE))
        assertEquals("Dispositivo desconhecido", descreverDispositivo(null))
        assertEquals("Dispositivo desconhecido", descreverDispositivo(" "))
    }
}
