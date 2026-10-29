package br.nexo.material

import br.nexo.NexoProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** Falha do serviço de processamento. [retentavel] vale para 5xx e erro de rede; 4xx traz o [codigo] do serviço (sem_texto, formato_invalido...). */
class FalhaProcessamento(val codigo: String, message: String, val retentavel: Boolean = false) : RuntimeException(message)

/**
 * Resultado da inspeção de um arquivo.
 * @property tipo pdf, docx ou txt.
 * @property paginas páginas (nulo em TXT e DOCX).
 * @property caracteres total de caracteres.
 */
data class Inspecao(val tipo: String, val paginas: Int?, val caracteres: Int?)
/**
 * Trecho extraído e dividido pelo serviço Python.
 * @property ordem posição no material (base 1).
 * @property numeroPagina página, se houver.
 * @property conteudo texto do trecho.
 * @property titulo título da seção, se houver.
 * @property nivel nível do título, se houver.
 */
data class TrechoExtraido(val ordem: Int, val numeroPagina: Int?, val conteudo: String, val titulo: String?, val nivel: Int? = null)
/**
 * Resultado da extração.
 * @property tipo pdf, docx ou txt.
 * @property paginas páginas, se houver.
 * @property caracteres total de caracteres.
 * @property trechos trechos em ordem.
 */
data class Extracao(val tipo: String, val paginas: Int?, val caracteres: Int, val trechos: List<TrechoExtraido>)
/**
 * Embeddings devolvidos pelo serviço.
 * @property vetores um vetor por texto.
 * @property tokens tokens consumidos.
 * @property modelo modelo de embedding usado.
 */
data class Vetores(val vetores: List<List<Double>>, val tokens: Int, val modelo: String)
/**
 * Tópico proposto: nome e faixa de trechos.
 * @property nome nome do tópico.
 * @property inicio ordem do primeiro trecho.
 * @property fim ordem do último trecho.
 */
data class TopicoProposto(val nome: String, val inicio: Int, val fim: Int)
/**
 * Proposta de divisão em tópicos, com o gasto.
 * @property topicos tópicos propostos.
 * @property tokensEntrada tokens do prompt.
 * @property tokensSaida tokens gerados.
 * @property modelo modelo de linguagem usado.
 */
data class PropostaTopicos(val topicos: List<TopicoProposto>, val tokensEntrada: Int, val tokensSaida: Int, val modelo: String)
/**
 * Tópico atual enviado à reorganização.
 * @property nome nome.
 * @property inicio primeiro trecho.
 * @property fim último trecho.
 * @property congelado se já tem resumo ou questões.
 */
data class TopicoAtual(val nome: String, val inicio: Int, val fim: Int, val congelado: Boolean)
/**
 * Resumo de um trecho enviado para propor tópicos.
 * @property ordem posição do trecho.
 * @property nivel nível do título.
 * @property titulo título do trecho.
 * @property inicio primeiras palavras do trecho.
 */
data class ResumoTrecho(val ordem: Int, val nivel: Int?, val titulo: String?, val inicio: String?)

/** `ordem` é o rótulo do trecho dentro do contexto enviado (1, 2, 3...), não a ordem no material: tópicos de vários materiais repetiriam números. */
data class TrechoConteudo(val ordem: Int, val conteudo: String)
/**
 * Texto gerado pelo modelo, com o gasto.
 * @property texto texto devolvido.
 * @property tokensEntrada tokens do prompt.
 * @property tokensSaida tokens gerados.
 * @property modelo modelo usado.
 */
data class Resumido(val texto: String, val tokensEntrada: Int, val tokensSaida: Int, val modelo: String)
/**
 * Questão gerada, ainda não verificada.
 * @property enunciado pergunta.
 * @property correta alternativa correta.
 * @property erradas alternativas erradas.
 * @property ordens rótulos dos trechos citados.
 */
