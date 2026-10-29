package br.nexo.material

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/**
 * Faixa de um tópico no corpo da revisão.
 * @property id tópico existente, ou nulo se é novo.
 * @property nome nome do tópico.
 * @property inicio ordem do primeiro trecho.
 * @property fim ordem do último trecho.
 */
data class FaixaRequest(
    val id: UUID?,
    @field:NotBlank(message = "Todo tópico precisa de um nome.") @field:Size(max = Cobertura.NOME_MAX, message = "O nome do tópico pode ter até 200 caracteres.") val nome: String,
    val inicio: Int,
    val fim: Int,
)

/**
 * Corpo de `PUT /materiais/{id}/topicos`.
 * @property topicos lista completa de tópicos (até 500).
 */
data class RevisaoRequest(@field:Size(max = 500, message = "Tópicos demais.") val topicos: List<@Valid FaixaRequest>)
/**
 * Corpo de `POST /topicos`.
 * @property nome nome do tópico livre.
 * @property assuntoId pasta, ou nulo para o "Geral".
 * @property orientacao orientação opcional (foco, banca, estilo); até 500 caracteres.
 */
data class TopicoLivreRequest(@field:NotBlank(message = "Informe o nome do tópico.") @field:Size(max = Cobertura.NOME_MAX, message = "O nome do tópico pode ter até 200 caracteres.") val nome: String, val assuntoId: UUID?, val orientacao: String? = null)

/**
 * Corpo de `PUT /topicos/{id}/orientacao` e do pedido de resumo.
 * @property orientacao texto da orientação; em `PUT`, em branco ou nulo limpa; no pedido de resumo, nulo usa a gravada no tópico e texto (mesmo em branco) vale só para esta geração.
 */
data class OrientacaoRequest(val orientacao: String? = null)

/** Rotas de material, andamento e tópicos O usuário sai sempre do JWT; a RLS isola as contas. */
@RestController
class MaterialController(private val materiais: MaterialService, private val topicos: TopicoService, private val envios: EnviosSimultaneos, private val reorganizacao: Reorganizacao) {
    /**
     * Envia um material (PDF, DOCX ou TXT).
     * @param arquivo arquivo enviado.
     * @param assuntoId pasta de destino, opcional.
     * @return 202 com o `materialId`; a indexação segue em segundo plano.
     */
    @PostMapping("/materiais")
    fun enviar(@RequestParam("arquivo") arquivo: MultipartFile, @RequestParam("assuntoId", required = false) assuntoId: UUID?): ResponseEntity<Map<String, UUID>> =
        envios.executar { ResponseEntity.status(HttpStatus.ACCEPTED).body(mapOf("materialId" to materiais.enviar(arquivo.originalFilename ?: "arquivo", arquivo.bytes, assuntoId))) }

    /**
     * Lista os materiais.
     * @param limite máximo de itens (1 a 200).
     * @return os materiais do usuário.
     */
    @GetMapping("/materiais")
    fun listar(@RequestParam(defaultValue = "100") limite: Int) = materiais.listar(limite.coerceIn(1, 200))

    /**
     * Detalha um material.
     * @param id material.
     * @return o material.
     */
    @GetMapping("/materiais/{id}")
    fun obter(@PathVariable id: UUID) = materiais.obter(id)

    /**
     * Andamento da indexação.
     * @param id material.
     * @return status, etapa e percentual.
     */
    @GetMapping("/materiais/{id}/andamento")
    fun andamento(@PathVariable id: UUID) = materiais.andamento(id)

    /**
     * Prévia dos trechos.
     * @param id material.
     * @param de primeira ordem.
     * @param ate última ordem.
     * @return os trechos do intervalo.
     */
    @GetMapping("/materiais/{id}/trechos")
    fun trechos(@PathVariable id: UUID, @RequestParam(defaultValue = "1") de: Int, @RequestParam(defaultValue = "200") ate: Int) = materiais.trechos(id, de, ate)

    /**
     * Página de cada trecho.
     * @param id material.
     * @return números de página, na ordem dos trechos.
     */
    @GetMapping("/materiais/{id}/paginas")
    fun paginas(@PathVariable id: UUID) = materiais.paginas(id)

    /**
     * Tópicos de um material.
     * @param id material.
     * @return os tópicos.
     */
    @GetMapping("/materiais/{id}/topicos")
    fun topicosDoMaterial(@PathVariable id: UUID) = materiais.topicosDoMaterial(id)

