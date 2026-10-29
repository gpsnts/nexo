package br.nexo.estudo

import br.nexo.conta.ErroNegocio
import br.nexo.material.Acumulado
import br.nexo.material.Dados
import br.nexo.material.FalhaProcessamento
import br.nexo.material.MaterialDto
import br.nexo.material.MaterialService
import br.nexo.material.MensagensIa
import br.nexo.material.PrecosIa
import br.nexo.material.Processamento
import br.nexo.seguranca.UsuarioAtual
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Corpo de `POST /assuntos` e `PUT /assuntos/{id}`.
 * @property nome nome da pasta (até 60 caracteres).
 */
data class PastaRequest(@field:NotBlank(message = "Informe o nome da pasta.") @field:Size(max = 60, message = "O nome da pasta pode ter até 60 caracteres.") val nome: String)
/**
 * Corpo das rotas de mover estudo.
 * @property assuntoId pasta de destino; nulo = "Geral".
 */
data class MoverRequest(val assuntoId: UUID?)
/**
 * Corpo de `PUT /materiais/{id}`.
 * @property titulo nome do estudo (até 120 caracteres); em branco volta ao nome do arquivo.
 */
data class TituloRequest(@field:Size(max = 120, message = "O nome do estudo pode ter até 120 caracteres.") val titulo: String? = null)
/**
 * Pasta devolvida pela API.
 * @property id identificador.
 * @property nome nome da pasta.
 * @property padrao se é a pasta "Geral".
 */
data class PastaDto(val id: UUID, val nome: String, val padrao: Boolean)
/**
 * Nome sugerido pela IA para o estudo.
 * @property nome sugestão (até 120 caracteres).
 */
data class NomeSugeridoDto(val nome: String)

/**
 * Pastas (os "assuntos" do projeto) e nome do estudo. A pasta de um estudo é a dos seus tópicos: mover um material move todos os tópicos dele
 * (a revisão de tópicos exige que fiquem juntos); o tópico livre se move sozinho. "Geral" é a pasta padrão: não se renomeia nem se exclui.
 */
