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

data class FaixaRequest(
    val id: UUID?,
    @field:NotBlank(message = "Todo tópico precisa de um nome.") @field:Size(max = Cobertura.NOME_MAX, message = "O nome do tópico pode ter até 200 caracteres.") val nome: String,
    val inicio: Int,
    val fim: Int,
)

data class RevisaoRequest(@field:Size(max = 500, message = "Tópicos demais.") val topicos: List<@Valid FaixaRequest>)
data class TopicoLivreRequest(@field:NotBlank(message = "Informe o nome do tópico.") @field:Size(max = Cobertura.NOME_MAX, message = "O nome do tópico pode ter até 200 caracteres.") val nome: String, val assuntoId: UUID?)

/** Rotas de material, andamento e tópicos (docs/api-materiais.md). O usuário sai sempre do JWT; a RLS isola as contas. */
@RestController
class MaterialController(private val materiais: MaterialService, private val topicos: TopicoService, private val envios: EnviosSimultaneos, private val reorganizacao: Reorganizacao) {
    @PostMapping("/materiais")
    fun enviar(@RequestParam("arquivo") arquivo: MultipartFile, @RequestParam("assuntoId", required = false) assuntoId: UUID?): ResponseEntity<Map<String, UUID>> =
        envios.executar { ResponseEntity.status(HttpStatus.ACCEPTED).body(mapOf("materialId" to materiais.enviar(arquivo.originalFilename ?: "arquivo", arquivo.bytes, assuntoId))) }

    @GetMapping("/materiais")
    fun listar(@RequestParam(defaultValue = "100") limite: Int) = materiais.listar(limite.coerceIn(1, 200))

    @GetMapping("/materiais/{id}")
    fun obter(@PathVariable id: UUID) = materiais.obter(id)

    @GetMapping("/materiais/{id}/andamento")
    fun andamento(@PathVariable id: UUID) = materiais.andamento(id)

    @GetMapping("/materiais/{id}/trechos")
    fun trechos(@PathVariable id: UUID, @RequestParam(defaultValue = "1") de: Int, @RequestParam(defaultValue = "200") ate: Int) = materiais.trechos(id, de, ate)

    @GetMapping("/materiais/{id}/paginas")
    fun paginas(@PathVariable id: UUID) = materiais.paginas(id)

    @GetMapping("/materiais/{id}/topicos")
    fun topicosDoMaterial(@PathVariable id: UUID) = materiais.topicosDoMaterial(id)

    @PutMapping("/materiais/{id}/topicos")
    fun revisar(@PathVariable id: UUID, @Valid @RequestBody r: RevisaoRequest) =
        topicos.revisar(id, r.topicos.map { FaixaTopico(it.id, it.nome, it.inicio, it.fim) })

    /** "Reorganizar com IA": devolve um rascunho de divisão; não grava (o usuário confirma pelo PUT acima). */
    @PostMapping("/materiais/{id}/topicos/reorganizar")
    fun reorganizar(@PathVariable id: UUID) = reorganizacao.reorganizar(id)

    @PostMapping("/topicos")
    fun topicoLivre(@Valid @RequestBody r: TopicoLivreRequest) = ResponseEntity.status(HttpStatus.CREATED).body(topicos.criarLivre(r.nome, r.assuntoId))
}

data class QuestoesRequest(val quantidade: Int = 5)

/** Resumo, questões e andamento da geração (docs/api-materiais.md). 202 + polling em `GET /geracoes/{id}`; o usuário sai sempre do JWT e a RLS isola as contas. */
@RestController
class GeracaoController(private val geracao: Geracao) {
    @GetMapping("/topicos/livres")
    fun livres(@RequestParam(defaultValue = "100") limite: Int) = geracao.topicosLivres(limite.coerceIn(1, 200))

    @GetMapping("/topicos/{id}")
    fun topico(@PathVariable id: UUID) = geracao.detalhe(id)

    @GetMapping("/topicos/{id}/resumos")
    fun resumos(@PathVariable id: UUID) = geracao.resumos(id)

    @GetMapping("/topicos/{id}/questoes")
    fun questoes(@PathVariable id: UUID) = geracao.questoes(id)

    @PostMapping("/topicos/{id}/resumo")
    fun resumo(@PathVariable id: UUID) = ResponseEntity.status(HttpStatus.ACCEPTED).body(geracao.pedirResumo(id))

    @PostMapping("/topicos/{id}/questoes")
    fun gerarQuestoes(@PathVariable id: UUID, @RequestBody(required = false) r: QuestoesRequest?) =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(geracao.pedirQuestoes(id, (r ?: QuestoesRequest()).quantidade))

    @PostMapping("/geracoes/{id}/cancelar")
    fun cancelar(@PathVariable id: UUID) = geracao.cancelar(id)

    @GetMapping("/geracoes/{id}")
    fun andamento(@PathVariable id: UUID) = geracao.consultar(id)
}
