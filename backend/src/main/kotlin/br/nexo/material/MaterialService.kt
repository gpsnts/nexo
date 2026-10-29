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

/** `titulo`: nome dado ao estudo (nulo = vale o nome do arquivo); `assuntoId`: a pasta (nulo enquanto o material não tem tópicos). */
data class MaterialDto(val id: UUID, val nomeArquivo: String, val status: String, val motivoFalha: String?, val paginas: Int?, val criadoEm: Instant, val gerando: Boolean = false, val titulo: String? = null, val assuntoId: UUID? = null)
/**
 * Andamento da indexação, para a barra de progresso.
 * @property status status do material.
 * @property etapa etapa corrente (na_fila, extracao, embeddings, topicos, concluido, falhou).
 * @property percentual 0 a 100 (100 só quando pronto).
 * @property motivoFalha motivo, se falhou.
 */
data class Andamento(val status: String, val etapa: String, val percentual: Int, val motivoFalha: String?)

/** Materiais: envio com validações, listagem, andamento da indexação, trechos e tópicos. */
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
    private val geracaoDados: GeracaoDados,
    private val relogio: Clock,
    private val limites: LimitesIa,
) {
    /**
     * Usuário autenticado.
     * @return id do usuário do JWT.
     * @throws ErroNegocio 401 se não houver usuário.
     */
    private fun usuario() = UsuarioAtual.id() ?: throw ErroNegocio(HttpStatus.UNAUTHORIZED, "Não autenticado.", "nao_autenticado")
    /**
     * Erro padrão de material inexistente.
     * @return erro 404 `nao_encontrado`.
     */
    private fun naoEncontrado() = ErroNegocio(HttpStatus.NOT_FOUND, "Material não encontrado.", "nao_encontrado")
    /**
     * Converte bytes em hexadecimal.
     * @param b bytes.
     * @return texto hexadecimal minúsculo.
     */
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /**
     * Valida, grava o arquivo e agenda a indexação em segundo plano.
     * @param nomeOriginal nome do arquivo enviado.
     * @param bytes conteúdo do arquivo.
     * @param assuntoId pasta de destino, ou nulo para o "Geral".
     * @return o id do material (status pendente).
     * @throws ErroNegocio formato, tamanho, páginas, arquivo repetido, processamento em andamento, limites de IA ou serviço indisponível.
     */
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
                // Reenvio após falha: remove o registro anterior com a mesma rotina da exclusão de material.
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

    /**
     * Lista os materiais do usuário, do mais novo ao mais antigo.
     * @param limite máximo de itens.
     * @return os materiais, marcando os que estão gerando conteúdo.
     */
    fun listar(limite: Int): List<MaterialDto> = tx.execute {
        val gerando = geracaoDados.materiaisGerando(props.geracao.pulsoMaximo.seconds)
        dados.materiais(limite).map { dto(it).copy(gerando = it.id in gerando) }
    }!!

    /**
     * Busca um material.
     * @param id material.
     * @return o material.
     * @throws ErroNegocio 404 se não existir.
     */
    fun obter(id: UUID): MaterialDto = tx.execute { dados.material(id)?.let(::dto) }  ?: throw naoEncontrado()

    /**
     * Converte a linha do banco em DTO.
     * @param m linha do material.
     * @return o DTO.
     */
    private fun dto(m: MaterialLinha) = MaterialDto(m.id, m.nomeArquivo, m.status, m.motivoFalha, m.paginas, m.criadoEm, false, m.titulo, m.assuntoId)

    /**
     * Barra de progresso: indexação vale 0 a 85 e a sugestão de tópicos 85 a 100. Detecta tarefa perdida (reinício) sem varrer outras contas.
     * @param id material.
     * @return status, etapa e percentual.
     * @throws ErroNegocio 404 se não existir.
     */
    fun andamento(id: UUID): Andamento {
        var m = tx.execute { dados.material(id) } ?: throw naoEncontrado()
        if (m.status in ABERTOS && id !in indexador.ativos) {
            val pulso = tx.execute { (dados.registros(id).map { it.atualizadoEm } + m.atualizadoEm).max() }
            if (Duration.between(pulso, relogio.instant()) > props.material.pulsoMaximo) {
                tx.executeWithoutResult {
                    dados.definirStatus(id, "falhou", "interrompido")
                    dados.falharRegistrosAbertos(id, "interrompido")
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

    /**
     * Prévia dos trechos de um material.
     * @param id material.
     * @param de primeira ordem (inclusive).
     * @param ate última ordem (inclusive; no máximo 200 por chamada).
     * @return os trechos do intervalo.
     * @throws ErroNegocio 400 se o intervalo for inválido; 404 se o material não existir.
     */
    fun trechos(id: UUID, de: Int, ate: Int): List<Dados.TrechoPrevia> {
        if (ate < de) throw ErroNegocio(HttpStatus.BAD_REQUEST, "Intervalo inválido.", "dados_invalidos")
        return tx.execute {
            if (dados.material(id) == null) throw naoEncontrado()
            dados.trechos(id, de, minOf(ate, de + 199), 300)
        }
    }

    /**
     * Página de cada trecho do material, na ordem.
     * @param id material.
     * @return número da página de cada trecho (nulo em TXT e DOCX).
     * @throws ErroNegocio 404 se não existir.
     */
    fun paginas(id: UUID): List<Int?> = tx.execute {
        if (dados.material(id) == null) throw naoEncontrado()
        dados.paginasDosTrechos(id)
    }

    /**
     * Tópicos de um material.
     * @param id material.
     * @return os tópicos do material.
     */
    fun topicosDoMaterial(id: UUID) = tx.execute { topicos.doMaterial(id) }

    companion object {
        val ABERTOS = setOf("pendente", "processando")
        val MIME = mapOf("pdf" to "application/pdf", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "txt" to "text/plain")
    }
}
