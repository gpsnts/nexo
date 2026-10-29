package br.nexo

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** bloqueio de login, refresh token, recuperação de senha e perfil. */
class ContaTest : BaseIntegracao() {
    private val senha = "senha-forte-1"

    private fun conta(): Pair<String, Resposta> {
        val email = "${UUID.randomUUID()}@x.com"
        return email to cadastrar(email = email, senha = senha)
    }

    private fun token(r: Resposta) = r.corpo["token"].asString()
    private fun usuarioDe(email: String) = dono.queryForMap("SELECT * FROM usuario WHERE email = ?", email)

    // ---------- bloqueio de login ----------

    @Test
    fun `cinco falhas bloqueiam, mesmo com a senha certa, e o desbloqueio vem com o tempo`() {
        val (email) = conta()
        repeat(4) { assertEquals(401, login(email, "errada-$it-xx").status) }
        assertEquals(4, usuarioDe(email)["falhas_login"])
        assertNull(usuarioDe(email)["bloqueado_ate"])
        assertEquals(401, login(email, "errada-5-xx").status) // 5ª falha: bloqueia
        val bloqueio = (usuarioDe(email)["bloqueado_ate"] as java.sql.Timestamp).toInstant()
        assertTrue(bloqueio.isAfter(Instant.now().plusSeconds(14 * 60)))
        val certa = login(email)
        assertEquals(401, certa.status)
        assertTrue(certa.corpo["mensagem"].asString().contains("bloqueado"))
        dono.update("UPDATE usuario SET bloqueado_ate = now() - interval '1 minute' WHERE email = ?", email)
        assertEquals(200, login(email).status)
        assertEquals(0, usuarioDe(email)["falhas_login"])
        assertNull(usuarioDe(email)["bloqueado_ate"])
    }

    @Test
    fun `um acerto zera o contador de falhas`() {
        val (email) = conta()
        repeat(3) { login(email, "errada-$it-xx") }
        assertEquals(200, login(email).status)
        repeat(4) { login(email, "errada-$it-xx") }
        assertNull(usuarioDe(email)["bloqueado_ate"])
        assertEquals(4, usuarioDe(email)["falhas_login"])
    }

    // ---------- refresh ----------

    @Test
    fun `refresh vai em cookie HttpOnly e nao no corpo`() {
        val (_, r) = conta()
        val cookie = r.cabecalhos.allValues("Set-Cookie").first { it.startsWith("nexo_refresh=") }
        for (atributo in listOf("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth")) assertTrue(cookie.contains(atributo), atributo)
        assertNull(r.corpo.get("refresh"))
    }

    @Test
    fun `refresh rotaciona, reuso revoga a familia, logout e expiracao recusam`() {
        val (email, r) = conta()
        val c1 = refreshDe(r)
        val r2 = chamar("POST", "/auth/refresh", cookie = c1)
        assertEquals(200, r2.status)
        val c2 = refreshDe(r2)
        assertNotEquals(c1, c2)
        assertEquals(200, chamar("GET", "/assuntos", token = token(r2)).status)
        assertEquals(401, chamar("POST", "/auth/refresh", cookie = c1).status) // reuso do antigo
        assertEquals(401, chamar("POST", "/auth/refresh", cookie = c2).status) // a família toda caiu
        assertEquals(401, chamar("POST", "/auth/refresh").status)
        // logout
        val l = login(email)
        val c3 = refreshDe(l)
        val saiu = chamar("POST", "/auth/logout", cookie = c3)
        assertEquals(204, saiu.status)
        assertTrue(saiu.cabecalhos.allValues("Set-Cookie").first().contains("Max-Age=0"))
        assertEquals(401, chamar("POST", "/auth/refresh", cookie = c3).status)
        // expirado
        val c4 = refreshDe(login(email))
        dono.update("UPDATE sessao SET expira_em = now() - interval '1 second' WHERE usuario_id = (SELECT id FROM usuario WHERE email = ?)", email)
        assertEquals(401, chamar("POST", "/auth/refresh", cookie = c4).status)
    }

    @Test
    fun `o banco guarda so o hash do refresh`() {
        val (_, r) = conta()
        val bruto = refreshDe(r).substringAfter("=")
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM sessao WHERE token_hash = ?", Int::class.java, bruto))
    }

    // ---------- recuperação de senha ----------

