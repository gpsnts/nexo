package br.nexo.conta

import br.nexo.NexoProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.UUID

private const val SENHA_MSG = "A senha precisa ter de 8 a 128 caracteres."
private const val EMAIL_MSG = "E-mail inválido."
private const val EMAIL_REGEX = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$" // exige domínio com ponto: "a@b" não passa
private const val NOME_MSG = "O nome pode ter até 120 caracteres."

/**
 * Corpo de `POST /auth/cadastro`.
 * @property nome nome de exibição (até 120 caracteres).
 * @property email e-mail com domínio.
 * @property senha senha de 8 a 128 caracteres.
 */
data class CadastroRequest(
    @field:NotBlank(message = "Informe o nome.") @field:Size(max = 120, message = NOME_MSG) val nome: String,
    @field:Email(message = EMAIL_MSG) @field:Pattern(regexp = EMAIL_REGEX, message = EMAIL_MSG) @field:NotBlank(message = "Informe o e-mail.") @field:Size(max = 254, message = EMAIL_MSG) val email: String,
    @field:Size(min = 8, max = 128, message = SENHA_MSG) val senha: String,
)

/**
 * Corpo de `POST /auth/login`.
 * @property email e-mail da conta.
 * @property senha senha da conta.
 */
data class LoginRequest(@field:NotBlank @field:Size(max = 254) val email: String, @field:NotBlank @field:Size(max = 128, message = SENHA_MSG) val senha: String)
/**
 * Corpo das rotas que só recebem um e-mail.
 * @property email e-mail informado.
 */
data class EmailRequest(@field:NotBlank @field:Size(max = 254) val email: String)
/**
 * Corpo de `POST /auth/redefinir-senha`.
 * @property token token bruto do link.
 * @property novaSenha nova senha de 8 a 128 caracteres.
 */
data class RedefinirRequest(@field:NotBlank @field:Size(max = 200) val token: String, @field:Size(min = 8, max = 128, message = SENHA_MSG) val novaSenha: String)
/**
 * Corpo das rotas que confirmam um link por e-mail.
 * @property token token bruto do link.
 */
data class TokenRequest(@field:NotBlank @field:Size(max = 200) val token: String)

private const val COOKIE = "nexo_refresh"

/** Cookie do refresh: HttpOnly (JS não lê), SameSite=Strict e restrito às rotas de /auth. */
@org.springframework.stereotype.Component
class CookieRefresh(private val props: NexoProperties) {
    /**
     * Monta o cookie do refresh.
     * @param valor token (vazio para apagar o cookie).
     * @param idade tempo de vida do cookie.
     * @return o cookie HttpOnly, SameSite=Strict, restrito às rotas de /auth.
     */
    private fun base(valor: String, idade: Duration) = ResponseCookie.from(COOKIE, valor)
        .httpOnly(true).secure(props.conta.cookieSeguro).sameSite("Strict").path(props.conta.caminhoCookie).maxAge(idade).build()

    /**
     * Responde com o JWT no corpo e o refresh no cookie.
     * @param a autenticação emitida.
     * @param status status da resposta.
     * @return resposta com `Set-Cookie` e a [Sessao] no corpo.
     */
    fun responder(a: Autenticacao, status: HttpStatus = HttpStatus.OK): ResponseEntity<Sessao> =
        ResponseEntity.status(status).header(HttpHeaders.SET_COOKIE, base(a.refresh, props.jwt.validadeRefresh).toString()).body(a.sessao)

    /**
     * Cookie que apaga o refresh do navegador.
     * @return o valor do cabeçalho `Set-Cookie`.
     */
    fun limpo(): String = base("", Duration.ZERO).toString()
}

