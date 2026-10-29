package br.nexo.conta

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/** Sessões e tokens vencidos não servem mais para nada (um token vencido é recusado de qualquer jeito); sem isso as tabelas só crescem. */
@Component
class LimpezaConta(
    private val sessoes: SessaoRepository,
    private val tokens: TokenContaRepository,
    private val usuarios: UsuarioRepository,
    private val props: br.nexo.NexoProperties,
    private val relogio: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Apaga sessões e tokens vencidos e contas nunca verificadas (roda no cron configurado).
     * @return total de linhas apagadas.
     */
    @Scheduled(cron = "\${nexo.conta.limpeza-cron}", zone = "\${nexo.fuso}")
    fun executar(): Int {
        val agora = relogio.instant()
        // Conta nunca verificada não tem sessão nem dados (não consegue entrar): apagar só libera o e-mail para o dono de verdade.
        val contas = usuarios.apagarNaoVerificadas(agora.minus(java.time.Duration.ofDays(props.conta.diasContaNaoVerificada)))
        val total = sessoes.apagarVencidas(agora) + tokens.apagarVencidos(agora) + contas
        log.info("Limpeza: {} linhas (inclui {} contas nunca verificadas)", total, contas)
        return total
    }
}