data class QuestaoGerada(val enunciado: String, val correta: String, val erradas: List<String>, val ordens: List<Int>)
/**
 * Questões geradas, com o gasto.
 * @property questoes questões candidatas.
 * @property tokensEntrada tokens do prompt.
 * @property tokensSaida tokens gerados.
 * @property modelo modelo usado.
 */
data class Geradas(val questoes: List<QuestaoGerada>, val tokensEntrada: Int, val tokensSaida: Int, val modelo: String)
/**
 * Veredito da verificação de fidelidade.
 * @property sustentada se os trechos sustentam a questão.
 * @property unicaCorreta se só a alternativa marcada está correta.
 * @property ordens trechos que sustentam.
 * @property motivo justificativa.
 * @property tokensEntrada tokens do prompt.
 * @property tokensSaida tokens gerados.
 * @property modelo modelo usado.
 */
data class Verificada(val sustentada: Boolean, val unicaCorreta: Boolean, val ordens: List<Int>, val motivo: String, val tokensEntrada: Int, val tokensSaida: Int, val modelo: String)

/** Contrato interno com o serviço Python. Ele não acessa o banco: recebe e devolve dados. */
interface Processamento {
    /**
     * Valida o arquivo e conta páginas e caracteres, sem extrair o conteúdo.
     * @param nome nome do arquivo.
     * @param dados conteúdo.
     * @return tipo, páginas e caracteres.
     * @throws FalhaProcessamento se o arquivo for inválido ou o serviço falhar.
     */
    fun inspecionar(nome: String, dados: ByteArray): Inspecao
    /**
     * Extrai o texto e divide em trechos.
     * @param nome nome do arquivo.
     * @param dados conteúdo.
     * @return a extração.
     * @throws FalhaProcessamento se não houver texto, o formato for inválido ou o serviço falhar.
     */
    fun processar(nome: String, dados: ByteArray): Extracao
    /**
     * Gera embeddings de um lote de textos.
     * @param textos textos a vetorizar.
     * @return vetores, tokens e modelo.
     * @throws FalhaProcessamento se o serviço ou o provedor falhar.
     */
    fun embeddings(textos: List<String>): Vetores
    /**
     * Pede ao modelo uma divisão em tópicos.
     * @param trechos resumos dos trechos do material.
     * @return tópicos propostos e gasto.
     * @throws FalhaProcessamento se o serviço ou o provedor falhar.
     */
    fun topicos(trechos: List<ResumoTrecho>): PropostaTopicos
    /**
     * Pede ao modelo uma nova divisão, preservando os tópicos congelados.
     * @param trechos resumos dos trechos.
     * @param atuais tópicos atuais.
     * @return tópicos propostos e gasto.
     * @throws FalhaProcessamento se o serviço ou o provedor falhar.
     */
    fun reorganizar(trechos: List<ResumoTrecho>, atuais: List<TopicoAtual>): PropostaTopicos
    /**
     * Nomes dos modelos em uso, do /saude.
     * @return par (modelo de linguagem, modelo de embedding).
     * @throws FalhaProcessamento se o serviço estiver fora, o provedor for falso em produção ou faltar preço.
     */
    fun modelos(): Pair<String, String>  // (linguagem, embedding), do /saude
    /**
     * Gera o resumo de um tópico.
     * @param topico nome do tópico.
     * @param trechos trechos de contexto (vazio = conhecimento geral).
     * @param orientacao orientação opcional do estudante (foco, banca, estilo), ou nulo.
     * @return o texto e o gasto.
     * @throws FalhaProcessamento se o serviço ou o provedor falhar.
     */
    fun resumir(topico: String, trechos: List<TrechoConteudo>, orientacao: String? = null): Resumido
    /**
     * Gera questões de múltipla escolha.
     * @param topico nome do tópico.
     * @param trechos trechos de contexto.
     * @param quantidade quantas questões pedir.
     * @param evitar enunciados já existentes.
     * @param dificuldade facil, media ou dificil.
     * @param orientacao orientação opcional do estudante (foco, banca, estilo), ou nulo.
     * @return as questões candidatas e o gasto.
     * @throws FalhaProcessamento se o serviço ou o provedor falhar.
     */
    fun gerarQuestoes(topico: String, trechos: List<TrechoConteudo>, quantidade: Int, evitar: List<String>, dificuldade: String = "media", orientacao: String? = null): Geradas
    /**
     * Verifica a fidelidade de uma questão aos trechos.
     * @param topico nome do tópico.
     * @param trechos trechos citados.
     * @param enunciado pergunta.
     * @param alternativas alternativas.
     * @param correta índice da correta.
     * @return o veredito e o gasto.
     * @throws FalhaProcessamento se o serviço ou o provedor falhar.
     */
    fun verificar(topico: String, trechos: List<TrechoConteudo>, enunciado: String, alternativas: List<String>, correta: Int): Verificada
    /**
     * Gera uma dica leve para uma questão.
     * @param topico nome do tópico.
     * @param trechos trechos que sustentam a questão.
     * @param enunciado pergunta.
     * @param alternativas alternativas.
     * @param correta índice da correta.
     * @return o texto da dica e o gasto.
     * @throws FalhaProcessamento se o serviço ou o provedor falhar.
     */
    fun darDica(topico: String, trechos: List<TrechoConteudo>, enunciado: String, alternativas: List<String>, correta: Int): Resumido
    /**
     * Responde a uma pergunta do chat. [historico]: pares (papel, conteúdo) com papel `usuario` ou `assistente`, do mais antigo ao mais novo.
     * @param topico nome do tópico.
     * @param trechos trechos de contexto.
     * @param historico conversa anterior.
     * @param pergunta nova pergunta.
     * @param orientacao orientação opcional do estudante (foco, banca, estilo), ou nulo.
     * @return a resposta e o gasto.
     * @throws FalhaProcessamento se o serviço ou o provedor falhar.
     */
    fun conversar(topico: String, trechos: List<TrechoConteudo>, historico: List<Pair<String, String>>, pergunta: String, orientacao: String? = null): Resumido
    /**
     * Nome curto para o estudo, a partir dos nomes dos tópicos e dos primeiros trechos.
     * @param topicos nomes dos tópicos.
     * @param trechos primeiros trechos.
     * @return o nome sugerido e o gasto.
     * @throws FalhaProcessamento se o serviço ou o provedor falhar.
     */
    fun sugerirNome(topicos: List<String>, trechos: List<String>): Resumido
}

