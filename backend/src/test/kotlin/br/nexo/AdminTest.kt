package br.nexo

import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Painel de administração: suspensão, cota por conta, exclusão e o acesso restrito a administradores. A conta nova entra na hora, sem aprovação. */
@TestPropertySource(properties = ["nexo.admin.emails=admin-teste@x.com"])
class AdminTest : BaseIntegracao() {
    /** Pessoa comum de um teste: id, e-mail e token. */
    class Pessoa(val id: UUID, val email: String, val token: String)

    /** Token do administrador (a conta do e-mail configurado é criada na primeira chamada). */
    private fun admin(): String {
        val r = login("admin-teste@x.com")
        return (if (r.status == 200) r else cadastrar(email = "admin-teste@x.com")).corpo["token"].asString()
    }

    private fun idDe(email: String) = dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!

    /** Conta comum recém-criada: cadastro, confirmação do e-mail e login, sem nenhuma aprovação. */
    private fun pessoa(): Pessoa {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        return Pessoa(idDe(email), email, token)
    }

    private fun pdf() = "%PDF-1.4 PAGINAS=2 TRECHOS=3 nonce=${UUID.randomUUID()}".toByteArray()

    @Test
    fun `quem nao e administrador recebe 404 em todas as rotas do painel`() {
        val p = pessoa()
        val alvo = pessoa().id
        listOf("GET" to "/admin/usuarios", "POST" to "/admin/usuarios/$alvo/suspender", "POST" to "/admin/usuarios/$alvo/reativar",
            "PUT" to "/admin/usuarios/$alvo/cota", "DELETE" to "/admin/usuarios/$alvo").forEach { (m, c) ->
            assertEquals(404, chamar(m, c, json = if (m == "GET" || c.endsWith("ar") || c.endsWith("ender")) null else """{"creditos":1,"confirmacao":"EXCLUIR"}""", token = p.token).status, "$m $c")
        }
        assertEquals(401, chamar("GET", "/admin/usuarios").status)
        assertEquals("ativo", dono.queryForObject("SELECT status_conta FROM usuario WHERE id = ?", String::class.java, alvo))
        assertFalse(chamar("GET", "/perfil", token = p.token).corpo["admin"].asBoolean())
        assertTrue(chamar("GET", "/perfil", token = admin()).corpo["admin"].asBoolean())
    }

    @Test
    fun `suspender corta na hora o token em uso, o login e a renovacao, e reativar devolve o acesso`() {
        val p = pessoa()
        val login = login(p.email)
        val refresh = refreshDe(login)
        val token = login.corpo["token"].asString()
        assertEquals(200, chamar("GET", "/assuntos", token = token).status)
        assertEquals(204, chamar("POST", "/admin/usuarios/${p.id}/suspender", token = admin()).status)
        val barrado = chamar("GET", "/assuntos", token = token)
        assertEquals(403, barrado.status)
        assertEquals("conta_suspensa", barrado.corpo["codigo"].asString())
        assertEquals("conta_suspensa", login(p.email).corpo["codigo"].asString())
        val renovacao = chamar("POST", "/auth/refresh", cookie = refresh)
        assertEquals(403, renovacao.status)
        assertEquals("conta_suspensa", renovacao.corpo["codigo"].asString())
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM sessao WHERE usuario_id = ? AND revogado_em IS NULL", Int::class.java, p.id))
        assertEquals(204, chamar("POST", "/admin/usuarios/${p.id}/reativar", token = admin()).status)
        assertEquals(200, login(p.email).status)
    }

    @Test
    fun `cota por conta bloqueia a IA com 0, vale um limite menor e volta ao padrao com nulo`() {
        val p = pessoa()
        val padrao = chamar("GET", "/uso", token = p.token).corpo["creditosLimite"].asInt()
        assertEquals(204, chamar("PUT", "/admin/usuarios/${p.id}/cota", """{"creditos":0}""", token = admin()).status)
        val uso = chamar("GET", "/uso", token = p.token).corpo
        assertEquals(0, uso["creditosLimite"].asInt())
        assertEquals(100, uso["percentual"].asInt())
        val bloqueado = enviarArquivo(p.token, "a.pdf", pdf())
        assertEquals(429, bloqueado.status)
        assertEquals("limite_creditos", bloqueado.corpo["codigo"].asString())
        assertEquals(204, chamar("PUT", "/admin/usuarios/${p.id}/cota", """{"creditos":5}""", token = admin()).status)
        assertEquals(5, chamar("GET", "/uso", token = p.token).corpo["creditosLimite"].asInt())
        repeat(5) { // cada operação concluída custa no mínimo 1 crédito: 5 registros esgotam a cota de 5
            dono.update("""INSERT INTO registro_geracao(id,usuario_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual,finalizada_em)
                VALUES (?,?,'resumo','topico_livre','m','concluida',0,0,0,0,0,0,100,now())""", UUID.randomUUID(), p.id)
        }
        assertEquals(429, enviarArquivo(p.token, "b.pdf", pdf()).status)
        assertEquals(400, chamar("PUT", "/admin/usuarios/${p.id}/cota", """{"creditos":-1}""", token = admin()).status)
        assertEquals(204, chamar("PUT", "/admin/usuarios/${p.id}/cota", """{"creditos":null}""", token = admin()).status)
        assertEquals(padrao, chamar("GET", "/uso", token = p.token).corpo["creditosLimite"].asInt())
    }