    @Test
    fun `esqueci a senha responde igual para e-mail desconhecido e so envia para quem existe`() {
        val (email) = conta()
        val desconhecido = "${UUID.randomUUID()}@x.com"
        assertEquals(202, chamar("POST", "/auth/esqueci-senha", """{"email":"$desconhecido"}""").status)
        assertEquals(202, chamar("POST", "/auth/esqueci-senha", """{"email":"${email.uppercase()}"}""").status)
        assertTrue(emails.para(desconhecido).isEmpty())
        assertEquals(1, emails.com(email, "Redefinir").size)
    }

    @Test
    fun `redefinicao troca a senha, derruba sessoes, desbloqueia e o token vale uma vez`() {
        val (email, r) = conta()
        val cookieAntigo = refreshDe(r)
        repeat(5) { login(email, "errada-$it-xx") } // bloqueia
        chamar("POST", "/auth/esqueci-senha", """{"email":"$email"}""")
        val token = emails.para(email).last().token
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM token_conta WHERE token_hash = ?", Int::class.java, token))
        assertEquals(204, chamar("POST", "/auth/redefinir-senha", """{"token":"$token","novaSenha":"nova-senha-2"}""").status)
        assertEquals(200, login(email, "nova-senha-2").status) // já desbloqueado
        assertEquals(401, login(email, senha).status)
        assertEquals(401, chamar("POST", "/auth/refresh", cookie = cookieAntigo).status)
        val reuso = chamar("POST", "/auth/redefinir-senha", """{"token":"$token","novaSenha":"outra-senha-3"}""")
        assertEquals(400, reuso.status)
        assertEquals("link_invalido", reuso.corpo["codigo"].asString())
        assertTrue(emails.para(email).last().assunto.contains("senha do Nexo foi alterada")) // aviso de segurança
    }

    @Test
    fun `token de redefinicao expirado ou inventado e recusado e a senha nova e validada`() {
        val (email) = conta()
        chamar("POST", "/auth/esqueci-senha", """{"email":"$email"}""")
        val token = emails.para(email).last().token
        assertEquals(400, chamar("POST", "/auth/redefinir-senha", """{"token":"$token","novaSenha":"curta"}""").status)
        dono.update("UPDATE token_conta SET expira_em = now() - interval '1 second' WHERE usuario_id = ?", usuarioDe(email)["id"])
        assertEquals(400, chamar("POST", "/auth/redefinir-senha", """{"token":"$token","novaSenha":"nova-senha-2"}""").status)
        assertEquals(400, chamar("POST", "/auth/redefinir-senha", """{"token":"inventado","novaSenha":"nova-senha-2"}""").status)
        assertEquals(200, login(email).status)
    }

    @Test
    fun `no maximo tres pedidos de redefinicao por hora`() {
        val (email) = conta()
        repeat(5) { chamar("POST", "/auth/esqueci-senha", """{"email":"$email"}""") }
        assertEquals(3, emails.com(email, "Redefinir").size)
    }

    // ---------- perfil ----------

    @Test
    fun `ver e editar o nome`() {
        val (email, r) = conta()
        assertEquals(email, chamar("GET", "/perfil", token = token(r)).corpo["email"].asString())
        val novo = chamar("PATCH", "/perfil", """{"nome":"  Maria  "}""", token(r))
        assertEquals("Maria", novo.corpo["nome"].asString())
        assertEquals("Maria", usuarioDe(email)["nome"])
        assertEquals(400, chamar("PATCH", "/perfil", """{"nome":" "}""", token(r)).status)
    }

    @Test
    fun `trocar e-mail exige a senha e so vale depois de confirmar o link enviado ao novo endereco`() {
        val (email, r) = conta()
        val novo = "novo-${UUID.randomUUID()}@x.com"
        assertEquals(403, chamar("POST", "/perfil/email", """{"senhaAtual":"errada-xx","novoEmail":"$novo"}""", token(r)).status)
        assertEquals(202, chamar("POST", "/perfil/email", """{"senhaAtual":"$senha","novoEmail":"${novo.uppercase()}"}""", token(r)).status)
        assertEquals(email, usuarioDe(email)["email"]) // ainda não mudou
        assertEquals(0, emails.com(email, "Confirme seu novo").size)
        val link = emails.para(novo).single().token
        assertEquals(204, chamar("POST", "/auth/confirmar-email", """{"token":"$link"}""").status)
        val aviso = emails.com(email, "alterado").single() // o endereço ANTIGO é avisado, sem o e-mail novo por inteiro
        assertTrue(aviso.assunto.contains("alterado") && !aviso.texto.contains(novo))
        assertEquals(200, login(novo).status)
        assertEquals(401, login(email).status)
        assertEquals(400, chamar("POST", "/auth/confirmar-email", """{"token":"$link"}""").status) // uso único
    }

