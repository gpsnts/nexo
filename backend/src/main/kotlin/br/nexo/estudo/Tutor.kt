package br.nexo.estudo

import br.nexo.NexoProperties
import br.nexo.conta.ErroNegocio
import br.nexo.material.Acumulado
import br.nexo.material.ConteudoBase
import br.nexo.material.FalhaProcessamento
import br.nexo.material.GeracaoDados
import br.nexo.material.Indexador
import br.nexo.material.MensagensIa
import br.nexo.material.PrecosIa
import br.nexo.material.Processamento
import br.nexo.material.TrechoConteudo
import br.nexo.seguranca.UsuarioAtual
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * Dica de uma questão.
 * @property dica texto da dica.
 */
data class DicaDto(val dica: String)
/**
 * Mensagem do chat de um tópico.
 * @property id identificador.
 * @property papel usuario ou assistente.
 * @property conteudo texto.
 * @property criadoEm quando foi gravada.
 */
data class MensagemDto(val id: UUID, val papel: String, val conteudo: String, val criadoEm: Instant)
/**
 * Corpo de `POST /topicos/{id}/chat`.
 * @property mensagem pergunta do estudante.
 */
data class MensagemRequest(val mensagem: String? = null)

/**
 * Dica por questão e chat por tópico. As duas coisas chamam o modelo de linguagem de forma síncrona, fora de transação, e cada
 * chamada deixa o seu registro em `registro_geracao`, inclusive quando falha, para entrar nos limites de operações e de gasto.
 */
