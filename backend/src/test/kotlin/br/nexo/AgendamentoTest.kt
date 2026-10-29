package br.nexo

import br.nexo.NexoProperties.Revisao
import br.nexo.estudo.Agendamento
import br.nexo.estudo.reagendar
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Reagendamento sem Spring: o SM-2 adaptado como função pura, com datas explícitas. */
class AgendamentoTest {
    private val p = Revisao(0, 1, BigDecimal("2.50"), BigDecimal("2.50"), BigDecimal("1.30"), BigDecimal("0.10"), BigDecimal("0.20"), 365)
    private val dia0 = LocalDate.of(2026, 3, 2)
    private fun inicial(hoje: LocalDate) = Agendamento(0, 1, BigDecimal("2.50"), hoje, hoje)
    private fun resumo(a: Agendamento) = "${a.repeticoes}/${a.intervaloDias}/${a.fator}/${a.proximaRevisao}"

    @Test
    fun `certo certo certo errado certo produz os intervalos esperados`() {
        // (dia, correta) -> repeticoes/intervalo/fator/proxima
        val passos = listOf(0L to true, 1L to true, 7L to true, 22L to false, 23L to true)
        val esperado = listOf("1/1/2.50/${dia0.plusDays(1)}", "2/6/2.50/${dia0.plusDays(7)}", "3/15/2.50/${dia0.plusDays(22)}", "0/1/2.30/${dia0.plusDays(23)}", "1/1/2.40/${dia0.plusDays(24)}")
        var a = inicial(dia0)
        passos.forEachIndexed { i, (dia, correta) -> a = reagendar(a, i == 0, correta, dia0.plusDays(dia), p); assertEquals(esperado[i], resumo(a), "passo ${i + 1}") }
    }

    @Test
    fun `acerto antecipado nao altera mas o primeiro acerto sempre conta`() {
        val primeiro = reagendar(inicial(dia0), true, true, dia0, p)
        assertEquals("1/1/2.50/${dia0.plusDays(1)}", resumo(primeiro))
        assertEquals(primeiro, reagendar(primeiro, false, true, dia0, p), "no mesmo dia, antes da próxima revisão")
    }

    @Test
    fun `fator nao passa de 2_50 nem cai de 1_30 e o intervalo para em 365`() {
        var a = inicial(dia0)
        repeat(8) { a = reagendar(a, false, false, dia0, p) }
        assertEquals(BigDecimal("1.30"), a.fator)
        val longo = Agendamento(5, 300, BigDecimal("2.50"), dia0, dia0)
        assertEquals(365, reagendar(longo, false, true, dia0, p).intervaloDias)
    }

    @Test
    fun `arredonda metade para cima`() {
        // 5 x 1,30 = 6,5 -> 7
        val a = Agendamento(2, 5, BigDecimal("1.30"), dia0, dia0)
        assertEquals(7, reagendar(a, false, true, dia0, p).intervaloDias)
    }
}