/** Cliente HTTP do serviço Python: segredo compartilhado no cabeçalho `X-Nexo-Segredo`, repetição em 5xx e erro de rede. */
@Component
class ProcessamentoHttp(private val props: NexoProperties, private val json: JsonMapper) : Processamento {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(java.time.Duration.ofSeconds(5)).build()  // HTTP/2 por padrão manda `Upgrade: h2c` e o uvicorn registra um aviso a cada chamada
    private val base get() = props.processamento.url.trimEnd('/')

    override fun inspecionar(nome: String, dados: ByteArray): Inspecao =
        enviarArquivo("/interno/inspecionar", nome, dados).let { Inspecao(it["tipo"].asString(), it["paginas"]?.takeIf { n -> !n.isNull }?.asInt(), it["caracteres"]?.takeIf { n -> !n.isNull }?.asInt()) }

    override fun processar(nome: String, dados: ByteArray): Extracao = enviarArquivo("/interno/processar", nome, dados).let { r ->
        Extracao(
            r["tipo"].asString(), r["paginas"]?.takeIf { !it.isNull }?.asInt(), r["caracteres"].asInt(),
            r["trechos"].toList().map { TrechoExtraido(it["ordem"].asInt(), it["numero_pagina"]?.takeIf { n -> !n.isNull }?.asInt(), it["conteudo"].asString(), it["titulo"]?.takeIf { n -> !n.isNull }?.asString(), it["nivel"]?.takeIf { n -> !n.isNull }?.asInt()) },
        )
    }

