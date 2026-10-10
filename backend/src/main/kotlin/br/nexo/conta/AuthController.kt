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

data class CadastroRequest(
    @field:NotBlank(message = "Informe o nome.") @field:Size(max = 120, message = NOME_MSG) val nome: String,
    @field:Email(message = EMAIL_MSG) @field:Pattern(regexp = EMAIL_REGEX, message = EMAIL_MSG) @field:NotBlank(message = "Informe o e-mail.") @field:Size(max = 254, message = EMAIL_MSG) val email: String,
    @field:Size(min = 8, max = 128, message = SENHA_MSG) val senha: String,
)

data class LoginRequest(@field:NotBlank @field:Size(max = 254) val email: String, @field:NotBlank @field:Size(max = 128, message = SENHA_MSG) val senha: String)
data class EmailRequest(@field:NotBlank @field:Size(max = 254) val email: String)
data class RedefinirRequest(@field:NotBlank @field:Size(max = 200) val token: String, @field:Size(min = 8, max = 128, message = SENHA_MSG) val novaSenha: String)
data class TokenRequest(@field:NotBlank @field:Size(max = 200) val token: String)

private const val COOKIE = "nexo_refresh"

/** Cookie do refresh: HttpOnly (JS não lê), SameSite=Strict e restrito às rotas de /auth (ADR 0008). */
@org.springframework.stereotype.Component
class CookieRefresh(private val props: NexoProperties) {
    private fun base(valor: String, idade: Duration) = ResponseCookie.from(COOKIE, valor)
        .httpOnly(true).secure(props.conta.cookieSeguro).sameSite("Strict").path(props.conta.caminhoCookie).maxAge(idade).build()

    fun responder(a: Autenticacao, status: HttpStatus = HttpStatus.OK): ResponseEntity<Sessao> =
        ResponseEntity.status(status).header(HttpHeaders.SET_COOKIE, base(a.refresh, props.jwt.validadeRefresh).toString()).body(a.sessao)

    fun limpo(): String = base("", Duration.ZERO).toString()
}

@RestController
@RequestMapping("/auth")
class AuthController(
    private val auth: AuthService,
    private val conta: ContaService,
    private val verificacao: VerificacaoService,
    private val cookie: CookieRefresh,
) {
    @PostMapping("/cadastro")
    fun cadastro(@Valid @RequestBody r: CadastroRequest): ResponseEntity<Map<String, String>> {
        auth.cadastrar(r.nome, r.email, r.senha)
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("mensagem" to "Conta criada. Enviamos um link de confirmação para o seu e-mail."))
    }

    @PostMapping("/verificar-email")
    fun verificarEmail(@Valid @RequestBody r: TokenRequest): ResponseEntity<Void> {
        verificacao.verificar(r.token)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/reenviar-verificacao")
    fun reenviarVerificacao(@Valid @RequestBody r: EmailRequest): ResponseEntity<Void> {
        verificacao.reenviar(r.email)
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody r: LoginRequest, req: HttpServletRequest) = cookie.responder(auth.entrar(r.email, r.senha, req.origem()))

    @PostMapping("/refresh")
    fun refresh(@CookieValue(COOKIE, required = false) bruto: String?, req: HttpServletRequest) = cookie.responder(auth.renovar(bruto, req.origem()))

    @PostMapping("/logout")
    fun logout(@CookieValue(COOKIE, required = false) bruto: String?): ResponseEntity<Void> {
        auth.sair(bruto)
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.limpo()).build()
    }

    @PostMapping("/esqueci-senha")
    fun esqueciSenha(@Valid @RequestBody r: EmailRequest): ResponseEntity<Void> {
        conta.esqueciSenha(r.email)
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/redefinir-senha")
    fun redefinirSenha(@Valid @RequestBody r: RedefinirRequest): ResponseEntity<Void> {
        conta.redefinirSenha(r.token, r.novaSenha)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/confirmar-email")
    fun confirmarEmail(@Valid @RequestBody r: TokenRequest): ResponseEntity<Void> {
        conta.confirmarEmail(r.token)
        return ResponseEntity.noContent().build()
    }
}

data class NomeRequest(@field:NotBlank(message = "Informe o nome.") @field:Size(max = 120, message = NOME_MSG) val nome: String)
data class TrocaEmailRequest(
    @field:NotBlank @field:Size(max = 128, message = SENHA_MSG) val senhaAtual: String,
    @field:Email(message = EMAIL_MSG) @field:Pattern(regexp = EMAIL_REGEX, message = EMAIL_MSG) @field:NotBlank(message = "Informe o e-mail.") @field:Size(max = 254, message = EMAIL_MSG) val novoEmail: String,
)
data class TrocaSenhaRequest(@field:NotBlank @field:Size(max = 128, message = SENHA_MSG) val senhaAtual: String, @field:Size(min = 8, max = 128, message = SENHA_MSG) val novaSenha: String)
data class ExclusaoRequest(@field:NotBlank @field:Size(max = 128, message = SENHA_MSG) val senha: String, @field:Size(max = 20) val confirmacao: String)

@RestController
@RequestMapping("/perfil")
class PerfilController(private val conta: ContaService, private val cookie: CookieRefresh) {
    @GetMapping
    fun ver() = conta.perfil()

    @PatchMapping
    fun nome(@Valid @RequestBody r: NomeRequest) = conta.alterarNome(r.nome)

    @PostMapping("/email")
    fun email(@Valid @RequestBody r: TrocaEmailRequest): ResponseEntity<Void> {
        conta.solicitarTrocaEmail(r.senhaAtual, r.novoEmail)
        return ResponseEntity.accepted().build()
    }

    @PutMapping("/senha")
    fun senha(@Valid @RequestBody r: TrocaSenhaRequest, req: HttpServletRequest) = cookie.responder(conta.alterarSenha(r.senhaAtual, r.novaSenha, req.origem()))

    @GetMapping("/sessoes")
    fun sessoes() = conta.sessoesAtivas()

    @DeleteMapping("/sessoes/{id}")
    fun encerrarSessao(@PathVariable id: UUID): ResponseEntity<Void> {
        conta.encerrarSessao(id)
        return ResponseEntity.noContent().build()
    }

    /** Encerra todas as sessões, menos a deste dispositivo. */
    @DeleteMapping("/sessoes")
    fun encerrarOutras(): ResponseEntity<Void> {
        conta.encerrarOutrasSessoes()
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping
    fun excluir(@Valid @RequestBody r: ExclusaoRequest): ResponseEntity<Void> {
        conta.excluirConta(r.senha, r.confirmacao)
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.limpo()).build()
    }
}

data class AssuntoDto(val id: UUID, val nome: String, val padrao: Boolean)

@RestController
class AssuntoController(private val assuntos: AssuntoRepository) {
    // Sem filtro de usuário no código: quem isola é a RLS, com o contexto posto pela transação.
    @GetMapping("/assuntos")
    @Transactional(readOnly = true)
    fun listar(@AuthenticationPrincipal jwt: Jwt) = assuntos.findAll().map { AssuntoDto(it.id, it.nome, it.assuntoPadrao) }
}