@Service
class Organizacao(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val materiais: MaterialService,
    private val dados: Dados,
    private val proc: Processamento,
    private val precos: PrecosIa,
    private val ops: OperacoesIa,
) {
    /**
     * Usuário autenticado.
     * @return id do usuário do JWT.
     * @throws ErroNegocio 401 se não houver usuário.
     */
    private fun uid() = UsuarioAtual.id() ?: throw ErroNegocio(HttpStatus.UNAUTHORIZED, "Não autenticado.", "nao_autenticado")
    /**
     * Erro padrão de pasta inexistente.
     * @return erro 404 `assunto_nao_encontrado`.
     */
    private fun pastaNaoEncontrada() = ErroNegocio(HttpStatus.NOT_FOUND, "Pasta não encontrada.", "assunto_nao_encontrado")
    /**
     * Erro padrão de nome de pasta repetido.
     * @return erro 409 `assunto_duplicado`.
     */
    private fun duplicada() = ErroNegocio(HttpStatus.CONFLICT, "Já existe uma pasta com esse nome. Use a que já existe.", "assunto_duplicado")

    /**
     * Pasta lida do banco.
     * @property id identificador.
     * @property padrao se é a pasta "Geral".
     */
    private class Pasta(val id: UUID, val padrao: Boolean)
    /**
     * Lê e trava a pasta até o fim da transação.
     * @param id pasta.
     * @return a pasta.
     * @throws ErroNegocio 404 se não for do usuário.
     */
    private fun pasta(id: UUID): Pasta = jdbc.query("SELECT id, assunto_padrao FROM assunto WHERE id = ? FOR UPDATE", { rs, _ -> Pasta(rs.getObject(1, UUID::class.java), rs.getBoolean(2)) }, id).firstOrNull() ?: throw pastaNaoEncontrada()
    /**
     * Pasta padrão do usuário.
     * @return id da pasta "Geral".
     */
    private fun padrao(): UUID = jdbc.queryForObject("SELECT id FROM assunto WHERE assunto_padrao", UUID::class.java)!!

    // ------------------------------------------------------------ pastas
    /**
     * Cria uma pasta.
     * @param nome nome da pasta (sem espaços nas pontas).
     * @return a pasta criada.
     * @throws ErroNegocio 409 se o nome já existir.
     */
    fun criar(nome: String): PastaDto = tx.execute {
        val id = UUID.randomUUID()
        try { jdbc.update("INSERT INTO assunto(id,usuario_id,nome,assunto_padrao) VALUES (?,?,?,false)", id, uid(), nome.trim()) } catch (e: DataIntegrityViolationException) { throw duplicada() }
        PastaDto(id, nome.trim(), false)
    }!!

    /**
     * Renomeia uma pasta.
     * @param id pasta.
     * @param nome novo nome.
     * @return a pasta renomeada.
     * @throws ErroNegocio 404 se não existir; 409 se for a padrão ou o nome já existir.
     */
    fun renomear(id: UUID, nome: String): PastaDto = tx.execute {
        val p = pasta(id)
        if (p.padrao) throw ErroNegocio(HttpStatus.CONFLICT, "A pasta padrão \"Geral\" não pode ser renomeada.", "assunto_padrao")
        try { jdbc.update("UPDATE assunto SET nome = ? WHERE id = ?", nome.trim(), id) } catch (e: DataIntegrityViolationException) { throw duplicada() }
        PastaDto(id, nome.trim(), false)
    }!!

    /**
     * Exclui uma pasta; os estudos dela não se perdem: voltam para "Geral", ao final dela e na mesma ordem.
     * @param id pasta.
     * @return nada.
     * @throws ErroNegocio 409 se for a padrão ou estiver em uso por uma prova; 404 se não existir.
     */
    fun excluir(id: UUID) = tx.executeWithoutResult {
        val p = pasta(id)
        if (p.padrao) throw ErroNegocio(HttpStatus.CONFLICT, "A pasta padrão \"Geral\" não pode ser excluída.", "assunto_padrao")
        val geral = pasta(padrao()).id
        anexar(jdbc.queryForList("SELECT id FROM topico WHERE assunto_id = ? ORDER BY ordem", UUID::class.java, id).filterNotNull(), geral)
        try { jdbc.update("DELETE FROM assunto WHERE id = ?", id) } catch (e: DataIntegrityViolationException) {
            throw ErroNegocio(HttpStatus.CONFLICT, "Esta pasta está em uso por uma prova e não pode ser excluída.", "assunto_em_uso")
        }
    }

    /**
     * Põe os tópicos ao final da pasta de destino (já travada), na ordem dada.
     * @param topicos tópicos a mover.
     * @param destino pasta de destino.
     */
    private fun anexar(topicos: List<UUID>, destino: UUID) {
        var ordem = jdbc.queryForObject("SELECT coalesce(max(ordem),0) FROM topico WHERE assunto_id = ?", Int::class.java, destino)!!
        topicos.forEach { jdbc.update("UPDATE topico SET assunto_id = ?, ordem = ? WHERE id = ?", destino, ++ordem, it) }
    }

    /**
     * Move um material (todos os seus tópicos) para uma pasta.
     * @param materialId material.
     * @param assuntoId pasta de destino; nulo = "Geral".
     * @return a pasta de destino.
     * @throws ErroNegocio 404 se o material não existir; 409 se ainda não tiver tópicos.
     */
    fun moverMaterial(materialId: UUID, assuntoId: UUID?): PastaDto = tx.execute {
        dados.material(materialId) ?: throw ErroNegocio(HttpStatus.NOT_FOUND, "Material não encontrado.", "nao_encontrado")
        val destino = pasta(assuntoId ?: padrao())
        val topicos = jdbc.query(
            "SELECT DISTINCT t.id, t.ordem, t.assunto_id FROM topico t JOIN topico_fonte f ON f.topico_id = t.id WHERE f.material_id = ? ORDER BY t.ordem",
            { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getObject(3, UUID::class.java)) }, materialId,
        )
        if (topicos.isEmpty()) throw ErroNegocio(HttpStatus.CONFLICT, "O material ainda não tem tópicos. Espere o processamento terminar.", "material_nao_pronto")
        val fora = topicos.filter { it.third != destino.id }
        if (fora.isNotEmpty()) anexar(fora.map { it.first }, destino.id)
        nomeDe(destino.id)
    }!!

    /**
     * Move um tópico livre para uma pasta.
     * @param topicoId tópico livre.
     * @param assuntoId pasta de destino; nulo = "Geral".
     * @return a pasta de destino.
     * @throws ErroNegocio 404 se o tópico não existir; 409 se for tópico de material.
     */
    fun moverTopicoLivre(topicoId: UUID, assuntoId: UUID?): PastaDto = tx.execute {
        jdbc.query("SELECT assunto_id FROM topico WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, topicoId).firstOrNull()
            ?: throw ErroNegocio(HttpStatus.NOT_FOUND, "Tópico não encontrado.", "nao_encontrado")
        if (jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM topico_fonte WHERE topico_id = ?)", Boolean::class.java, topicoId)!!)
            throw ErroNegocio(HttpStatus.CONFLICT, "Tópico de material muda de pasta junto com o material.", "topico_de_material")
        val destino = pasta(assuntoId ?: padrao())
        anexar(listOf(topicoId), destino.id)
        nomeDe(destino.id)
    }!!

    /**
     * Lê a pasta pelo id.
     * @param id pasta.
     * @return id, nome e se é a padrão.
     */
    private fun nomeDe(id: UUID) = PastaDto(id, jdbc.queryForObject("SELECT nome FROM assunto WHERE id = ?", String::class.java, id)!!, jdbc.queryForObject("SELECT assunto_padrao FROM assunto WHERE id = ?", Boolean::class.java, id)!!)

    // ------------------------------------------------------------ nome do estudo
    /**
     * Renomeia o estudo. Em branco volta a valer o nome do arquivo.
     * @param materialId material.
     * @param titulo novo nome, ou nulo/em branco para limpar.
     * @return o material atualizado.
     * @throws ErroNegocio 404 se o material não existir.
     */
    fun renomearEstudo(materialId: UUID, titulo: String?): MaterialDto {
        tx.executeWithoutResult {
            dados.material(materialId) ?: throw ErroNegocio(HttpStatus.NOT_FOUND, "Material não encontrado.", "nao_encontrado")
            jdbc.update("UPDATE material SET titulo = ? WHERE id = ?", titulo?.trim()?.ifEmpty { null }, materialId)
        }
        return materiais.obter(materialId)
    }

    /**
     * Sugestão da IA a partir dos tópicos e dos primeiros trechos; não grava o nome (o estudante aceita ou edita). Conta como operação com IA.
     * @param materialId material pronto.
     * @return o nome sugerido.
     * @throws ErroNegocio 404 se não existir; 409 se não estiver pronto; 503 se a IA estiver indisponível; limites de uso.
     */
    fun sugerirNome(materialId: UUID): NomeSugeridoDto {
        /**
         * Contexto lido do banco para o prompt.
         * @property topicos nomes dos tópicos.
         * @property trechos primeiros trechos.
         */
        class Ctx(val topicos: List<String>, val trechos: List<String>)
        val c = tx.execute {
            val m = dados.material(materialId) ?: throw ErroNegocio(HttpStatus.NOT_FOUND, "Material não encontrado.", "nao_encontrado")
            if (m.status != "pronto") throw ErroNegocio(HttpStatus.CONFLICT, "O material ainda não está pronto.", "material_nao_pronto")
            Ctx(
                jdbc.queryForList("SELECT nome FROM (SELECT DISTINCT t.nome, t.ordem FROM topico t JOIN topico_fonte f ON f.topico_id = t.id WHERE f.material_id = ?) x ORDER BY ordem LIMIT 40", String::class.java, materialId).filterNotNull(),
                jdbc.queryForList("SELECT left(conteudo, 600) FROM trecho WHERE material_id = ? ORDER BY ordem LIMIT 3", String::class.java, materialId).filterNotNull(),
            )
        }!!
        val modelo = try { proc.modelos().first } catch (e: FalhaProcessamento) { throw ErroNegocio(HttpStatus.SERVICE_UNAVAILABLE, MensagensIa.deCodigo(e.codigo), "ia_indisponivel") }
        val registro = tx.execute { ops.abrir("nome_estudo", modelo, null, materialId, false) }!!  // confere os limites e reserva a vaga antes de chamar o modelo
        val a = Acumulado()
        return ops.chamar(registro, modelo, a, "nome_estudo") {
            val r = proc.sugerirNome(c.topicos, c.trechos)
            a.tokensEntrada = r.tokensEntrada; a.tokensSaida = r.tokensSaida; a.custo = precos.custoLinguagem(r.modelo, r.tokensEntrada, r.tokensSaida)
            if (r.texto.isBlank()) throw FalhaProcessamento("falha_ia", "Nome vazio")
            tx.executeWithoutResult { ops.concluir(registro, r.modelo, a) }
            NomeSugeridoDto(r.texto.trim().take(120))
        }
    }
}

