package br.nexo

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Orientação do estudante (foco, banca, estilo): gravada no tópico, sobrescrita por pedido, enviada ao serviço de IA e isolada entre contas. */
class OrientacaoTest : BaseIntegracao() {
    private fun conta() = cadastrar(email = "${UUID.randomUUID()}@x.com").corpo["token"].asString()
    private fun enviado(rota: String) = python.corpos.last { it.startsWith("$rota:") }.substringAfter(":").let { mapper.readTree(it) }
    private fun detalhe(token: String, t: String) = chamar("GET", "/topicos/$t", token = token).corpo

    @Test
    fun `orientacao do topico livre vai ao resumo, as questoes e o chat, e o pedido pode sobrescrever ou zerar`() {
        val token = conta()
        val t = chamar("POST", "/topicos", """{"nome":"Segunda Guerra Mundial","orientacao":"  Foque em Espanha e Portugal; estilo FCC  "}""", token).corpo["id"].asString()
        assertEquals("Foque em Espanha e Portugal; estilo FCC", detalhe(token, t)["orientacao"].asString(), "grava sem espaços nas pontas")

        assertEquals(202, chamar("POST", "/topicos/$t/resumo", token = token).status)
        assertEquals("Foque em Espanha e Portugal; estilo FCC", enviado("resumo")["orientacao"].asString(), "sem corpo usa a gravada")
        assertEquals(202, chamar("POST", "/topicos/$t/questoes", """{"quantidade":1}""", token).status)
        assertEquals("Foque em Espanha e Portugal; estilo FCC", enviado("questoes")["orientacao"].asString())
        assertEquals(200, chamar("POST", "/topicos/$t/chat", """{"mensagem":"E a Espanha?"}""", token).status)
        assertEquals("Foque em Espanha e Portugal; estilo FCC", enviado("chat")["orientacao"].asString())

        assertEquals(202, chamar("POST", "/topicos/$t/questoes", """{"quantidade":1,"orientacao":"vestibular UFRGS"}""", token).status)
        assertEquals("vestibular UFRGS", enviado("questoes")["orientacao"].asString(), "o pedido sobrescreve só esta geração")
        assertEquals("Foque em Espanha e Portugal; estilo FCC", detalhe(token, t)["orientacao"].asString(), "a gravada não muda")
        assertEquals(202, chamar("POST", "/topicos/$t/resumo", """{"orientacao":"   "}""", token).status)
        assertFalse(enviado("resumo").has("orientacao"), "em branco = gerar sem orientação desta vez")
    }

    @Test
    fun `editar e limpar a orientacao, limite de 500 caracteres e isolamento entre contas`() {
        val a = conta(); val b = conta()
        val t = chamar("POST", "/topicos", """{"nome":"Tema A"}""", a).corpo["id"].asString()
        assertTrue(detalhe(a, t)["orientacao"].isNull, "sem orientação por padrão")
        assertEquals("pontos sobre Portugal", chamar("PUT", "/topicos/$t/orientacao", """{"orientacao":"pontos sobre Portugal"}""", a).corpo["orientacao"].asString())
        assertEquals(400, chamar("PUT", "/topicos/$t/orientacao", """{"orientacao":"${"x".repeat(501)}"}""", a).status)
        assertEquals(400, chamar("POST", "/topicos", """{"nome":"Longo","orientacao":"${"x".repeat(501)}"}""", a).status)

        assertEquals(404, chamar("PUT", "/topicos/$t/orientacao", """{"orientacao":"invadido"}""", b).status, "tópico de outra conta não existe")
        assertEquals("pontos sobre Portugal", dono.queryForObject("SELECT orientacao FROM topico WHERE id = ?::uuid", String::class.java, t))

        assertTrue(chamar("PUT", "/topicos/$t/orientacao", """{"orientacao":""}""", a).corpo["orientacao"].isNull)
        assertEquals(null, dono.queryForObject("SELECT orientacao FROM topico WHERE id = ?::uuid", String::class.java, t))
    }
}
