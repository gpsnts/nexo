package br.nexo

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.time.Duration
import java.time.ZoneId

@ConfigurationProperties("nexo")
data class NexoProperties(
    val formatosAceitos: List<String>,
    val tamanhoMaximoMb: Int,
    val paginasMaximas: Int,
    val limiteOperacoesPorHora: Int,
    val tentativasPorQuestao: Int,
    val questoesPorSessaoRevisao: Int,
    val fuso: ZoneId,
    val limiarSimilaridadeRepeticao: Double,
    val figurasPorMaterial: Int,
    val limiteGastoUsd: BigDecimal,
    val janelaGasto: Duration,
    val revisao: Revisao,
    val processamento: Processamento,
) {
    data class Revisao(
        val repeticoesInicial: Int,
        val intervaloInicial: Int,
        val fatorInicial: BigDecimal,
        val fatorMaximo: BigDecimal,
        val fatorMinimo: BigDecimal,
        val fatorAcerto: BigDecimal,
        val fatorErro: BigDecimal,
        val intervaloMaximo: Int,
    )

    data class Processamento(val url: String)
}