    override fun embeddings(textos: List<String>): Vetores =
        enviarJson("/interno/embeddings", mapOf("textos" to textos)).let { r -> Vetores(r["vetores"].toList().map { v -> v.toList().map { it.asDouble() } }, r["tokens"].asInt(), r["modelo"].asString()) }

    override fun topicos(trechos: List<ResumoTrecho>): PropostaTopicos =
        enviarJson("/interno/topicos", mapOf("trechos" to trechos.map { mapOf("ordem" to it.ordem, "nivel" to it.nivel, "titulo" to it.titulo, "inicio" to it.inicio) })).let { r ->
            PropostaTopicos(r["topicos"].toList().map { TopicoProposto(it["nome"].asString(), it["inicio"].asInt(), it["fim"].asInt()) }, r["tokens_entrada"].asInt(), r["tokens_saida"].asInt(), r["modelo"].asString())
        }

    override fun reorganizar(trechos: List<ResumoTrecho>, atuais: List<TopicoAtual>): PropostaTopicos =
        enviarJson(
            "/interno/reorganizar",
            mapOf(
                "trechos" to trechos.map { mapOf("ordem" to it.ordem, "nivel" to it.nivel, "titulo" to it.titulo, "inicio" to it.inicio) },
                "atuais" to atuais.map { mapOf("nome" to it.nome, "inicio" to it.inicio, "fim" to it.fim, "congelado" to it.congelado) },
            ),
        ).let { r -> PropostaTopicos(r["topicos"].toList().map { TopicoProposto(it["nome"].asString(), it["inicio"].asInt(), it["fim"].asInt()) }, r["tokens_entrada"].asInt(), r["tokens_saida"].asInt(), r["modelo"].asString()) }

    /**
     * Monta o corpo comum das chamadas de geração.
     * @param topico nome do tópico.
     * @param trechos trechos de contexto.
     * @return mapa mutável com `topico` e `trechos`.
     */
    private fun contexto(topico: String, trechos: List<TrechoConteudo>) = mutableMapOf<String, Any>("topico" to topico, "trechos" to trechos.map { mapOf("ordem" to it.ordem, "conteudo" to it.conteudo) })

    override fun resumir(topico: String, trechos: List<TrechoConteudo>, orientacao: String?): Resumido =
        enviarJson("/interno/resumo", contexto(topico, trechos).apply { orientacao?.let { put("orientacao", it) } }).let { Resumido(it["texto"].asString(), it["tokens_entrada"].asInt(), it["tokens_saida"].asInt(), it["modelo"].asString()) }

    override fun gerarQuestoes(topico: String, trechos: List<TrechoConteudo>, quantidade: Int, evitar: List<String>, dificuldade: String, orientacao: String?): Geradas =
        enviarJson("/interno/questoes", contexto(topico, trechos).apply { put("quantidade", quantidade); put("dificuldade", dificuldade); put("evitar", evitar); orientacao?.let { put("orientacao", it) } }).let { r ->
            Geradas(
                r["questoes"].toList().map { q -> QuestaoGerada(q["enunciado"].asString(), q["correta"].asString(), q["erradas"].toList().map { it.asString() }, q["ordens"].toList().map { it.asInt() }) },
                r["tokens_entrada"].asInt(), r["tokens_saida"].asInt(), r["modelo"].asString(),
            )
        }

    override fun verificar(topico: String, trechos: List<TrechoConteudo>, enunciado: String, alternativas: List<String>, correta: Int): Verificada =
        enviarJson("/interno/verificar", contexto(topico, trechos).apply { put("questao", mapOf("enunciado" to enunciado, "alternativas" to alternativas, "correta" to correta)) }).let { r ->
            Verificada(r["sustentada"].asBoolean(), r["unica_correta"].asBoolean(), r["ordens"].toList().map { it.asInt() }, r["motivo"]?.asString() ?: "", r["tokens_entrada"].asInt(), r["tokens_saida"].asInt(), r["modelo"].asString())
        }