    @Test
    fun `e-mail de outra conta da 409, no pedido e na confirmacao`() {
        val (_, r) = conta()
        val (outro) = conta()
        assertEquals(409, chamar("POST", "/perfil/email", """{"senhaAtual":"$senha","novoEmail":"$outro"}""", token(r)).status)
        // alguém ocupa o e-mail depois do pedido, antes da confirmação
        val livre = "livre-${UUID.randomUUID()}@x.com"
        chamar("POST", "/perfil/email", """{"senhaAtual":"$senha","novoEmail":"$livre"}""", token(r))
        val link = emails.para(livre).single().token
        cadastrar(email = livre)
        assertEquals(409, chamar("POST", "/auth/confirmar-email", """{"token":"$link"}""").status)
    }

    @Test
    fun `trocar a senha derruba as outras sessoes e mantem a de quem trocou`() {
        val (email, r) = conta()
        val outra = refreshDe(login(email))
        val errada = chamar("PUT", "/perfil/senha", """{"senhaAtual":"errada-xx","novaSenha":"nova-senha-2"}""", token(r))
        assertEquals(403, errada.status)
        assertEquals("senha_incorreta", errada.corpo["codigo"].asString())
        val trocou = chamar("PUT", "/perfil/senha", """{"senhaAtual":"$senha","novaSenha":"nova-senha-2"}""", token(r))
        assertEquals(200, trocou.status)
        assertEquals(401, chamar("POST", "/auth/refresh", cookie = outra).status)
        assertTrue(emails.para(email).last().assunto.contains("senha do Nexo foi alterada"))
        assertEquals(200, chamar("POST", "/auth/refresh", cookie = refreshDe(trocou)).status)
        assertEquals(200, login(email, "nova-senha-2").status)
    }

    @Test
    fun `reautenticacao tambem conta para o bloqueio`() {
        val (email, r) = conta()
        repeat(5) { chamar("PUT", "/perfil/senha", """{"senhaAtual":"errada-$it-xx","novaSenha":"nova-senha-2"}""", token(r)) }
        assertEquals(403, chamar("PUT", "/perfil/senha", """{"senhaAtual":"$senha","novaSenha":"nova-senha-2"}""", token(r)).status)
        assertEquals(401, login(email).status)
    }

    @Test
    fun `excluir conta exige senha e confirmacao e apaga tudo so de quem excluiu`() {
        val (emailA, a) = conta()
        val (emailB, b) = conta()
        val idA = usuarioDe(emailA)["id"]
        val corpo = { s: String, c: String -> """{"senha":"$s","confirmacao":"$c"}""" }
        assertEquals(400, chamar("DELETE", "/perfil", corpo(senha, "sim"), token(a)).status)
        assertEquals(403, chamar("DELETE", "/perfil", corpo("errada-xx", "EXCLUIR"), token(a)).status)
        val cookieA = refreshDe(a)
        val del = chamar("DELETE", "/perfil", corpo(senha, "EXCLUIR"), token(a))
        assertEquals(204, del.status)
        assertTrue(del.cabecalhos.allValues("Set-Cookie").first().contains("Max-Age=0"))
        for (tabela in listOf("usuario" to "id", "assunto" to "usuario_id", "sessao" to "usuario_id", "token_conta" to "usuario_id"))
            assertEquals(0, dono.queryForObject("SELECT count(*) FROM ${tabela.first} WHERE ${tabela.second} = ?", Int::class.java, idA), tabela.first)
        // o JWT de A ainda não expirou, mas não leva a nada
        assertEquals(401, chamar("GET", "/perfil", token = token(a)).status)
        assertEquals(0, chamar("GET", "/assuntos", token = token(a)).corpo.size())
        assertEquals(401, chamar("POST", "/auth/refresh", cookie = cookieA).status)
        assertEquals(401, login(emailA).status)
        // B intacto
        assertEquals(1, chamar("GET", "/assuntos", token = token(b)).corpo.size())
        assertEquals(200, login(emailB).status)
    }

    @Test
    fun `A nao altera o perfil de B nem mandando o id de B no corpo`() {
        val (emailA, a) = conta()
        val (emailB) = conta()
        val idB = usuarioDe(emailB)["id"]
        chamar("PATCH", "/perfil", """{"nome":"Invasor","id":"$idB","email":"$emailB","usuarioId":"$idB"}""", token(a))
        assertEquals("Invasor", usuarioDe(emailA)["nome"])
        assertEquals("Ana", usuarioDe(emailB)["nome"])
    }
}
