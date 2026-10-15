package br.nexo.material

import br.nexo.NexoProperties
import br.nexo.conta.ErroNegocio
import br.nexo.seguranca.Auditoria
import br.nexo.seguranca.UsuarioAtual
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class MaterialDto(val id: UUID, val nomeArquivo: String, val status: String, val motivoFalha: String?, val paginas: Int?, val criadoEm: Instant)
data class Andamento(val status: String, val etapa: String, val percentual: Int, val motivoFalha: String?)

@Service
class MaterialService(
    private val props: NexoProperties,
    private val dados: Dados,
    private val topicos: TopicoService,
    private val proc: Processamento,
    private val armazenamento: Armazenamento,
    private val remocao: RemocaoArquivos,
    private val indexador: Indexador,
    private val tx: TransactionTemplate,
    private val auditoria: Auditoria,
    private val relogio: Clock,
    private val limites: LimitesIa,
) {
    private fun usuario() = UsuarioAtual.id() ?: throw ErroNegocio(HttpStatus.UNAUTHORIZED, "Não autenticado.", "nao_autenticado")
    private fun naoEncontrado() = ErroNegocio(HttpStatus.NOT_FOUND, "Material não encontrado.", "nao_encontrado")
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** UC02 passos 3 e 4. Devolve o id do material (status pendente); a indexação segue em segundo plano. */
    fun enviar(nomeOriginal: String, bytes: ByteArray, assuntoId: UUID?): UUID {
        val uid = usuario()
        val nome = nomeOriginal.substringAfterLast('/').substringAfterLast('\\').trim().take(255)
        val extensao = nome.substringAfterLast('.', "").lowercase()
        val aceitos = props.formatosAceitos.joinToString(", ") { it.uppercase() }
        if (extensao !in props.formatosAceitos) throw ErroNegocio(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Formato não aceito. Envie $aceitos.", "formato_nao_aceito")
        if (bytes.isEmpty()) throw ErroNegocio(HttpStatus.BAD_REQUEST, "O arquivo está vazio.", "arquivo_vazio")
        if (bytes.size > props.tamanhoMaximoMb * 1024L * 1024L) throw ErroNegocio(HttpStatus.CONTENT_TOO_LARGE, "O arquivo passa de ${props.tamanhoMaximoMb} MB.", "arquivo_grande")

        val hash = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val assunto = tx.execute { // checagens baratas antes de gastar uma chamada ao Python
            val destino = assuntoId ?: topicos.assuntoPadrao()
            if (assuntoId != null && dados.jdbc.queryForObject("SELECT count(*) FROM assunto WHERE id = ?", Int::class.java, assuntoId) == 0)
                throw ErroNegocio(HttpStatus.NOT_FOUND, "Assunto não encontrado.", "assunto_nao_encontrado")
            dados.materialAtivoPorHash(hash)?.let {
                throw ErroNegocio(HttpStatus.CONFLICT, "Você já enviou este arquivo.", "material_repetido", mapOf("materialId" to it.id.toString()))
            }
            if (dados.emProcessamento() >= props.material.simultaneosPorUsuario)
                throw ErroNegocio(HttpStatus.CONFLICT, "Você já tem ${props.material.simultaneosPorUsuario} materiais sendo processados. Aguarde um terminar.", "processamento_em_andamento")
            limites.exigirDisponivel()  // por último: duplicado ou processamento em andamento é mais útil de saber do que o limite
            destino
        }

        val insp = try { proc.inspecionar(nome, bytes) } catch (e: FalhaProcessamento) {
            // Só o código do próprio serviço diz que o ARQUIVO é inválido; segredo errado (401), 413 ou 5xx são problema nosso, não do usuário.
            if (e.codigo != "formato_invalido") throw ErroNegocio(HttpStatus.SERVICE_UNAVAILABLE, "O serviço de processamento está indisponível. Tente novamente em instantes.", "processamento_indisponivel")
            throw ErroNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "O arquivo não é um ${aceitos} válido.", "formato_invalido")
        }
        if (insp.tipo != extensao) throw ErroNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "O conteúdo do arquivo não corresponde à extensão .$extensao.", "formato_invalido")
        val estimadas = insp.paginas ?: ((insp.caracteres ?: 0) + props.material.caracteresPorPagina - 1) / props.material.caracteresPorPagina
        if (estimadas > props.paginasMaximas) throw ErroNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "O material passa de ${props.paginasMaximas} páginas${if (insp.paginas == null) " (estimado)" else ""}.", "paginas_excedidas")

        val id = UUID.randomUUID()
        val chave = "$uid/$id"
        armazenamento.salvar(chave, bytes)
        val linha = MaterialLinha(id, nome, MIME.getValue(insp.tipo), bytes.size.toLong(), insp.paginas, hash, chave, "pendente", null, Instant.EPOCH, Instant.EPOCH)
        try {
            tx.executeWithoutResult {
                // Reenvio após falha (UC02 A4): remove o registro anterior com a mesma rotina da exclusão de material (regra 6).
                val antigos = dados.materiaisPorHashFalhos(hash).mapNotNull { dados.excluirMaterial(it.id) }
                dados.inserirMaterial(id, uid, linha)
                if (antigos.isNotEmpty()) remocao.depoisDoCommit(chaves = antigos)
            }
        } catch (e: DataIntegrityViolationException) {  // corrida: dois envios do mesmo arquivo ao mesmo tempo
            runCatching { armazenamento.remover(chave) }
            throw ErroNegocio(HttpStatus.CONFLICT, "Você já enviou este arquivo.", "material_repetido")
        } catch (e: RuntimeException) {
            runCatching { armazenamento.remover(chave) }
            throw e
        }
        auditoria.registrar("material_enviado", uid, "material=$id")
        try {
            indexador.submeter(Tarefa(uid, id, assunto))
        } catch (e: FilaCheia) {
            tx.executeWithoutResult { dados.definirStatus(id, "falhou", "erro_interno") }
            throw ErroNegocio(HttpStatus.SERVICE_UNAVAILABLE, "Muitos materiais na fila. Tente novamente em instantes.", "fila_cheia")
        }
        return id
    }

    fun listar(): List<MaterialDto> = tx.execute { dados.materiais().map(::dto) }

    fun obter(id: UUID): MaterialDto = tx.execute { dados.material(id)?.let(::dto) }  ?: throw naoEncontrado()

    private fun dto(m: MaterialLinha) = MaterialDto(m.id, m.nomeArquivo, m.status, m.motivoFalha, m.paginas, m.criadoEm)

    /** Barra de progresso (regra 14): indexação vale 0 a 85 e a sugestão de tópicos 85 a 100. Detecta tarefa perdida (reinício) sem varrer outras contas. */
    fun andamento(id: UUID): Andamento {
        var m = tx.execute { dados.material(id) } ?: throw naoEncontrado()
        if (m.status in ABERTOS && id !in indexador.ativos) {
            val pulso = tx.execute { (dados.registros(id).map { it.atualizadoEm } + m.atualizadoEm).max() }
            if (Duration.between(pulso, relogio.instant()) > props.material.pulsoMaximo) {
                tx.executeWithoutResult {
                    dados.definirStatus(id, "falhou", "interrompido")
                    dados.falharRegistrosAbertos(id)
                }
                m = tx.execute { dados.material(id) }!!
            }
        }
        val regs = tx.execute { dados.registros(id) }
        val idx = regs.lastOrNull { it.operacao == "indexacao_material" }
        val sug = regs.lastOrNull { it.operacao == "sugestao_topicos" }
        val etapa = when {
            m.status == "falhou" -> "falhou"
            m.status == "pronto" -> "concluido"
            idx == null -> "na_fila"
            idx.progresso < 30 -> "extracao"
            idx.status != "concluida" -> "embeddings"
            else -> "topicos"
        }
        val pct = when {
            m.status == "pronto" -> 100
            idx == null -> 0
            idx.status != "concluida" -> idx.progresso * 85 / 100
            else -> 85 + (sug?.progresso ?: 0) * 15 / 100
        }.let { if (m.status == "pronto") it else minOf(it, 99) }
        return Andamento(m.status, etapa, pct, m.motivoFalha)
    }

    fun trechos(id: UUID, de: Int, ate: Int): List<Dados.TrechoPrevia> {
        if (ate < de) throw ErroNegocio(HttpStatus.BAD_REQUEST, "Intervalo inválido.", "dados_invalidos")
        return tx.execute {
            if (dados.material(id) == null) throw naoEncontrado()
            dados.trechos(id, de, minOf(ate, de + 199), 300)
        }
    }

    fun paginas(id: UUID): List<Int?> = tx.execute {
        if (dados.material(id) == null) throw naoEncontrado()
        dados.paginasDosTrechos(id)
    }

    fun topicosDoMaterial(id: UUID) = tx.execute { topicos.doMaterial(id) }

    companion object {
        val ABERTOS = setOf("pendente", "processando")
        val MIME = mapOf("pdf" to "application/pdf", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "txt" to "text/plain")
    }
}