/** Rotas públicas de autenticação (`/auth`): cadastro, verificação de e-mail, login, refresh, logout e recuperação de senha. */
@RestController
@RequestMapping("/auth")
class AuthController(
    private val auth: AuthService,
    private val conta: ContaService,
    private val verificacao: VerificacaoService,
    private val cookie: CookieRefresh,
) {
    /**
     * Cria a conta (sem sessão: o login só vale depois de confirmar o e-mail).
     * @param r dados do cadastro.
     * @return 201 com mensagem; 409 se o e-mail estiver em uso.
     */
    @PostMapping("/cadastro")
    fun cadastro(@Valid @RequestBody r: CadastroRequest): ResponseEntity<Map<String, String>> {
        auth.cadastrar(r.nome, r.email, r.senha)
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("mensagem" to "Conta criada. Enviamos um link de confirmação para o seu e-mail."))
    }

    /**
     * Confirma o e-mail do cadastro.
     * @param r token do link.
     * @return 204; 400 se o link for inválido ou expirado.
     */
    @PostMapping("/verificar-email")
    fun verificarEmail(@Valid @RequestBody r: TokenRequest): ResponseEntity<Void> {
        verificacao.verificar(r.token)
        return ResponseEntity.noContent().build()
    }

    /**
     * Reenvia o link de verificação.
     * @param r e-mail informado.
     * @return 202, exista ou não a conta.
     */
    @PostMapping("/reenviar-verificacao")
    fun reenviarVerificacao(@Valid @RequestBody r: EmailRequest): ResponseEntity<Void> {
        verificacao.reenviar(r.email)
        return ResponseEntity.accepted().build()
    }

    /**
     * Entra com e-mail e senha.
     * @param r credenciais.
     * @param req requisição, de onde vêm dispositivo e IP.
     * @return 200 com o JWT e o cookie do refresh; 401 ou 403 nas falhas.
     */
    @PostMapping("/login")
    fun login(@Valid @RequestBody r: LoginRequest, req: HttpServletRequest) = cookie.responder(auth.entrar(r.email, r.senha, req.origem()))

    /**
     * Renova a sessão (rotação do refresh).
     * @param bruto refresh vindo do cookie.
     * @param req requisição, de onde vêm dispositivo e IP.
     * @return 200 com novo JWT e novo cookie; 401 se a sessão expirou.
     */
    @PostMapping("/refresh")
    fun refresh(@CookieValue(COOKIE, required = false) bruto: String?, req: HttpServletRequest) = cookie.responder(auth.renovar(bruto, req.origem()))

    /**
     * Encerra a sessão do refresh do cookie.
     * @param bruto refresh vindo do cookie.
     * @return 204 e cookie apagado.
     */
    @PostMapping("/logout")
    fun logout(@CookieValue(COOKIE, required = false) bruto: String?): ResponseEntity<Void> {
        auth.sair(bruto)
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.limpo()).build()
    }

    /**
     * Pede o link de redefinição de senha.
     * @param r e-mail informado.
     * @return 202, exista ou não a conta.
     */
    @PostMapping("/esqueci-senha")
    fun esqueciSenha(@Valid @RequestBody r: EmailRequest): ResponseEntity<Void> {
        conta.esqueciSenha(r.email)
        return ResponseEntity.accepted().build()
    }

    /**
     * Define a nova senha pelo link do e-mail.
     * @param r token e nova senha.
     * @return 204; 400 se o link for inválido ou a senha, fraca.
     */
    @PostMapping("/redefinir-senha")
    fun redefinirSenha(@Valid @RequestBody r: RedefinirRequest): ResponseEntity<Void> {
        conta.redefinirSenha(r.token, r.novaSenha)
        return ResponseEntity.noContent().build()
    }

    /**
     * Confirma a troca de e-mail pelo link.
     * @param r token do link.
     * @return 204; 400 se o link for inválido; 409 se o e-mail estiver em uso.
     */
    @PostMapping("/confirmar-email")
    fun confirmarEmail(@Valid @RequestBody r: TokenRequest): ResponseEntity<Void> {
        conta.confirmarEmail(r.token)
        return ResponseEntity.noContent().build()
    }
}

/**
 * Corpo de `PATCH /perfil`.
 * @property nome novo nome (até 120 caracteres).
 */
data class NomeRequest(@field:NotBlank(message = "Informe o nome.") @field:Size(max = 120, message = NOME_MSG) val nome: String)
/**
 * Corpo de `POST /perfil/email`.
 * @property senhaAtual senha atual, para reautenticar.
 * @property novoEmail novo e-mail.
 */
data class TrocaEmailRequest(
    @field:NotBlank @field:Size(max = 128, message = SENHA_MSG) val senhaAtual: String,
    @field:Email(message = EMAIL_MSG) @field:Pattern(regexp = EMAIL_REGEX, message = EMAIL_MSG) @field:NotBlank(message = "Informe o e-mail.") @field:Size(max = 254, message = EMAIL_MSG) val novoEmail: String,
)
/**
 * Corpo de `PUT /perfil/senha`.
 * @property senhaAtual senha atual.
 * @property novaSenha nova senha de 8 a 128 caracteres.
 */
data class TrocaSenhaRequest(@field:NotBlank @field:Size(max = 128, message = SENHA_MSG) val senhaAtual: String, @field:Size(min = 8, max = 128, message = SENHA_MSG) val novaSenha: String)
/**
 * Corpo de `DELETE /perfil`.
 * @property senha senha atual.
 * @property confirmacao deve ser a palavra EXCLUIR.
 */
data class ExclusaoRequest(@field:NotBlank @field:Size(max = 128, message = SENHA_MSG) val senha: String, @field:Size(max = 20) val confirmacao: String)

