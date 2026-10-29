package br.nexo.material

import br.nexo.NexoProperties
import br.nexo.conta.ErroNegocio
import br.nexo.seguranca.UsuarioAtual
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** `percentual` e `renovaEm` são o que a tela mostra; `operacoes` e o limite por hora servem só à proteção contra abuso. O dólar não sai do servidor. */
data class Uso(
    val creditosUsados: Int, val creditosLimite: Int, val percentual: Int, val renovaEm: Instant,
    val operacoes: Int, val limiteOperacoes: Int, val janelaOperacoesMinutos: Long,
)

/**
 * Limites por usuário nas operações com IA, contados nos registros de `registro_geracao` (a RLS já filtra a conta) e conferidos
 * ANTES de iniciar a operação:
 * - **cota semanal de créditos** (a que o estudante vê): semana de segunda a domingo no fuso configurado; ao esgotar, 429 `limite_creditos` com a hora em que renova;
 * - **proteção contra rajada** (escondida): no máximo N operações por hora (429 `limite_operacoes`), contra abuso e laço de erro.
 * O custo é estimado e só fica completo ao fim da operação, então a cota é aproximada, e duas operações iniciadas juntas podem passar dela por pouco.
 * Deve ser chamado dentro de uma transação (RLS).
 */
@Service
class LimitesIa(private val jdbc: JdbcTemplate, private val props: NexoProperties, private val relogio: Clock) {
    /**
     * Operação da janela de proteção contra rajada.
     * @property restanteSegundos segundos até a operação sair da janela.
     */
    private class Linha(val restanteSegundos: Long)

    /**
     * Operações dentro da janela por hora, da mais antiga para a mais nova.
     * @return uma linha por operação.
     */
    private fun operacoesDaJanela() = jdbc.query(
        """SELECT ceil(extract(epoch FROM (criado_em + make_interval(secs => ?) - now())))::bigint AS restante
           FROM registro_geracao WHERE criado_em > now() - make_interval(secs => ?) ORDER BY criado_em""",
        { rs, _ -> Linha(rs.getLong(1).coerceAtLeast(1)) }, props.janelaOperacoes.seconds.toDouble(), props.janelaOperacoes.seconds.toDouble(),
    )

    private val segunda get() = relogio.instant().atZone(props.fuso).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    /**
     * Início da semana de cota.
     * @return a segunda-feira à meia-noite, no fuso configurado.
     */
    private fun inicioDaSemana(): Instant = segunda.atStartOfDay(props.fuso).toInstant()
    /**
     * Fim da semana de cota.
     * @return a próxima segunda-feira à meia-noite, no fuso configurado.
     */
    private fun renovaEm(): Instant = segunda.atStartOfDay(props.fuso).plusWeeks(1).toInstant()

    /**
     * Concluída ou ainda aberta custa ao menos o mínimo (a aberta reserva a vaga); falha ou cancelamento custam só o que gastaram (falha do serviço não pune o estudante).
     * @return créditos usados na semana.
     */
    private fun creditosDaSemana(): Int = jdbc.queryForObject(
        """SELECT coalesce(sum(CASE WHEN status <> 'falhou' THEN GREATEST(?::int, ceil(custo_estimado_usd / ?::numeric)::int) ELSE ceil(custo_estimado_usd / ?::numeric)::int END), 0)::int
           FROM registro_geracao WHERE criado_em >= ?""",
        Int::class.java, props.cota.minimoPorOperacao, props.cota.usdPorCredito, props.cota.usdPorCredito, Timestamp.from(inicioDaSemana()),
    )!!

    /**
     * Uso de IA do usuário, para a tela.
     * @return créditos usados e limite, percentual, renovação e dados da proteção contra rajada.
     */
    fun uso(): Uso {
        val usados = creditosDaSemana(); val limite = props.cota.creditosPorSemana
        return Uso(usados, limite, (usados * 100L / limite).toInt().coerceIn(0, 100), renovaEm(), operacoesDaJanela().size, props.limiteOperacoesPorHora, props.janelaOperacoes.toMinutes())
    }

    /** Lança 429 `limite_operacoes` (rajada) ou `limite_creditos` (cota da semana, com `esperaSegundos`) se o usuário já atingiu um dos limites. */
    fun exigirDisponivel() {
        // Trava por usuário até o fim da transação: quem confere e abre o registro na mesma transação enxerga os pedidos simultâneos (read committed não bastaria).
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", { _, _ -> 0 }, UsuarioAtual.id().toString())
        val ops = operacoesDaJanela()
        if (ops.size >= props.limiteOperacoesPorHora) {
            val espera = ops[ops.size - props.limiteOperacoesPorHora].restanteSegundos  // a mais antiga que precisa sair da janela para abrir uma vaga
            throw ErroNegocio(
                HttpStatus.TOO_MANY_REQUESTS,
                "Muitas operações com IA em pouco tempo (o limite é ${props.limiteOperacoesPorHora} por ${descreverJanela(props.janelaOperacoes)}). Tente novamente em ${descreverEspera(espera)}.",
                "limite_operacoes", mapOf("esperaSegundos" to espera),
            )
        }
        if (creditosDaSemana() >= props.cota.creditosPorSemana) {
            val renova = renovaEm()
            val quando = DateTimeFormatter.ofPattern("EEEE, dd/MM 'às' HH:mm", Locale.forLanguageTag("pt-BR")).withZone(props.fuso).format(renova)
            throw ErroNegocio(
                HttpStatus.TOO_MANY_REQUESTS,
                "Você usou toda a sua cota de IA desta semana. Ela renova na $quando.",
                "limite_creditos", mapOf("esperaSegundos" to Duration.between(relogio.instant(), renova).seconds.coerceAtLeast(1)),
            )
        }
    }

    /**
     * Descreve a janela de tempo em português.
     * @param d duração da janela.
     * @return texto como "hora", "2 horas" ou "30 minutos".
     */
    private fun descreverJanela(d: Duration) = if (d.seconds % 3600 == 0L) (if (d.seconds == 3600L) "hora" else "${d.seconds / 3600} horas") else "${d.toMinutes()} minutos"
    /**
     * Descreve uma espera em português.
     * @param s segundos de espera.
     * @return texto como "45 segundos", "10 minutos" ou "2 horas".
     */
    private fun descreverEspera(s: Long) = if (s < 90) "$s segundos" else if (s < 5400) "${(s + 59) / 60} minutos" else "${(s + 3599) / 3600} horas"
}