    @Test
    fun `a lista mostra o consumo de cada conta so com os registros dela e nada do conteudo`() {
        val a = pessoa(); val b = pessoa()
        repeat(3) {
            dono.update("""INSERT INTO registro_geracao(id,usuario_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual,finalizada_em)
                VALUES (?,?,'resumo','topico_livre','m','concluida',0,0,0,0,0,0,100,now())""", UUID.randomUUID(), a.id)
        }
        val lista = chamar("GET", "/admin/usuarios", token = admin()).corpo
        val linhaA = lista.first { it["id"].asString() == a.id.toString() }
        val linhaB = lista.first { it["id"].asString() == b.id.toString() }
        assertTrue(linhaA["creditosUsados"].asInt() >= 3)
        assertEquals(0, linhaB["creditosUsados"].asInt())
        assertEquals("ativo", linhaA["status"].asString())
        assertNotNull(linhaA["ultimoAcessoEm"])
        assertEquals(setOf("id", "nome", "email", "status", "admin", "emailVerificado", "criadoEm", "ultimoAcessoEm", "cotaPersonalizada", "creditosLimite", "creditosUsados", "percentual"), linhaA.propertyNames().toSet())
        assertTrue(lista.first { it["email"].asString() == "admin-teste@x.com" }["admin"].asBoolean())
        val nova = pessoa()
        assertEquals(nova.id.toString(), chamar("GET", "/admin/usuarios", token = admin()).corpo[0]["id"].asString(), "a mais recente vem primeiro")
    }

    @Test
    fun `excluir remove a conta, os dados e os arquivos e nao toca nas outras contas`() {
        val a = pessoa(); val b = pessoa()
        assertEquals(202, enviarArquivo(a.token, "a.pdf", pdf()).status)
        assertEquals(202, enviarArquivo(b.token, "b.pdf", pdf()).status)
        val pastaA = BaseIntegracao.arquivos.resolve(a.id.toString())
        assertTrue(java.nio.file.Files.exists(pastaA))
        assertEquals(400, chamar("DELETE", "/admin/usuarios/${a.id}", """{"confirmacao":"sim"}""", token = admin()).status)
        assertEquals(204, chamar("DELETE", "/admin/usuarios/${a.id}", """{"confirmacao":"EXCLUIR"}""", token = admin()).status)
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM usuario WHERE id = ?", Int::class.java, a.id))
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM material WHERE usuario_id = ?", Int::class.java, a.id))
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM material WHERE usuario_id = ?", Int::class.java, b.id))
        repeat(40) { if (java.nio.file.Files.exists(pastaA)) Thread.sleep(50) }
        assertFalse(java.nio.file.Files.exists(pastaA))
        assertTrue(java.nio.file.Files.exists(BaseIntegracao.arquivos.resolve(b.id.toString())))
        assertEquals(404, chamar("DELETE", "/admin/usuarios/${a.id}", """{"confirmacao":"EXCLUIR"}""", token = admin()).status)
    }

    @Test
    fun `o painel nao altera contas de administrador e recusa acoes fora do estado certo`() {
        val adm = admin()
        val idAdmin = idDe("admin-teste@x.com")
        listOf("POST" to "suspender", "POST" to "reativar", "PUT" to "cota").forEach { (m, a) ->
            val r = chamar(m, "/admin/usuarios/$idAdmin/$a", if (m == "PUT") """{"creditos":1}""" else null, token = adm)
            assertEquals(409, r.status, a)
            assertEquals("alvo_admin", r.corpo["codigo"].asString())
        }
        assertEquals(409, chamar("DELETE", "/admin/usuarios/$idAdmin", """{"confirmacao":"EXCLUIR"}""", token = adm).status)
        val p = pessoa()
        assertEquals("estado_invalido", chamar("POST", "/admin/usuarios/${p.id}/reativar", token = adm).corpo["codigo"].asString()) // já está ativa
        assertEquals(204, chamar("POST", "/admin/usuarios/${p.id}/suspender", token = adm).status)
        assertEquals("estado_invalido", chamar("POST", "/admin/usuarios/${p.id}/suspender", token = adm).corpo["codigo"].asString()) // já está suspensa
        assertEquals(404, chamar("POST", "/admin/usuarios/${UUID.randomUUID()}/suspender", token = adm).status)
    }

}
