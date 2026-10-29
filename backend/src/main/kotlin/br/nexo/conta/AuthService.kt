package br.nexo.conta

import br.nexo.NexoProperties
import br.nexo.seguranca.Auditoria
import br.nexo.seguranca.JwtService
import br.nexo.seguranca.UsuarioAtual
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.Locale
import java.util.UUID

/**
 * Resposta de autenticação enviada ao cliente.
 * @property token JWT de acesso.
 * @property nome nome do usuário.
 */
data class Sessao(val token: String, val nome: String)

/** [refresh] vai para o cookie HttpOnly, nunca para o corpo da resposta. */
class Autenticacao(val sessao: Sessao, val refresh: String)

/**
 * Normaliza o e-mail: sem espaços nas pontas e em minúsculas.
 * @param email e-mail digitado.
 * @return e-mail normalizado.
 */
fun normalizarEmail(email: String) = email.trim().lowercase(Locale.ROOT)

/** Autenticação: cadastro, login com bloqueio por falhas, sessões com refresh rotativo e logout. */
@Service
class AuthService(
    private val usuarios: UsuarioRepository,
    private val assuntos: AssuntoRepository,
    private val sessoes: SessaoRepository,
    private val encoder: PasswordEncoder,
    private val jwt: JwtService,
    private val tx: TransactionTemplate,
    private val props: NexoProperties,
    private val relogio: Clock,
    private val verificacao: VerificacaoService,
    private val auditoria: Auditoria,
) {
    /**
     * A conta nasce sem e-mail verificado e SEM sessão: o usuário só entra depois de confirmar o link.
     * @param nome nome de exibição.
     * @param email e-mail (vira minúsculas).
     * @param senha senha, validada pela [PoliticaSenha].
     * @throws ErroNegocio 409 se o e-mail já estiver em uso; 400 se a senha for fraca.
     */
    fun cadastrar(nome: String, email: String, senha: String) {
        val emailNormalizado = normalizarEmail(email)
        PoliticaSenha.exigir(senha, emailNormalizado)
        val id = UUID.randomUUID()
        val hash = encoder.encode(senha)!!
        val usuario = Usuario(id, nome.trim(), emailNormalizado, hash)
        try {
            // O "Geral" é gravado sob RLS, então a transação já nasce com o contexto do novo usuário.
            UsuarioAtual.como(id) {
                tx.executeWithoutResult {
                    if (usuarios.findByEmail(emailNormalizado) != null) throw emailEmUso()
                    usuarios.saveAndFlush(usuario)
                    assuntos.save(Assunto(UUID.randomUUID(), id, "Geral", true))
                }
            }
        } catch (e: DataIntegrityViolationException) {
            throw emailEmUso() // corrida entre dois cadastros do mesmo e-mail: a unicidade do banco decide
        }
        auditoria.registrar("cadastro", id)
        verificacao.enviar(usuario)
    }

    /**
     * Autentica por e-mail e senha.
     * @param email e-mail informado.
     * @param senha senha informada.
     * @param origem dispositivo e IP, gravados na sessão.
     * @return JWT de acesso e refresh para o cookie.
     * @throws ErroNegocio 401 para credenciais inválidas ou conta bloqueada; 403 se o e-mail não foi verificado.
     */
    fun entrar(email: String, senha: String, origem: Origem): Autenticacao {
        val usuario = usuarios.findByEmail(normalizarEmail(email))
        if (!conferirSenha(usuario, senha)) throw ErroNegocio(HttpStatus.UNAUTHORIZED, MENSAGEM_LOGIN, "credenciais_invalidas")
        // Só depois de acertar a senha: dizer "não verificado" antes revelaria quais e-mails existem.
        if (usuario!!.emailVerificadoEm == null) {
            auditoria.registrar("login_nao_verificado", usuario.id)
            throw ErroNegocio(HttpStatus.FORBIDDEN, "Confirme seu e-mail para entrar. Enviamos um link quando você criou a conta.", "email_nao_verificado")
        }
        auditoria.registrar("login_ok", usuario.id)
        return abrirSessao(usuario, origem)
    }

    /**
     * Confere a senha aplicando o bloqueio (5 falhas seguidas). Bloqueado, senha errada e e-mail inexistente têm o mesmo resultado e o mesmo custo (sempre há um hash Argon2), para não revelar quais e-mails existem.
     * @param usuario conta encontrada, ou nulo se o e-mail não existe.
     * @param senha senha informada.
     * @return verdadeiro só se a senha estiver certa e a conta não estiver bloqueada.
     */
    fun conferirSenha(usuario: Usuario?, senha: String): Boolean {
        val agora = relogio.instant()
        val certa = encoder.matches(senha, usuario?.senhaHash ?: hashFalso)
        if (usuario == null) { auditoria.registrar("login_falha", detalhe = "conta=inexistente"); return false }
        if (usuario.bloqueadoAte?.isAfter(agora) == true) { auditoria.registrar("login_bloqueado", usuario.id); return false }
        if (!certa) {
            usuarios.registrarFalha(usuario.id, props.login.maxTentativas, agora.plus(props.login.duracaoBloqueio))
            auditoria.registrar("login_falha", usuario.id)
            if (usuario.falhasLogin + 1 >= props.login.maxTentativas) auditoria.registrar("conta_bloqueada", usuario.id)
            return false
        }
        if (usuario.falhasLogin > 0 || usuario.bloqueadoAte != null) usuarios.limparFalhas(usuario.id)
        return true
    }

    /**
     * Nova família de sessões (login, cadastro, troca de senha).
     * @param usuario dono da sessão.
     * @param origem dispositivo e IP.
     * @return JWT de acesso e refresh.
     */
    fun abrirSessao(usuario: Usuario, origem: Origem): Autenticacao =
        tx.execute { novaSessao(usuario, UUID.randomUUID(), origem, relogio.instant()) }

    /**
     * Rotação: cada refresh vale uma vez. Token já usado volta a aparecer = possível roubo: revoga a família inteira.
     * @param bruto refresh recebido no cookie.
     * @param origem dispositivo e IP.
     * @return novo JWT de acesso e novo refresh.
     * @throws ErroNegocio 401 se o refresh for inválido, usado, revogado ou expirado.
     */
    fun renovar(bruto: String?, origem: Origem): Autenticacao {
        val aut = bruto?.takeIf { it.isNotBlank() }?.let { b ->
            tx.execute {
                val s = sessoes.findByTokenHash(Tokens.hash(b)) ?: return@execute null
                val agora = relogio.instant()
                if (sessoes.revogar(s.id, agora) == 0) { // já usado ou revogado
                    sessoes.revogarFamilia(s.familiaId, agora)
                    auditoria.registrar("refresh_reuso", s.usuarioId, "familia=${s.familiaId}")
                    return@execute null
                }
                if (s.expiraEm.isBefore(agora)) return@execute null
                val usuario = usuarios.findById(s.usuarioId).orElse(null) ?: return@execute null
                novaSessao(usuario, s.familiaId, origem, s.inicioEm)
            }
        }
        return aut ?: throw ErroNegocio(HttpStatus.UNAUTHORIZED, "Sessão expirada. Entre novamente.", "sessao_expirada")
    }

    /**
     * Encerra a família de sessões do refresh informado (logout).
     * @param bruto refresh do cookie; vazio é ignorado.
     */
    fun sair(bruto: String?) {
        if (bruto.isNullOrBlank()) return
        tx.executeWithoutResult {
            sessoes.findByTokenHash(Tokens.hash(bruto))?.let {
                sessoes.revogarFamilia(it.familiaId, relogio.instant())
                auditoria.registrar("logout", it.usuarioId)
            }
        }
    }

    /**
     * Revoga todas as sessões do usuário.
     * @param usuarioId usuário.
     * @return quantidade de sessões revogadas.
     */
    fun revogarTodas(usuarioId: UUID) = sessoes.revogarTodas(usuarioId, relogio.instant())

    /**
     * Grava o refresh novo e emite o JWT.
     * @param usuario dono da sessão.
     * @param familia família do refresh.
     * @param origem dispositivo e IP.
     * @param inicio quando a família começou.
     * @return JWT de acesso e refresh bruto.
     */
    private fun novaSessao(usuario: Usuario, familia: UUID, origem: Origem, inicio: java.time.Instant): Autenticacao {
        val bruto = Tokens.novo()
        sessoes.save(SessaoRefresh(UUID.randomUUID(), usuario.id, familia, Tokens.hash(bruto), relogio.instant().plus(props.jwt.validadeRefresh),
            dispositivo = origem.dispositivo, ip = origem.ip, inicioEm = inicio))
        return Autenticacao(Sessao(jwt.emitir(usuario.id, familia), usuario.nome), bruto)
    }

    /**
     * Erro padrão de e-mail já cadastrado.
     * @return erro 409 `email_em_uso`.
     */
    private fun emailEmUso() = ErroNegocio(HttpStatus.CONFLICT, "Este e-mail já está cadastrado. Entre com a sua conta.", "email_em_uso")

    private val hashFalso by lazy { encoder.encode("senha-que-nunca-sera-usada")!! }

    companion object {
        const val MENSAGEM_LOGIN = "E-mail ou senha incorretos, ou acesso temporariamente bloqueado."
    }
}
