package br.nexo

import br.nexo.seguranca.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthTest : BaseIntegracao() {
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") lateinit var mapeamento: RequestMappingHandlerMapping

    private fun assuntos(token: String?) = chamar("GET", "/assuntos", token = token)

    @Test
    fun `cadastro grava e-mail em minusculas, hash Argon2id e o assunto Geral`() {
        val email = "Ana-${UUID.randomUUID()}@X.com"
        val r = cadastrar(email = email, senha = "senha-forte-1")
        assertEquals(200, r.status)
        val linha = dono.queryForMap("SELECT id, email, senha_hash FROM usuario WHERE email = ?", email.lowercase())
        assertTrue((linha["senha_hash"] as String).startsWith("\$argon2id\$"))
        assertNotEquals("senha-forte-1", linha["senha_hash"])
        val lista = assuntos(r.corpo["token"].asString())
        assertEquals(200, lista.status)
        assertEquals(1, lista.corpo.size())
        assertEquals("Geral", lista.corpo[0]["nome"].asString())
        assertTrue(lista.corpo[0]["padrao"].asBoolean())
    }

    @Test
    fun `e-mail repetido, mesmo com outra caixa, devolve 409`() {
        val email = "${UUID.randomUUID()}@x.com"
        assertEquals(201, cadastrarSemVerificar(email = email).status)
        val r = cadastrarSemVerificar(email = email.uppercase())
        assertEquals(409, r.status)
        assertTrue(r.corpo["mensagem"].asString().contains("Entre"))
        assertEquals("email_em_uso", r.corpo["codigo"].asString())
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM usuario WHERE email = ?", Int::class.java, email))
    }

    @Test
    fun `cadastro invalido devolve 400`() {
        assertEquals(400, cadastrarSemVerificar(email = "nao-e-email").status)
        assertEquals(400, cadastrarSemVerificar(senha = "curta").status)
        assertEquals(400, cadastrarSemVerificar(nome = " ").status)
    }

    @Test
    fun `login funciona com qualquer caixa e erro e igual para e-mail inexistente e senha errada`() {
        val email = "${UUID.randomUUID()}@x.com"
        cadastrar(email = email, senha = "senha-forte-1")
        assertEquals(200, login(email.uppercase(), "senha-forte-1").status)
        val errada = login(email, "outra-senha-1")
        val inexistente = login("${UUID.randomUUID()}@x.com", "senha-forte-1")
        assertEquals(401, errada.status)
        assertEquals(401, inexistente.status)
        assertEquals(errada.corpo.toString(), inexistente.corpo.toString())
        assertEquals("credenciais_invalidas", errada.corpo["codigo"].asString())
    }

    @Test
    fun `rota com id de B e token de A so mostra o que e de A`() {
        val a = cadastrar().corpo["token"].asString()
        val b = cadastrar().corpo["token"].asString()
        val idsDeB = assuntos(b).corpo.toList().map { it["id"].asString() }
        dono.update(
            "INSERT INTO assunto(id,usuario_id,nome,assunto_padrao) SELECT ?, usuario_id, 'Só do B', false FROM assunto WHERE id = ?::uuid",
            UUID.randomUUID(), idsDeB[0],
        )
        val deA = assuntos(a).corpo.toList().map { it["id"].asString() }
        assertEquals(1, deA.size)
        assertTrue(deA.none { it in idsDeB })
        assertEquals(2, assuntos(b).corpo.size())
    }

    @Test
    fun `toda rota fora da lista publica exige token valido`() {
        val publicas = setOf(
            "/auth/cadastro", "/auth/login", "/auth/refresh", "/auth/logout", "/auth/esqueci-senha", "/auth/redefinir-senha", "/auth/confirmar-email", "/auth/verificar-email", "/auth/reenviar-verificacao",
            "/auth/verificar-email", "/auth/reenviar-verificacao",
        )
        val forjado = JwtService("outro-segredo-com-mais-de-32-caracteres", Duration.ofMinutes(5)).emitir(UUID.randomUUID(), UUID.randomUUID())
        val expirado = JwtService(SEGREDO, Duration.ofMinutes(5), Clock.offset(Clock.systemUTC(), Duration.ofHours(-1))).emitir(UUID.randomUUID(), UUID.randomUUID())
        val protegidas = mapeamento.handlerMethods.keys.flatMap { info ->
            val metodos = info.methodsCondition.methods.map { it.name }.ifEmpty { listOf("GET") }
            info.pathPatternsCondition!!.patternValues.flatMap { p -> metodos.map { it to p } }
        }.filter { (_, p) -> p !in publicas && !p.startsWith("/actuator") && p != "/error" }
        assertTrue(protegidas.isNotEmpty())
        for ((metodo, padrao) in protegidas) {
            val caminho = padrao.replace(Regex("\\{[^}]+}"), UUID.randomUUID().toString())
            for (token in listOf(null, forjado, expirado, "lixo")) {
                assertEquals(401, chamar(metodo, caminho, json = if (metodo == "GET") null else "{}", token = token).status, "$metodo $padrao")
            }
        }
    }
}
