package br.nexo

import br.nexo.material.Cobertura
import br.nexo.material.FaixaTopico
import br.nexo.material.TrechoExtraido
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tópicos pela estrutura do documento (sem IA). */
class EstruturaTest {
    private fun trechos(total: Int, capitulos: Map<Int, String>, nivel2: Set<Int> = emptySet()) = (1..total).map {
        TrechoExtraido(it, null, "texto $it", capitulos[it] ?: if (it in nivel2) "Seção $it" else null, if (it in capitulos) 1 else if (it in nivel2) 2 else null)
    }

    private fun cobre(t: List<br.nexo.material.TopicoProposto>, total: Int) =
        Cobertura.validar(t.map { FaixaTopico(null, it.nome, it.inicio, it.fim) }, total, 100) == null

    @Test
    fun `cada capitulo vira um topico e o que vem antes do primeiro titulo forma a introducao`() {
        val t = Cobertura.porEstrutura(trechos(30, mapOf(5 to "Cap A", 12 to "Cap B", 20 to "Cap C")), 2, 100)!!
        assertEquals(listOf("Introdução" to (1..4), "Cap A" to (5..11), "Cap B" to (12..19), "Cap C" to (20..30)), t.map { it.nome to (it.inicio..it.fim) })
        assertTrue(cobre(t, 30))
    }

    @Test
    fun `secao pequena se junta a anterior e a primeira pequena se junta a seguinte`() {
        val t = Cobertura.porEstrutura(trechos(12, mapOf(2 to "Curta", 3 to "B", 8 to "C")), 2, 100)!!
        // trecho 1 sozinho (intro) + "Curta" (2) = 1..2 ; "B" 3..7 ; "C" 8..12
        assertEquals(listOf(1..2, 3..7, 8..12), t.map { it.inicio..it.fim })
        assertEquals("Curta", t[0].nome, "o trecho 1 não tem título: o tópico leva o nome da seção que se juntou a ele")
        assertTrue(cobre(t, 12))
    }

    @Test
    fun `sem titulos ou com uma secao so nao ha estrutura aproveitavel`() {
        assertNull(Cobertura.porEstrutura(trechos(20, emptyMap()), 2, 100))
        assertNull(Cobertura.porEstrutura(emptyList(), 2, 100))
        assertNull(Cobertura.porEstrutura(trechos(20, mapOf(1 to "Só um")), 2, 100))
    }

    @Test
    fun `usa o nivel mais alto que gera ao menos duas secoes e cai para o seguinte quando falta`() {
        // só um capítulo (nível 1) e várias subseções (nível 2): o nível 1 não separa nada, então valem as subseções
        val t = Cobertura.porEstrutura(trechos(20, mapOf(1 to "Único capítulo"), nivel2 = setOf(6, 14)), 2, 100)!!
        assertEquals(listOf(1..5, 6..13, 14..20), t.map { it.inicio..it.fim })
    }

    @Test
    fun `secoes demais passam do maximo de topicos e nao viram proposta`() {
        assertNull(Cobertura.porEstrutura(trechos(60, (1..60 step 2).associateWith { "Cap $it" }), 2, 10))
    }

    @Test
    fun `apostila de catalogacao - areas viram topicos e a Area 3 de um trecho so entra na Area 2`() {
        // mesmos pontos de abertura de capítulo (nível 1) da apostila enviada pelo usuário (54 trechos)
        val aberturas = listOf(2, 4, 6, 9, 12, 21, 24, 25, 30, 35, 38, 40, 42, 43, 45, 47, 48, 49, 50, 53, 54)
        val nomes = aberturas.associateWith { "Cap@$it" } + mapOf(21 to "5. Área 2", 24 to "6. Área 3", 25 to "7. Área 4", 30 to "8. Área 5", 35 to "9. Área 6", 38 to "10. Área 7")
        val t = Cobertura.porEstrutura(trechos(54, nomes), 2, 100)!!
        val faixas = t.associate { it.nome to (it.inicio..it.fim) }
        assertEquals(21..24, faixas["5. Área 2 a 6. Área 3"], "Área 3 (1 trecho) se junta à Área 2 e o nome mostra o intervalo")
        assertEquals(25..29, faixas["7. Área 4"])
        assertEquals(30..34, faixas["8. Área 5"])
        assertEquals(35..37, faixas["9. Área 6"])
        assertTrue(t.all { it.fim - it.inicio + 1 >= 2 }, "nenhum tópico de um trecho só")
        assertTrue(cobre(t, 54))
    }

    @Test
    fun `nome de topico que juntou secoes mostra do primeiro ao ultimo titulo`() {
        val t = Cobertura.porEstrutura(trechos(12, mapOf(1 to "Cap A", 6 to "Cap B", 7 to "Cap C", 8 to "Cap D", 10 to "Cap E")), 2, 100)!!
        // Cap B (6) e Cap C (7) têm 1 trecho cada e se juntam a Cap A? Não: B tem 1 trecho -> junta em A; C -> junta em A; D (8-9) fica; E (10-12) fica
        assertEquals(listOf("Cap A a Cap C" to (1..7), "Cap D" to (8..9), "Cap E" to (10..12)), t.map { it.nome to (it.inicio..it.fim) })
    }
}