@Service
class Tutor(
    private val props: NexoProperties,
    private val dados: TutorDados,
    private val gDados: GeracaoDados,
    private val conteudo: ConteudoBase,
    private val proc: Processamento,
    private val precos: PrecosIa,
    private val tx: TransactionTemplate,
    private val json: JsonMapper,
    private val ops: OperacoesIa,
) {
    /**
     * Erro padrão de recurso inexistente.
     * @param m mensagem para o usuário.
     * @return erro 404 `nao_encontrado`.
     */
    private fun naoEncontrado(m: String) = ErroNegocio(HttpStatus.NOT_FOUND, m, "nao_encontrado")
    /**
     * Erro de IA indisponível.
     * @param codigo código da falha do serviço de IA.
     * @return erro 503 `ia_indisponivel` com mensagem clara.
     */
    private fun indisponivel(codigo: String?) = ErroNegocio(HttpStatus.SERVICE_UNAVAILABLE, MensagensIa.deCodigo(codigo), "ia_indisponivel")
    /**
     * Nome do modelo de linguagem em uso.
     * @return nome do modelo informado pelo serviço de IA.
     * @throws ErroNegocio 503 se o serviço de IA estiver indisponível.
     */
    private fun modelo() = try { proc.modelos().first } catch (e: FalhaProcessamento) { throw indisponivel(e.codigo) }

    // ---------------------------------------------------------------- dica
    /**
     * Dica leve para uma questão, sem revelar a resposta. Já gerada: devolve a gravada, sem chamar o modelo e sem registro; senão chama o modelo, grava e registra a operação.
     * @param questaoId questão.
     * @return a dica.
     * @throws ErroNegocio 404 se a questão não existir; 503 se a IA falhar; limites de uso.
     */
    fun dica(questaoId: UUID): DicaDto {
        val q = tx.execute { dados.questao(questaoId) ?: throw naoEncontrado("Questão não encontrada.") }!!
        q.dica?.let { return DicaDto(it) }  // já gerada: sem chamada ao modelo e sem registro
        val m = modelo()
        /**
         * Contexto da dica lido do banco.
         * @property registro registro de operação aberto.
         * @property trechos trechos que sustentam a questão.
         * @property livre se a questão é de tópico livre.
         */
        class Aberta(val registro: UUID, val trechos: List<String>, val livre: Boolean)
        val aberta = tx.execute {
            val livre = gDados.fontes(q.topicoId).isEmpty()
            // Sem trechos citados mas com material no tópico (ex.: o material citado foi excluído): usa os trechos do tópico, dentro do orçamento de contexto, em vez de tratar como livre.
            val trechos = dados.trechosDaQuestao(questaoId).ifEmpty { if (livre) emptyList() else trechosDoTopicoNoOrcamento(q.topicoId) }
            Aberta(ops.abrir("dica", m, q.topicoId, null, livre), trechos, livre)  // confere os limites e reserva a vaga antes de chamar o modelo
        }!!
        val a = Acumulado()
        return ops.chamar(aberta.registro, m, a, "dica") {
            val alternativas = json.readTree(q.alternativas).toList().map { it.asString() }
            val topico = tx.execute { gDados.topico(q.topicoId) }!!
            val r = proc.darDica(topico.nome, aberta.trechos.mapIndexed { i, t -> TrechoConteudo(i + 1, t) }, q.enunciado, alternativas, q.respostaCorreta)
            a.tokensEntrada = r.tokensEntrada; a.tokensSaida = r.tokensSaida; a.custo = precos.custoLinguagem(r.modelo, r.tokensEntrada, r.tokensSaida)
            if (r.texto.isBlank()) throw FalhaProcessamento("falha_ia", "Dica vazia")
            tx.execute {
                dados.gravarDica(questaoId, r.texto.trim())
                ops.concluir(aberta.registro, r.modelo, a)
                DicaDto(dados.dicaDe(questaoId)!!)  // se outra chamada gravou antes, vale a dela
            }!!
        }
    }

    /**
     * Trechos das faixas do tópico, em ordem, até o orçamento de contexto (sempre ao menos o primeiro).
     * @param topicoId tópico com material.
     * @return o texto dos trechos escolhidos.
     */
    private fun trechosDoTopicoNoOrcamento(topicoId: UUID): List<String> {
        var soma = 0
        return gDados.trechosDasFaixas(topicoId).map { it.conteudo }.takeWhile { t -> (soma + t.length <= props.geracao.contextoMaxCaracteres || soma == 0).also { if (it) soma += t.length } }
    }

    // ---------------------------------------------------------------- chat
    /**
     * Histórico do chat de um tópico, da mensagem mais antiga para a mais nova.
     * @param topicoId tópico.
     * @return as mensagens guardadas.
     * @throws ErroNegocio 404 se o tópico não existir.
     */
    fun historico(topicoId: UUID): List<MensagemDto> = tx.execute {
        gDados.topico(topicoId) ?: throw naoEncontrado("Tópico não encontrado.")
        dados.mensagens(topicoId, props.chat.mensagensPorTopico).map { MensagemDto(it.id, it.papel, it.conteudo, it.criadoEm) }
    }!!

    /**
     * Apaga a conversa de um tópico.
     * @param topicoId tópico.
     * @return nada.
     * @throws ErroNegocio 404 se o tópico não existir.
     */
    fun limpar(topicoId: UUID) = tx.executeWithoutResult {
        gDados.topico(topicoId) ?: throw naoEncontrado("Tópico não encontrado.")
        dados.limparChat(topicoId)
    }

    /**
     * Responde a uma pergunta com base no material do tópico e no histórico. Devolve as duas mensagens gravadas: a pergunta e a resposta. Nada é gravado se o modelo falhar (a tela mantém o texto para tentar de novo).
     * @param topicoId tópico.
     * @param mensagem pergunta do estudante.
     * @return a pergunta e a resposta gravadas.
     * @throws ErroNegocio 400 se a mensagem for vazia ou longa demais; 404 se o tópico não existir; 409 no limite de mensagens; 503 se a IA falhar; limites de uso.
     */
    fun conversar(topicoId: UUID, mensagem: String?): List<MensagemDto> {
        val texto = mensagem?.trim().orEmpty()
        if (texto.isEmpty() || texto.length > props.chat.mensagemMaxCaracteres)
            throw ErroNegocio(HttpStatus.BAD_REQUEST, "Escreva uma pergunta de até ${props.chat.mensagemMaxCaracteres} caracteres.", "dados_invalidos")
        /**
         * Contexto do chat lido do banco.
         * @property nome nome do tópico.
         * @property livre se é tópico livre.
         * @property historico falas anteriores (papel, texto).
         * @property buscar se é preciso busca vetorial para escolher os trechos.
         * @property orientacao orientação gravada no tópico, se houver.
         */
        class Ctx(val nome: String, val livre: Boolean, val historico: List<Pair<String, String>>, val buscar: Boolean, val orientacao: String?)
        val c = tx.execute {
            val t = gDados.topico(topicoId) ?: throw naoEncontrado("Tópico não encontrado.")
            if (dados.totalMensagens(topicoId) + 2 > props.chat.mensagensPorTopico)
                throw ErroNegocio(HttpStatus.CONFLICT, "A conversa deste tópico chegou ao limite de ${props.chat.mensagensPorTopico} mensagens. Limpe a conversa para continuar.", "limite_chat_topico")
            // cada fala vai cortada ao tamanho que o serviço de IA aceita (a resposta longa de um modelo não pode travar o chat)
            val ant = dados.mensagens(topicoId, props.chat.historicoEnviado).map { it.papel to it.conteudo.take(props.chat.falaEnviadaMaxCaracteres) }
            Ctx(t.nome, gDados.fontes(topicoId).isEmpty(), ant, conteudo.precisaDeBusca(topicoId), t.orientacao)
        }!!
        val m = modelo()
        val registro = tx.execute { ops.abrir("chat", m, topicoId, null, c.livre) }!!  // confere os limites e reserva a vaga antes de chamar o modelo
        val a = Acumulado()
        val uid = UsuarioAtual.id()!!
        return ops.chamar(registro, m, a, "chat") {
            val vetor = if (c.buscar) proc.embeddings(listOf(texto)).also {
                if (it.vetores.size != 1 || it.vetores[0].size != Indexador.DIMENSAO) throw FalhaProcessamento("falha_ia", "Embedding fora do esperado")
                a.tokensEntrada += it.tokens; a.custo = a.custo.add(conteudo.custoEmbedding(it.modelo, it.tokens))
            } else null
            val base = tx.execute { conteudo.obter(gDados.topico(topicoId) ?: throw naoEncontrado("Tópico não encontrado."), vetor) }!!
            val r = proc.conversar(c.nome, base.paraModelo(), c.historico, texto, c.orientacao)
            a.tokensEntrada += r.tokensEntrada; a.tokensSaida += r.tokensSaida; a.custo = a.custo.add(precos.custoLinguagem(r.modelo, r.tokensEntrada, r.tokensSaida))
            if (r.texto.isBlank()) throw FalhaProcessamento("falha_ia", "Resposta vazia")
            tx.execute {
                dados.inserirMensagem(UUID.randomUUID(), uid, topicoId, "usuario", texto)
                dados.inserirMensagem(UUID.randomUUID(), uid, topicoId, "assistente", r.texto.trim().take(props.chat.respostaMaxCaracteres))
                ops.concluir(registro, r.modelo, a)
                dados.mensagens(topicoId, 2).map { MensagemDto(it.id, it.papel, it.conteudo, it.criadoEm) }
            }!!
        }
    }
}

/** Rotas da dica e do chat. */
@RestController
class TutorController(private val tutor: Tutor) {
    /**
     * Pede a dica de uma questão.
     * @param id questão.
     * @return a dica.
     */
    @PostMapping("/questoes/{id}/dica")
    fun dica(@PathVariable id: UUID) = tutor.dica(id)

    /**
     * Histórico do chat.
     * @param id tópico.
     * @return as mensagens.
     */
    @GetMapping("/topicos/{id}/chat")
    fun historico(@PathVariable id: UUID) = tutor.historico(id)

    /**
     * Envia uma pergunta ao tutor.
     * @param id tópico.
     * @param r mensagem.
     * @return a pergunta e a resposta gravadas.
     */
    @PostMapping("/topicos/{id}/chat")
    fun conversar(@PathVariable id: UUID, @RequestBody(required = false) r: MensagemRequest?) = tutor.conversar(id, r?.mensagem)

    /**
     * Limpa a conversa.
     * @param id tópico.
     * @return 204.
     */
    @DeleteMapping("/topicos/{id}/chat")
    fun limpar(@PathVariable id: UUID) = tutor.limpar(id).let { org.springframework.http.ResponseEntity.noContent().build<Void>() }
}
