package br.nexo.estudo

import br.nexo.NexoProperties
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Estado de repetição espaçada de uma questão para um usuário.
 * @property repeticoes acertos seguidos.
 * @property intervaloDias intervalo atual até a próxima revisão.
 * @property fator fator de facilidade.
 * @property ultimaRevisao data da última resposta.
 * @property proximaRevisao data em que a questão volta à revisão.
 */
data class Agendamento(val repeticoes: Int, val intervaloDias: Int, val fator: BigDecimal, val ultimaRevisao: LocalDate, val proximaRevisao: LocalDate)

/**
 * SM-2 adaptado a certo/errado. Função pura: o estado inicial e as datas vêm de quem chama.
 * @param a estado atual.
 * @param primeira se é a primeira resposta à questão.
 * @param correta se a resposta estava certa.
 * @param hoje data da resposta, no fuso configurado.
 * @param p parâmetros do reagendamento.
 * @return o novo estado (o próprio [a] se o acerto foi antecipado e não é a primeira resposta).
 */
fun reagendar(a: Agendamento, primeira: Boolean, correta: Boolean, hoje: LocalDate, p: NexoProperties.Revisao): Agendamento {
    if (!correta) {
        val fator = (a.fator - p.fatorErro).max(p.fatorMinimo)
        return Agendamento(0, 1, fator, hoje, hoje.plusDays(1))
    }
    if (!primeira && hoje.isBefore(a.proximaRevisao)) return a // acerto antecipado não altera
    val repeticoes = a.repeticoes + 1
    val intervalo = when (repeticoes) {
        1 -> 1
        2 -> 6
        else -> (BigDecimal(a.intervaloDias) * a.fator).setScale(0, RoundingMode.HALF_UP).toInt().coerceAtMost(p.intervaloMaximo)
    }
    return Agendamento(repeticoes, intervalo, (a.fator + p.fatorAcerto).min(p.fatorMaximo), hoje, hoje.plusDays(intervalo.toLong()))
}
