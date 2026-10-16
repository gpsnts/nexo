package br.nexo

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Modelo de linguagem real SEM preço configurado: a operação é recusada antes de qualquer chamada (o limite de gasto não enxergaria o custo). */
class GeracaoSemPrecoTest : BaseIntegracao() {
    @AfterEach fun restaurar() { python.modeloLinguagem = "linguagem-falso" }

    @Test
    fun `modelo real sem preco configurado e recusado com mensagem clara, sem registro e sem chamar o modelo`() {
        python.modeloLinguagem = "modelo-real-x"
        val token = cadastrar().corpo["token"].asString()
        val t = chamar("POST", "/topicos", """{"nome":"Tema ${UUID.randomUUID()}"}""", token).corpo["id"].asString()
        python.chamadas.clear()
        for (r in listOf(chamar("POST", "/topicos/$t/resumo", token = token), chamar("POST", "/topicos/$t/questoes", """{"quantidade":1}""", token))) {
            assertEquals(503, r.status); assertEquals("ia_indisponivel", r.corpo["codigo"].asString())
        }
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM registro_geracao WHERE topico_id = ?::uuid", Int::class.java, t))
        assertTrue(python.chamadas.none { it in setOf("resumo", "questoes", "verificar") })
        python.modeloLinguagem = "linguagem-falso"
        assertEquals(202, chamar("POST", "/topicos/$t/resumo", token = token).status, "com o provedor falso volta a funcionar")
    }

    @Test
    fun `a recusa por preco ausente tem codigo proprio para o log`() {
        python.modeloLinguagem = "modelo-real-x"
        val http = br.nexo.material.ProcessamentoHttp(props.copy(processamento = props.processamento.copy(cacheModelos = java.time.Duration.ZERO)), mapper)
        val e = kotlin.test.assertFailsWith<br.nexo.material.FalhaProcessamento> { http.modelos() }
        assertEquals("preco_ausente", e.codigo); assertTrue("PRECO_LINGUAGEM_ENTRADA_USD_POR_MILHAO" in e.message!!)
    }

    @org.springframework.beans.factory.annotation.Autowired lateinit var props: br.nexo.NexoProperties
}
