package br.nexo.conta

import br.nexo.NexoProperties
import br.nexo.conta.TokenContaService.Companion.CONFIRMACAO
import br.nexo.conta.TokenContaService.Companion.REDEFINICAO
import br.nexo.seguranca.Auditoria
import br.nexo.seguranca.UsuarioAtual
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Dados do perfil do usuário.
 * @property nome nome de exibição.
 * @property email e-mail de acesso.
 */
data class PerfilDto(val nome: String, val email: String)
/**
 * Sessão ativa na tela "Sessões ativas".
 * @property id família do refresh.
 * @property dispositivo navegador e sistema.
 * @property ip IP do último acesso.
 * @property inicio quando a sessão começou.
 * @property ultimaAtividade última renovação.
 * @property atual se é a sessão de quem pede.
 */
data class SessaoDto(val id: UUID, val dispositivo: String, val ip: String, val inicio: Instant, val ultimaAtividade: Instant, val atual: Boolean)

/** Regras da conta: recuperação de senha, troca de e-mail e de senha, perfil, sessões ativas e exclusão de conta (UC de autenticação). */
@Service
class ContaService(
    private val usuarios: UsuarioRepository,
    private val sessoes: SessaoRepository,
    private val tokens: TokenContaService,
    private val auth: AuthService,
    private val encoder: PasswordEncoder,
    private val email: EnviadorEmail,
    private val segundoPlano: SegundoPlano,
    private val tx: TransactionTemplate,
    private val props: NexoProperties,
    private val relogio: Clock,
    private val auditoria: Auditoria,
    private val arquivos: br.nexo.material.RemocaoArquivos,
) {
    // ---- recuperação de senha (sem login) ----

    /**
     * Sempre "funciona" para quem chama: não revela se o e-mail existe nem se o limite por hora foi atingido.
     * @param emailInformado e-mail digitado (normalizado aqui).
     */
    fun esqueciSenha(emailInformado: String) {
        val u = usuarios.findByEmail(normalizarEmail(emailInformado)) ?: return
        // Em segundo plano para a resposta ter o mesmo custo com e sem conta (e um SMTP lento não travar a requisição).
        segundoPlano.executar {
            if (tokens.pedidosNaUltimaHora(u.id, REDEFINICAO) < props.conta.pedidosPorHora) {
                val bruto = tokens.criar(u.id, REDEFINICAO, null, props.conta.validadeRedefinicao)
                email.enviar(u.email, "Redefinir sua senha no Nexo",
                    "Olá, ${u.nome}.\n\nPara criar uma nova senha, abra o link (vale por ${props.conta.validadeRedefinicao.toMinutes()} minutos e só pode ser usado uma vez):\n" +
                        "${props.conta.urlFrontend}/redefinir-senha?token=$bruto\n\nSe não foi você, ignore este e-mail.")
            }
        }
    }

    /**
     * Define a nova senha a partir do link de redefinição; revoga todas as sessões e confirma o e-mail.
     * @param bruto token bruto do link.
     * @param novaSenha nova senha, validada pela [PoliticaSenha].
     * @throws ErroNegocio 400 se o link for inválido ou a senha for fraca (o link continua valendo neste caso).
     */
    fun redefinirSenha(bruto: String, novaSenha: String) {
        val hash = encoder.encode(novaSenha)!!
        val u = tx.execute {
            val t = tokens.validar(bruto, REDEFINICAO)
            val u = usuarios.findById(t.usuarioId).orElseThrow { TokenContaService.linkInvalido() }
            PoliticaSenha.exigir(novaSenha, u.email) // falhar aqui desfaz a transação: o link continua valendo
            u.senhaHash = hash
            u.falhasLogin = 0
            u.bloqueadoAte = null
            // Abrir o link enviado ao e-mail prova que a pessoa controla o endereço: confirma o e-mail (e libera quem foi vítima de cadastro alheio).
            if (u.emailVerificadoEm == null) u.emailVerificadoEm = relogio.instant()
            t.usadoEm = relogio.instant()
            auth.revogarTodas(u.id)
            u
        }
        auditoria.registrar("senha_redefinida", u.id)
        avisarSenhaAlterada(u)
    }

    /**
     * Confirma a troca de e-mail pelo link enviado ao novo endereço e avisa o endereço antigo.
     * @param bruto token bruto do link.
     * @throws ErroNegocio 400 se o link for inválido; 409 se o e-mail já estiver em uso.
     */
    fun confirmarEmail(bruto: String) {
        val (antigo, nome, novo) = try {
            tx.execute {
                val t = tokens.validar(bruto, CONFIRMACAO)
                val novo = t.novoEmail!!
                if (usuarios.findByEmail(novo) != null) throw emailEmUso()
                val u = usuarios.findById(t.usuarioId).orElseThrow { TokenContaService.linkInvalido() }
                val antigo = u.email
                u.email = novo
                u.emailVerificadoEm = relogio.instant()
                t.usadoEm = relogio.instant()
                auditoria.registrar("email_alterado", u.id)
                Triple(antigo, u.nome, novo)
            }
        } catch (e: DataIntegrityViolationException) {
            throw emailEmUso()
        }
        // Aviso ao endereço ANTIGO: se não foi o dono, é a chance de perceber.
        enviar(antigo, "O e-mail da sua conta Nexo foi alterado",
            "Olá, $nome.\n\nO e-mail de acesso da sua conta foi alterado para ${mascarar(novo)}.\n\n" +
                "Se não foi você, recupere o acesso agora em ${props.conta.urlFrontend} (\"Esqueci minha senha\") e troque a senha.")
    }

    // ---- perfil (autenticado; tudo sai do JWT, nenhuma rota recebe id de usuário) ----

    /**
     * Perfil do usuário autenticado.
     * @return nome e e-mail.
     */
    fun perfil() = atual().let { PerfilDto(it.nome, it.email) }

    /**
     * Altera o nome do usuário autenticado.
     * @param nome novo nome (sem espaços nas pontas).
     * @return o perfil atualizado.
     */
    fun alterarNome(nome: String) = tx.execute { usuarios.findById(atual().id).get().also { it.nome = nome.trim() } }.let { PerfilDto(it.nome, it.email) }

    /**
     * Pede a troca de e-mail: envia o link ao novo endereço; o e-mail de acesso só muda depois da confirmação.
     * @param senhaAtual senha atual, para reautenticar.
     * @param novoEmail novo e-mail.
     * @throws ErroNegocio 403 senha incorreta; 400 e-mail igual ao atual; 409 e-mail em uso.
     */
    fun solicitarTrocaEmail(senhaAtual: String, novoEmail: String) {
        val u = atual()
        exigirSenha(u, senhaAtual)
        val novo = normalizarEmail(novoEmail)
        if (novo == u.email) throw ErroNegocio(HttpStatus.BAD_REQUEST, "O novo e-mail é igual ao atual.", "email_igual")
        if (usuarios.findByEmail(novo) != null) throw emailEmUso()
        val bruto = tx.execute {
            tokens.invalidarPendentes(u.id, CONFIRMACAO)
            tokens.criar(u.id, CONFIRMACAO, novo, props.conta.validadeConfirmacaoEmail)
        }
        enviar(novo, "Confirme seu novo e-mail no Nexo",
            "Olá, ${u.nome}.\n\nPara confirmar que este é o seu novo e-mail, abra o link (vale por ${props.conta.validadeConfirmacaoEmail.toHours()} horas):\n" +
                "${props.conta.urlFrontend}/confirmar-email?token=$bruto\n\nO e-mail de acesso só muda depois da confirmação.")
    }

    /**
     * Troca a senha, derruba as outras sessões e devolve uma sessão nova para quem trocou.
     * @param senhaAtual senha atual, para reautenticar.
     * @param novaSenha nova senha, validada pela política.
     * @param origem dispositivo e IP da nova sessão.
     * @return JWT de acesso e refresh da nova sessão.
     * @throws ErroNegocio 403 senha atual incorreta; 400 senha fraca.
     */
    fun alterarSenha(senhaAtual: String, novaSenha: String, origem: Origem): Autenticacao {
        val u = atual()
        exigirSenha(u, senhaAtual)
        PoliticaSenha.exigir(novaSenha, u.email)
        val hash = encoder.encode(novaSenha)!!
        tx.executeWithoutResult {
            usuarios.findById(u.id).get().senhaHash = hash
            auth.revogarTodas(u.id)
        }
        auditoria.registrar("senha_alterada", u.id)
        avisarSenhaAlterada(u)
        return auth.abrirSessao(u, origem)
    }

    /**
     * DELETE FROM usuario remove tudo em cascata; os arquivos saem depois do commit.
     * @param senha senha atual, para reautenticar.
     * @param confirmacao deve ser a palavra EXCLUIR.
     * @throws ErroNegocio 400 se a confirmação não bater; 403 se a senha estiver errada.
     */
    fun excluirConta(senha: String, confirmacao: String) {
        if (confirmacao != "EXCLUIR") throw ErroNegocio(HttpStatus.BAD_REQUEST, "Digite EXCLUIR para confirmar.", "confirmacao_invalida")
        val u = atual()
        exigirSenha(u, senha)
        tx.executeWithoutResult {
            usuarios.deleteById(u.id)
            arquivos.depoisDoCommit(pastas = listOf(u.id.toString()))  // os arquivos da conta saem só depois do commit
        }
        auditoria.registrar("conta_excluida", u.id)
    }

    // ---- sessões ativas ----

    /**
     * Lista as sessões ativas do usuário, marcando a atual.
     * @return sessões não revogadas e não expiradas.
     */
    fun sessoesAtivas(): List<SessaoDto> {
        val atualId = UsuarioAtual.sessaoId()
        return sessoes.ativas(atual().id, relogio.instant()).map {
            SessaoDto(it.familiaId, it.dispositivo ?: "Dispositivo desconhecido", it.ip ?: "", it.inicioEm, it.criadoEm ?: it.inicioEm, it.familiaId == atualId)
        }
    }

    /**
     * Encerra uma sessão do próprio usuário (inclusive a atual). Id de outra pessoa ou inexistente: 404, sem distinguir.
     * @param familiaId família da sessão.
     * @throws ErroNegocio 404 se não for uma sessão do usuário.
     */
    fun encerrarSessao(familiaId: UUID) {
        if (sessoes.revogarFamiliaDoUsuario(atual().id, familiaId, relogio.instant()) == 0)
            throw ErroNegocio(HttpStatus.NOT_FOUND, "Sessão não encontrada.", "nao_encontrado")
        auditoria.registrar("sessao_encerrada", UsuarioAtual.id(), "familia=$familiaId")
    }

    /**
     * "Sair de todos os outros dispositivos".
     * @return quantidade de sessões encerradas.
     */
    fun encerrarOutrasSessoes(): Int {
        val manter = UsuarioAtual.sessaoId() ?: throw ErroNegocio(HttpStatus.UNAUTHORIZED, "Sessão inválida.", "sessao_invalida")
        val n = sessoes.revogarOutras(atual().id, manter, relogio.instant())
        auditoria.registrar("sessoes_encerradas", UsuarioAtual.id(), "quantidade=$n")
        return n
    }

    // ---- apoio ----

    /**
     * Usuário autenticado.
     * @return a conta do JWT corrente.
     * @throws ErroNegocio 401 se não houver usuário válido.
     */
    private fun atual(): Usuario =
        UsuarioAtual.id()?.let { usuarios.findById(it).orElse(null) } ?: throw ErroNegocio(HttpStatus.UNAUTHORIZED, "Sessão inválida.", "sessao_invalida")

    // Reautenticação passa pelo mesmo bloqueio do login: um token de acesso roubado não serve para adivinhar a senha.
    /**
     * Reautenticação pelo mesmo bloqueio do login: um token de acesso roubado não serve para adivinhar a senha.
     * @param u conta.
     * @param senha senha informada.
     * @throws ErroNegocio 403 se a senha estiver errada.
     */
    private fun exigirSenha(u: Usuario, senha: String) {
        if (!auth.conferirSenha(u, senha)) throw ErroNegocio(HttpStatus.FORBIDDEN, "Senha atual incorreta.", "senha_incorreta")
    }

    /**
     * Envia um e-mail em segundo plano.
     * @param para destinatário.
     * @param assunto assunto.
     * @param texto corpo.
     * @return nada; a tarefa roda em segundo plano.
     */
    private fun enviar(para: String, assunto: String, texto: String) = segundoPlano.executar { email.enviar(para, assunto, texto) }

    /**
     * Avisa por e-mail que a senha foi alterada, com data e hora no fuso configurado.
     * @param u dono da conta.
     */
    private fun avisarSenhaAlterada(u: Usuario) {
        val quando = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm").withZone(props.fuso).format(relogio.instant())
        enviar(u.email, "Sua senha do Nexo foi alterada",
            "Olá, ${u.nome}.\n\nA senha da sua conta foi alterada em $quando.\n\n" +
                "Se não foi você, recupere o acesso agora em ${props.conta.urlFrontend} (\"Esqueci minha senha\").")
    }

    // m***@dominio.com: o aviso mostra para onde foi sem expor o endereço inteiro.
    /**
     * Esconde o miolo do e-mail (m***@dominio.com): o aviso mostra para onde foi sem expor o endereço inteiro.
     * @param email e-mail completo.
     * @return e-mail mascarado.
     */
    private fun mascarar(email: String) = email.substringBefore("@").take(1) + "***@" + email.substringAfter("@")

    /**
     * Erro padrão de e-mail já usado por outra conta.
     * @return erro 409 `email_em_uso`.
     */
    private fun emailEmUso() = ErroNegocio(HttpStatus.CONFLICT, "Este e-mail já está em uso por outra conta.", "email_em_uso")
}