/** Rotas do perfil do usuário autenticado (`/perfil`): nome, e-mail, senha, sessões ativas e exclusão da conta. Nenhuma rota recebe id de usuário: tudo sai do JWT. */
@RestController
@RequestMapping("/perfil")
class PerfilController(private val conta: ContaService, private val cookie: CookieRefresh) {
    /**
     * Mostra o perfil.
     * @return nome e e-mail.
     */
    @GetMapping
    fun ver() = conta.perfil()

    /**
     * Altera o nome.
     * @param r novo nome.
     * @return o perfil atualizado.
     */
    @PatchMapping
    fun nome(@Valid @RequestBody r: NomeRequest) = conta.alterarNome(r.nome)

    /**
     * Pede a troca de e-mail (link enviado ao novo endereço).
     * @param r senha atual e novo e-mail.
     * @return 202; 403 se a senha estiver errada; 409 se o e-mail estiver em uso.
     */
    @PostMapping("/email")
    fun email(@Valid @RequestBody r: TrocaEmailRequest): ResponseEntity<Void> {
        conta.solicitarTrocaEmail(r.senhaAtual, r.novoEmail)
        return ResponseEntity.accepted().build()
    }

    /**
     * Troca a senha e abre uma nova sessão neste dispositivo.
     * @param r senha atual e nova.
     * @param req requisição, de onde vêm dispositivo e IP.
     * @return 200 com novo JWT e cookie; 403 se a senha atual estiver errada.
     */
    @PutMapping("/senha")
    fun senha(@Valid @RequestBody r: TrocaSenhaRequest, req: HttpServletRequest) = cookie.responder(conta.alterarSenha(r.senhaAtual, r.novaSenha, req.origem()))

    /**
     * Lista as sessões ativas.
     * @return sessões do usuário, marcando a atual.
     */
    @GetMapping("/sessoes")
    fun sessoes() = conta.sessoesAtivas()

    /**
     * Encerra uma sessão do usuário.
     * @param id família da sessão.
     * @return 204; 404 se não for dele.
     */
    @DeleteMapping("/sessoes/{id}")
    fun encerrarSessao(@PathVariable id: UUID): ResponseEntity<Void> {
        conta.encerrarSessao(id)
        return ResponseEntity.noContent().build()
    }

    /**
     * Encerra todas as sessões, menos a deste dispositivo.
     * @return 204.
     */
    @DeleteMapping("/sessoes")
    fun encerrarOutras(): ResponseEntity<Void> {
        conta.encerrarOutrasSessoes()
        return ResponseEntity.noContent().build()
    }

    /**
     * Exclui a conta e todos os dados (cascata no banco).
     * @param r senha e confirmação EXCLUIR.
     * @return 204 e cookie apagado; 400 ou 403 nas falhas.
     */
    @DeleteMapping
    fun excluir(@Valid @RequestBody r: ExclusaoRequest): ResponseEntity<Void> {
        conta.excluirConta(r.senha, r.confirmacao)
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.limpo()).build()
    }
}

/**
 * Assunto (pasta) devolvido pela API.
 * @property id identificador.
 * @property nome nome do assunto.
 * @property padrao se é o "Geral".
 * @property topicos quantos tópicos a pasta tem.
 * @property materiais quantos materiais diferentes alimentam tópicos da pasta.
 */
data class AssuntoDto(val id: UUID, val nome: String, val padrao: Boolean, val topicos: Int = 0, val materiais: Int = 0)

/** Lista dos assuntos do usuário (`/assuntos`). */
@RestController
class AssuntoController(private val jdbc: org.springframework.jdbc.core.JdbcTemplate) {
    // Sem filtro de usuário no código: quem isola é a RLS, com o contexto posto pela transação.
    /**
     * Lista os assuntos do usuário, com a contagem de tópicos e de materiais de cada um; o isolamento vem da RLS.
     * @param jwt token do usuário autenticado.
     * @return os assuntos do usuário, o padrão primeiro.
     */
    @GetMapping("/assuntos")
    @Transactional(readOnly = true)
    fun listar(@AuthenticationPrincipal jwt: Jwt) = jdbc.query(
        """SELECT a.id, a.nome, a.assunto_padrao, (SELECT count(*) FROM topico t WHERE t.assunto_id = a.id) AS topicos,
                  (SELECT count(DISTINCT f.material_id) FROM topico t JOIN topico_fonte f ON f.topico_id = t.id WHERE t.assunto_id = a.id) AS materiais
           FROM assunto a ORDER BY a.assunto_padrao DESC, a.criado_em""",
        { rs, _ -> AssuntoDto(rs.getObject("id", UUID::class.java), rs.getString("nome"), rs.getBoolean("assunto_padrao"), rs.getInt("topicos"), rs.getInt("materiais")) },
    )
}