/** Rotas de pastas e do nome do estudo. */
@RestController
class OrganizacaoController(private val org: Organizacao) {
    /**
     * Cria uma pasta.
     * @param r nome da pasta.
     * @return 201 com a pasta; 409 se o nome já existir.
     */
    @PostMapping("/assuntos")
    fun criar(@Valid @RequestBody r: PastaRequest) = ResponseEntity.status(HttpStatus.CREATED).body(org.criar(r.nome))

    /**
     * Renomeia uma pasta.
     * @param id pasta.
     * @param r novo nome.
     * @return a pasta renomeada.
     */
    @PutMapping("/assuntos/{id}")
    fun renomear(@PathVariable id: UUID, @Valid @RequestBody r: PastaRequest) = org.renomear(id, r.nome)

    /**
     * Exclui uma pasta (os estudos voltam para "Geral").
     * @param id pasta.
     * @return 204.
     */
    @DeleteMapping("/assuntos/{id}")
    fun excluir(@PathVariable id: UUID): ResponseEntity<Void> { org.excluir(id); return ResponseEntity.noContent().build() }

    /**
     * Move um material para uma pasta.
     * @param id material.
     * @param r pasta de destino.
     * @return a pasta de destino.
     */
    @PutMapping("/materiais/{id}/assunto")
    fun moverMaterial(@PathVariable id: UUID, @RequestBody r: MoverRequest) = org.moverMaterial(id, r.assuntoId)

    /**
     * Move um tópico livre para uma pasta.
     * @param id tópico.
     * @param r pasta de destino.
     * @return a pasta de destino.
     */
    @PutMapping("/topicos/{id}/assunto")
    fun moverTopico(@PathVariable id: UUID, @RequestBody r: MoverRequest) = org.moverTopicoLivre(id, r.assuntoId)

    /**
     * Renomeia o estudo.
     * @param id material.
     * @param r novo nome.
     * @return o material atualizado.
     */
    @PutMapping("/materiais/{id}")
    fun renomearEstudo(@PathVariable id: UUID, @Valid @RequestBody r: TituloRequest) = org.renomearEstudo(id, r.titulo)

    /**
     * Pede à IA um nome para o estudo.
     * @param id material.
     * @return o nome sugerido (não gravado).
     */
    @PostMapping("/materiais/{id}/sugerir-nome")
    fun sugerirNome(@PathVariable id: UUID) = org.sugerirNome(id)
}