    override fun darDica(topico: String, trechos: List<TrechoConteudo>, enunciado: String, alternativas: List<String>, correta: Int): Resumido =
        enviarJson("/interno/dica", contexto(topico, trechos).apply { put("questao", mapOf("enunciado" to enunciado, "alternativas" to alternativas, "correta" to correta)) }).let { r ->
            Resumido(r["texto"].asString(), r["tokens_entrada"].asInt(), r["tokens_saida"].asInt(), r["modelo"].asString())
        }

    override fun conversar(topico: String, trechos: List<TrechoConteudo>, historico: List<Pair<String, String>>, pergunta: String, orientacao: String?): Resumido =
        enviarJson("/interno/chat", contexto(topico, trechos).apply { put("historico", historico.map { mapOf("papel" to it.first, "conteudo" to it.second) }); put("pergunta", pergunta); orientacao?.let { put("orientacao", it) } }).let { r ->
            Resumido(r["texto"].asString(), r["tokens_entrada"].asInt(), r["tokens_saida"].asInt(), r["modelo"].asString())
        }

    override fun sugerirNome(topicos: List<String>, trechos: List<String>): Resumido =
        enviarJson("/interno/nome", mapOf("topicos" to topicos, "trechos" to trechos.mapIndexed { i, t -> mapOf("ordem" to i + 1, "conteudo" to t) })).let { r ->
            Resumido(r["texto"].asString(), r["tokens_entrada"].asInt(), r["tokens_saida"].asInt(), r["modelo"].asString())
        }

    /**
     * Cache dos nomes dos modelos.
     * @property em instante (nanossegundos) em que foi lido.
     * @property modelos par (linguagem, embedding).
     */
    private class Saude(val em: Long, val modelos: Pair<String, String>)
    @Volatile private var saude: Saude? = null

    /**
     * Nomes dos modelos, do /saude. Chamado no começo de toda operação com IA, então precisa falhar rápido com o serviço fora do ar (uma tentativa,
     * `saude-timeout`): antes eram 3 tentativas de 10 s e o pedido segurava uma thread do servidor por 33 s. O resultado vale por `cache-modelos`.
     */
    override fun modelos(): Pair<String, String> {
        val agora = System.nanoTime()
        val modelos = saude?.takeIf { agora - it.em < props.processamento.cacheModelos.toNanos() }?.modelos ?: run {
            val r = com(tentativas = 1) { HttpRequest.newBuilder(URI("$base/saude")).timeout(props.processamento.saudeTimeout).GET().build() }
            (r["modelo_linguagem"].asString() to r["modelo_embedding"].asString()).also { saude = Saude(agora, it) }
        }
        // Produção com provedor de IA falso entregaria conteúdo de demonstração como se fosse real: recusa antes de qualquer operação (chamado no começo de todas).
        if (props.ambiente == "producao" && (modelos.first.contains("falso") || modelos.second.contains("falso")))
            throw FalhaProcessamento("modelo_falso", "O serviço de processamento usa provedor de IA falso em produção (${modelos.first}, ${modelos.second})")
        // Modelo real sem preço configurado gastaria dinheiro sem que o limite de gasto (US$ por janela) pudesse enxergar: recusa antes de qualquer chamada.
        if (!modelos.first.contains("falso") && (props.geracao.precoLinguagem.entradaUsdPorMilhao == null || props.geracao.precoLinguagem.saidaUsdPorMilhao == null))
            throw FalhaProcessamento("preco_ausente", "Modelo de linguagem real (${modelos.first}) sem preço configurado: defina PRECO_LINGUAGEM_ENTRADA_USD_POR_MILHAO e PRECO_LINGUAGEM_SAIDA_USD_POR_MILHAO")
        if (!modelos.second.contains("falso") && props.precoEmbedding(modelos.second) == null)
            throw FalhaProcessamento("preco_ausente", "Modelo de embedding real (${modelos.second}) sem preço: defina PRECO_EMBEDDING_USD_POR_MILHAO (valor do painel do provedor) ou acrescente o modelo em nexo.material.precos-embedding-usd-por-milhao")
        return modelos
    }

