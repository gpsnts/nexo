package br.nexo

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.time.Duration
import java.time.ZoneId

/** Parâmetros de negócio e de infraestrutura do bloco `nexo:` do application.yml (nada fixo no código; sobrescreva por variável de ambiente). */
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
    val cota: Cota,
    val revisao: Revisao,
    val painel: Painel,
    val chat: Chat,
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
    /**
     * US$ por milhão de tokens do embedding: a tabela por modelo e, na falta dele, o preço único do `.env` (modelo trocado sem editar o application.yml).
     * @param modelo nome do modelo de embedding.
     * @return preço em US$ por milhão de tokens, ou nulo se o modelo não tiver preço configurado.
     */
    fun precoEmbedding(modelo: String): BigDecimal? = material.precosEmbeddingUsdPorMilhao[modelo] ?: material.precoEmbeddingUsdPorMilhao

    /**
     * Cota semanal de IA do estudante, em créditos. Crédito = custo estimado ÷ [usdPorCredito], arredondado para cima; operação concluída custa ao menos
     * [minimoPorOperacao] (o provedor falso, de custo zero, também mostra consumo). A semana vai de segunda a domingo, no fuso configurado.
     */
    data class Cota(val creditosPorSemana: Int, val usdPorCredito: BigDecimal, val minimoPorOperacao: Int)

    /** Chat com o tutor: tamanho máximo da mensagem, quantas falas anteriores vão ao modelo, quantas mensagens um tópico guarda e os cortes de tamanho (a resposta gravada e cada fala reenviada, que o serviço de IA limita a 4000 caracteres). */
    data class Chat(val mensagemMaxCaracteres: Int, val historicoEnviado: Int, val mensagensPorTopico: Int, val respostaMaxCaracteres: Int, val falaEnviadaMaxCaracteres: Int)

    /** Painel semanal: janela padrão e máxima em semanas, mínimo de respostas para um tópico entrar nos mais fracos e quantos mostrar. */
    data class Painel(val semanas: Int, val semanasMaximo: Int, val minimoRespostas: Int, val fracos: Int, val topicosRecentes: Int)

    /** Parâmetros do reagendamento SM-2 adaptado: valores iniciais, limites do fator, ajuste por acerto e erro e intervalo máximo em dias. */
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

    /** Parâmetros da indexação de material: armazenamento, executor em segundo plano, limites de envio, lotes de embedding, plano B dos tópicos e preços do embedding. */
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

    /** US$ por milhão de tokens do modelo de linguagem REAL, do painel do provedor. Sem os dois valores, operação com modelo real é recusada (o limite de gasto depende do custo). */
    data class PrecoLinguagem(val entradaUsdPorMilhao: BigDecimal?, val saidaUsdPorMilhao: BigDecimal?)

    /** Parâmetros de resumo, questões e verificação: executor em segundo plano, contexto, quantidades por pedido e por tópico, simultaneidade por usuário e preço do modelo de linguagem. */
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

    /** Parâmetros do JWT: segredo de assinatura e validade dos tokens de acesso e de refresh. */
    data class Jwt(val segredo: String, val validadeAcesso: Duration, val validadeRefresh: Duration)

    /** Bloqueio de login após tentativas erradas: máximo de tentativas e duração do bloqueio. */
    data class Login(val maxTentativas: Int, val duracaoBloqueio: Duration)

    /** Parâmetros da conta: validade dos tokens de redefinição e de confirmação de e-mail, limite de pedidos, URL do frontend, cookie do refresh, tarefa de limpeza de contas não verificadas e fila de segundo plano. */
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

    /** Limite de requisições por IP nas rotas públicas de autenticação. */
    data class LimitePublico(val requisicoes: Int, val janela: Duration)

    /** [hashConcorrencia] limita quantos hashes Argon2 (16 MiB cada) rodam ao mesmo tempo; o excedente espera até [hashEspera] e depois recebe 503. */
    data class Seguranca(val hashConcorrencia: Int, val hashEspera: Duration)

    /** Provedor de e-mail e remetente (`falso` em desenvolvimento). */
    data class Email(val provedor: String, val remetente: String)
}
