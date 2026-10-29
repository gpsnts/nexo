package br.nexo

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Custo do modelo de linguagem REAL (preço por milhão de tokens configurado) somado ao registro e lido pela cota de créditos. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "nexo.conta.assincrono=false", "nexo.material.assincrono=false", "nexo.geracao.assincrono=false", "nexo.limite-publico.requisicoes=100000",
        "nexo.geracao.preco-linguagem.entrada-usd-por-milhao=2", "nexo.geracao.preco-linguagem.saida-usd-por-milhao=10", "nexo.cota.creditos-por-semana=8",
    ],
)
@org.springframework.context.annotation.Import(EmailTesteConfig::class)
class GeracaoCustoTest : BaseIntegracao() {
    private fun conta() = cadastrar().corpo["token"].asString()
    private fun livre(t: String) = chamar("POST", "/topicos", """{"nome":"Tema ${UUID.randomUUID()}"}""", t).corpo["id"].asString()
    private fun custos(topico: String) = dono.queryForList("SELECT operacao||':'||custo_estimado_usd FROM registro_geracao WHERE topico_id = ?::uuid ORDER BY operacao", String::class.java, topico).filterNotNull()

    @AfterEach fun restaurar() { python.modeloLinguagem = "linguagem-falso" }

    @Test
    fun `modelo real soma o custo por milhao de tokens e a cota de créditos passa a valer`() {
        python.modeloLinguagem = "modelo-real-x"
        val token = conta(); val t = livre(token)
        assertEquals(202, chamar("POST", "/topicos/$t/resumo", token = token).status)
        assertEquals(listOf("resumo:0.000180"), custos(t), "30 tokens de entrada x 2 + 12 de saída x 10 = 180 / 1 milhão")
        val q = chamar("POST", "/topicos/$t/questoes", """{"quantidade":2}""", token)
        assertEquals(202, q.status, q.corpo.toString())
        assertEquals(listOf("questoes:0.000500", "resumo:0.000180", "verificacao_questao:0.000180"), custos(t), "geração 50x2+40x10; 2 verificações de 20x2+5x10")
        val uso = chamar("GET", "/uso", token = token).corpo
        assertEquals(9, uso["creditosUsados"].asInt(), "créditos = custo ÷ 0,0001, para cima: resumo 2 + questões 5 + verificação 2: $uso")
        val recusada = chamar("POST", "/topicos/$t/resumo", token = token)
        assertEquals(429, recusada.status); assertEquals("limite_creditos", recusada.corpo["codigo"].asString())
        assertEquals(3, custos(t).size, "recusada: nenhum registro novo")
    }

    @Test
    fun `provedor falso continua custando zero mesmo com preco configurado`() {
        val token = conta(); val t = livre(token)
        chamar("POST", "/topicos/$t/resumo", token = token)
        assertEquals(listOf("resumo:0.000000"), custos(t))
    }
}
