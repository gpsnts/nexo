package br.nexo.material

import br.nexo.NexoProperties
import br.nexo.conta.ErroNegocio
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Uso(
    val operacoes: Int, val limiteOperacoes: Int, val janelaOperacoesMinutos: Long,
    val gastoUsd: BigDecimal, val limiteGastoUsd: BigDecimal, val janelaGastoDias: Long,
)

/**
 * Limites por usuário nas operações com IA (regra 11 e requisitos não funcionais): quantidade por janela (padrão 30 por hora) e gasto estimado
 * por janela (padrão US$ 10 em 30 dias). Ambos contam os registros de `registro_geracao` do usuário (a RLS já filtra a conta), inclusive os que
 * falharam. Conferido ANTES de iniciar a operação (envio de material, reorganização com IA e, na F3, resumo e questões); ao atingir, 429 com
 * o prazo de liberação. O gasto é estimado e só fica completo ao fim de cada operação, então o limite é aproximado, e duas operações iniciadas
 * ao mesmo tempo podem passar da conta por pouco (ADR 0033). Deve ser chamado dentro de uma transação (RLS).
 */
@Service
class LimitesIa(private val jdbc: JdbcTemplate, private val props: NexoProperties) {
    private class Linha(val custo: BigDecimal, val restanteSegundos: Long)

    private fun linhas(janelaSegundos: Long, soComCusto: Boolean) = jdbc.query(
        """SELECT custo_estimado_usd, ceil(extract(epoch FROM (criado_em + make_interval(secs => ?) - now())))::bigint AS restante
           FROM registro_geracao WHERE criado_em > now() - make_interval(secs => ?) ${if (soComCusto) "AND custo_estimado_usd > 0" else ""} ORDER BY criado_em""",
        { rs, _ -> Linha(rs.getBigDecimal(1), rs.getLong(2).coerceAtLeast(1)) }, janelaSegundos.toDouble(), janelaSegundos.toDouble(),
    )

    private val janelaOps get() = props.janelaOperacoes.seconds
    private val janelaGasto get() = props.janelaGasto.seconds

    fun uso(): Uso = Uso(
        linhas(janelaOps, false).size, props.limiteOperacoesPorHora, props.janelaOperacoes.toMinutes(),
        linhas(janelaGasto, true).fold(BigDecimal.ZERO) { a, l -> a + l.custo }.setScale(6, RoundingMode.HALF_UP), props.limiteGastoUsd, props.janelaGasto.toDays(),
    )

    /** Lança 429 `limite_operacoes` ou `limite_gasto` (com `esperaSegundos`) se o usuário já atingiu um dos limites. */
    fun exigirDisponivel() {
        val ops = linhas(janelaOps, false)
        if (ops.size >= props.limiteOperacoesPorHora) {
            val espera = ops[ops.size - props.limiteOperacoesPorHora].restanteSegundos  // a mais antiga que precisa sair da janela para abrir uma vaga
            throw ErroNegocio(
                HttpStatus.TOO_MANY_REQUESTS,
                "Você atingiu o limite de ${props.limiteOperacoesPorHora} operações com IA por ${descreverJanela(janelaOps)}. Tente novamente em ${descreverEspera(espera)}.",
                "limite_operacoes", mapOf("esperaSegundos" to espera),
            )
        }
        val gastos = linhas(janelaGasto, true)
        var total = gastos.fold(BigDecimal.ZERO) { a, l -> a + l.custo }
        if (total >= props.limiteGastoUsd) {
            var i = 0
            while (total >= props.limiteGastoUsd) total -= gastos[i++].custo   // gasto que precisa sair da janela para voltar abaixo do limite
            val espera = gastos[i - 1].restanteSegundos
            val quando = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm").withZone(props.fuso).format(Instant.now().plusSeconds(espera))
            throw ErroNegocio(
                HttpStatus.TOO_MANY_REQUESTS,
                "Você atingiu o limite de gasto com IA (US$ ${usd(props.limiteGastoUsd)} em ${props.janelaGasto.toDays()} dias). Novas operações com IA ficam bloqueadas até $quando.",
                "limite_gasto", mapOf("esperaSegundos" to espera),
            )
        }
    }

    private fun usd(v: BigDecimal) = String.format(Locale.forLanguageTag("pt-BR"), "%.2f", v)
    private fun descreverJanela(s: Long) = if (s % 3600 == 0L) (if (s == 3600L) "hora" else "${s / 3600} horas") else "${s / 60} minutos"
    private fun descreverEspera(s: Long) = if (s < 90) "$s segundos" else if (s < 5400) "${(s + 59) / 60} minutos" else "${(s + 3599) / 3600} horas"
}