    /**
     * Envia um JSON ao serviço.
     * @param rota rota interna (ex.: `/interno/resumo`).
     * @param corpo objeto serializado como JSON.
     * @return o JSON da resposta.
     * @throws FalhaProcessamento se o serviço recusar ou falhar.
     */
    private fun enviarJson(rota: String, corpo: Any): JsonNode = com {
        HttpRequest.newBuilder(URI(base + rota)).timeout(props.processamento.timeout).header("Content-Type", "application/json")
            .header("X-Nexo-Segredo", props.processamento.segredo).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo))).build()
    }

    /**
     * Envia um arquivo ao serviço (multipart).
     * @param rota rota interna.
     * @param nome nome do arquivo (não é enviado: evita path traversal).
     * @param dados conteúdo.
     * @return o JSON da resposta.
     * @throws FalhaProcessamento se o serviço recusar ou falhar.
     */
    private fun enviarArquivo(rota: String, nome: String, dados: ByteArray): JsonNode = com {
        val limite = "nexo" + UUID.randomUUID().toString().replace("-", "")
        val corpo = ByteArrayOutputStream().apply {
            write("--$limite\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"arquivo.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(dados)
            write("\r\n--$limite--\r\n".toByteArray())
        }.toByteArray()
        HttpRequest.newBuilder(URI(base + rota)).timeout(props.processamento.timeout).header("Content-Type", "multipart/form-data; boundary=$limite")
            .header("X-Nexo-Segredo", props.processamento.segredo).POST(HttpRequest.BodyPublishers.ofByteArray(corpo)).build()
    }

    /**
     * Tenta até `tentativas` vezes em 5xx e erro de rede, com espera crescente; 4xx sobe na hora (repetir não adianta).
     * @param tentativas número máximo de tentativas.
     * @param requisicao constrói a requisição a cada tentativa.
     * @return o JSON da resposta 200.
     * @throws FalhaProcessamento se a resposta for 4xx ou as tentativas acabarem.
     */
    private fun com(tentativas: Int = props.processamento.tentativas, requisicao: () -> HttpRequest): JsonNode {
        var ultima: FalhaProcessamento? = null
        for (n in 1..tentativas) {
            try {
                val r = http.send(requisicao(), HttpResponse.BodyHandlers.ofString())
                if (r.statusCode() == 200) return json.readTree(r.body())
                val detalhe = runCatching { json.readTree(r.body())["detail"] }.getOrNull()
                val codigo = detalhe?.takeIf { it.isObject }?.get("codigo")?.asString() ?: "falha_ia"
                if (r.statusCode() < 500) throw FalhaProcessamento(codigo, "Processamento recusou (HTTP ${r.statusCode()}): ${r.body().take(200)}")
                ultima = FalhaProcessamento(if (codigo == "falha_ia") "falha_ia" else codigo, "Processamento falhou (HTTP ${r.statusCode()}): ${r.body().take(200)}", true)
            } catch (e: FalhaProcessamento) {
                throw e
            } catch (e: IOException) {
                ultima = FalhaProcessamento("falha_ia", "Processamento indisponível: ${e.javaClass.simpleName}", true)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw FalhaProcessamento("falha_ia", "Interrompido")
            }
            log.warn("Serviço de processamento: tentativa {} de {} falhou ({})", n, tentativas, ultima.message)
            if (n < tentativas) Thread.sleep(props.processamento.espera.toMillis() * n)
        }
        throw ultima!!
    }
}