    /**
     * Revisa a divisão em tópicos (renomear, unir, dividir, ajustar), numa transação.
     * @param id material.
     * @param r lista completa de tópicos.
     * @return os tópicos após a revisão.
     */
    @PutMapping("/materiais/{id}/topicos")
    fun revisar(@PathVariable id: UUID, @Valid @RequestBody r: RevisaoRequest) =
        topicos.revisar(id, r.topicos.map { FaixaTopico(it.id, it.nome, it.inicio, it.fim) })

    /**
     * "Reorganizar com IA": devolve um rascunho de divisão; não grava (o usuário confirma pelo PUT acima).
     * @param id material.
     * @return o rascunho proposto.
     */
    @PostMapping("/materiais/{id}/topicos/reorganizar")
    fun reorganizar(@PathVariable id: UUID) = reorganizacao.reorganizar(id)

    /**
     * Cria um tópico livre.
     * @param r nome, pasta e orientação opcional.
     * @return 201 com o tópico.
     */
    @PostMapping("/topicos")
    fun topicoLivre(@Valid @RequestBody r: TopicoLivreRequest) = ResponseEntity.status(HttpStatus.CREATED).body(topicos.criarLivre(r.nome, r.assuntoId, r.orientacao))

    /**
     * Grava ou limpa a orientação padrão de um tópico (livre ou de material).
     * @param id tópico.
     * @param r nova orientação; em branco limpa.
     * @return a orientação gravada, ou nulo se foi limpa.
     */
    @PutMapping("/topicos/{id}/orientacao")
    fun orientacao(@PathVariable id: UUID, @RequestBody r: OrientacaoRequest) = mapOf("orientacao" to topicos.definirOrientacao(id, r.orientacao))
}

/**
 * Corpo de `POST /topicos/{id}/questoes`.
 * @property quantidade quantidade de questões.
 * @property dificuldade facil, media ou dificil.
 * @property orientacao orientação só para esta geração; nulo usa a gravada no tópico, texto em branco gera sem orientação.
 */
data class QuestoesRequest(val quantidade: Int = 5, val dificuldade: String = "media", val orientacao: String? = null)

/** Resumo, questões e andamento da geração 202 + polling em `GET /geracoes/{id}`; o usuário sai sempre do JWT e a RLS isola as contas. */
@RestController
class GeracaoController(private val geracao: Geracao) {
    /**
     * Lista os tópicos livres.
     * @param limite máximo de itens (1 a 200).
     * @return os tópicos livres.
     */
    @GetMapping("/topicos/livres")
    fun livres(@RequestParam(defaultValue = "100") limite: Int) = geracao.topicosLivres(limite.coerceIn(1, 200))

    /**
     * Detalha um tópico.
     * @param id tópico.
     * @return o tópico com fontes e contagens.
     */
    @GetMapping("/topicos/{id}")
    fun topico(@PathVariable id: UUID) = geracao.detalhe(id)

    /**
     * Resumos do tópico.
     * @param id tópico.
     * @return os resumos mais recentes.
     */
    @GetMapping("/topicos/{id}/resumos")
    fun resumos(@PathVariable id: UUID) = geracao.resumos(id)

    /**
     * Questões do tópico.
     * @param id tópico.
     * @return as questões gravadas.
     */
    @GetMapping("/topicos/{id}/questoes")
    fun questoes(@PathVariable id: UUID) = geracao.questoes(id)

    /**
     * Pede um resumo do tópico.
     * @param id tópico.
     * @param r orientação só para esta geração, opcional.
     * @return 202 com o registro da geração, para consulta por polling.
     */
    @PostMapping("/topicos/{id}/resumo")
    fun resumo(@PathVariable id: UUID, @RequestBody(required = false) r: OrientacaoRequest?) = ResponseEntity.status(HttpStatus.ACCEPTED).body(geracao.pedirResumo(id, r?.orientacao))

    /**
     * Pede questões do tópico.
     * @param id tópico.
     * @param r quantidade e dificuldade (padrão 5 e média).
     * @return 202 com o registro da geração.
     */
    @PostMapping("/topicos/{id}/questoes")
    fun gerarQuestoes(@PathVariable id: UUID, @RequestBody(required = false) r: QuestoesRequest?) =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(geracao.pedirQuestoes(id, (r ?: QuestoesRequest()).quantidade, (r ?: QuestoesRequest()).dificuldade, r?.orientacao))

    /**
     * Cancela uma geração em andamento.
     * @param id registro da geração.
     * @return o estado do registro.
     */
    @PostMapping("/geracoes/{id}/cancelar")
    fun cancelar(@PathVariable id: UUID) = geracao.cancelar(id)

    /**
     * Consulta o andamento de uma geração.
     * @param id registro da geração.
     * @return status, progresso e resultado.
     */
    @GetMapping("/geracoes/{id}")
    fun andamento(@PathVariable id: UUID) = geracao.consultar(id)
}
