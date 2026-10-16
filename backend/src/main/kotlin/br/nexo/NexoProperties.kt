package br.nexo

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.time.Duration
import java.time.ZoneId

@ConfigurationProperties("nexo")
data class NexoProperties(
    val ambiente: String,
    val formatosAceitos: List<String>,
    val tamanhoMaximoMb: Int,
    val paginasMaximas: Int,
    val limiteOperacoesPorHora: Int,
    val janelaOperacoes: Duration,
    val tentativasPorQuestao: Int,
    val questoesPorSessaoRevisao: Int,
    val fuso: ZoneId,
    val limiarSimilaridadeRepeticao: Double,
    val figurasPorMaterial: Int,
    val limiteGastoUsd: BigDecimal,
    val janelaGasto: Duration,
    val revisao: Revisao,
    val processamento: Processamento,
    val material: Material,
    val geracao: Geracao,
    val jwt: Jwt,
    val login: Login,
    val conta: Conta,
    val email: Email,
    val limitePublico: LimitePublico,
    val seguranca: Seguranca,
) {
    /** US$ por milhão de tokens do embedding: a tabela por modelo e, na falta dele, o preço único do `.env` (modelo trocado sem editar o application.yml). */
    fun precoEmbedding(modelo: String): BigDecimal? = material.precosEmbeddingUsdPorMilhao[modelo] ?: material.precoEmbeddingUsdPorMilhao

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

    /** [segredo] é compartilhado com o serviço Python (cabeçalho X-Nexo-Segredo); sem valor padrão de propósito. */
    data class Processamento(val url: String, val segredo: String, val timeout: Duration, val tentativas: Int, val espera: Duration, val saudeTimeout: Duration, val cacheModelos: Duration)

    data class Material(
        val diretorio: String,
        val assincrono: Boolean,
        val threads: Int,
        val fila: Int,
        val simultaneosPorUsuario: Int,
        val enviosSimultaneos: Int,
        val caracteresPorPagina: Int,
        val loteEmbeddings: Int,
        val pulsoMaximo: Duration,
        val trechosPorTopicoPlanoB: Int,
        val trechosMinimosPorTopico: Int,
        val maxTopicos: Int,
        val esperasRemocao: List<Duration>,
        val precosEmbeddingUsdPorMilhao: Map<String, BigDecimal>,
        val precoEmbeddingUsdPorMilhao: BigDecimal? = null,
    )

    /** Resumo, questões e verificação (F3). O contexto é medido em caracteres (cerca de 4 por token). */
    /** US$ por milhão de tokens do modelo de linguagem REAL, do painel do provedor. Sem os dois valores, operação com modelo real é recusada (o limite de gasto depende do custo). */
    data class PrecoLinguagem(val entradaUsdPorMilhao: BigDecimal?, val saidaUsdPorMilhao: BigDecimal?)

    data class Geracao(
        val assincrono: Boolean,
        val threads: Int,
        val fila: Int,
        val pulsoMaximo: Duration,
        val contextoMaxCaracteres: Int,
        val questoesPorPedido: Int,
        val questoesPorTopico: Int,
        val resumosPorTopico: Int,
        val simultaneasPorUsuario: Int,
        val precoLinguagem: PrecoLinguagem = PrecoLinguagem(null, null),
        val enunciadosAEvitar: Int,
    )

    data class Jwt(val segredo: String, val validadeAcesso: Duration, val validadeRefresh: Duration)

    data class Login(val maxTentativas: Int, val duracaoBloqueio: Duration)

    data class Conta(
        val validadeRedefinicao: Duration,
        val validadeConfirmacaoEmail: Duration,
        val pedidosPorHora: Int,
        val urlFrontend: String,
        val cookieSeguro: Boolean,
        val caminhoCookie: String,
        val assincrono: Boolean,
        val limpezaCron: String,
        val diasContaNaoVerificada: Long,
        val filaSegundoPlano: Int,
    )

    data class LimitePublico(val requisicoes: Int, val janela: Duration)

    /** [hashConcorrencia] limita quantos hashes Argon2 (16 MiB cada) rodam ao mesmo tempo; o excedente espera até [hashEspera] e depois recebe 503. */
    data class Seguranca(val hashConcorrencia: Int, val hashEspera: Duration)

    data class Email(val provedor: String, val remetente: String)
}
