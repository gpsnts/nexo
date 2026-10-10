package br.nexo

import org.flywaydb.core.Flyway
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** Banco real (pgvector) migrado pelo papel dono; o backend conecta como nexo_app, então a RLS vale nos testes. */
class EmailCaptura : br.nexo.conta.EnviadorEmail {
    class Enviado(val para: String, val assunto: String, val texto: String) {
        val token: String get() = Regex("token=([\\w-]+)").find(texto)!!.groupValues[1]
    }

    val enviados = java.util.concurrent.CopyOnWriteArrayList<Enviado>()
    override fun enviar(para: String, assunto: String, texto: String) { enviados += Enviado(para, assunto, texto) }
    fun para(email: String) = enviados.filter { it.para == email.lowercase() }
    fun com(email: String, trecho: String) = para(email).filter { it.assunto.contains(trecho) }
}

@org.springframework.boot.test.context.TestConfiguration
class EmailTesteConfig {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    fun emailCaptura() = EmailCaptura()
}

// Testes rodam sem segundo plano (e-mails aparecem na hora) e com limite de taxa alto; LimiteTaxaTest baixa o limite.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["nexo.conta.assincrono=false", "nexo.limite-publico.requisicoes=100000"],
)
@org.springframework.context.annotation.Import(EmailTesteConfig::class)
abstract class BaseIntegracao {
    @Autowired lateinit var jdbc: JdbcTemplate // papel nexo_app
    @LocalServerPort var porta: Int = 0
    @Autowired lateinit var emails: EmailCaptura

    /** Papel dono: ignora a RLS. Serve para preparar e conferir dados nos testes. */
    val dono: JdbcTemplate by lazy { JdbcTemplate(DriverManagerDataSource(pg.jdbcUrl, "nexo_dono", pg.password)) }

    companion object {
        const val SENHA_APP = "senha-app-teste"
        const val SEGREDO = "segredo-de-teste-com-mais-de-32-caracteres"

        val pg = PostgreSQLContainer("pgvector/pgvector:pg16").withUsername("nexo_dono").also {
            it.start()
            Flyway.configure().dataSource(it.jdbcUrl, it.username, it.password)
                .locations("filesystem:../db/migrations", "filesystem:../db/callbacks")
                .placeholders(mapOf("nexo_app_senha" to SENHA_APP)).load().migrate()
        }

        @JvmStatic
        @DynamicPropertySource
        fun propriedades(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { pg.jdbcUrl }
            r.add("spring.datasource.username") { "nexo_app" }
            r.add("spring.datasource.password") { SENHA_APP }
            r.add("spring.datasource.hikari.maximum-pool-size") { "1" } // força reuso da conexão: expõe vazamento de contexto
            r.add("nexo.jwt.segredo") { SEGREDO }
        }

        val mapper: JsonMapper = JsonMapper.builder().build()
    }

    class Resposta(val status: Int, val corpo: JsonNode, val cabecalhos: java.net.http.HttpHeaders)

    private val http = HttpClient.newHttpClient()

    fun chamar(metodo: String, caminho: String, json: String? = null, token: String? = null, cookie: String? = null, agente: String? = null, tipo: String = "application/json"): Resposta {
        val b = HttpRequest.newBuilder(URI("http://localhost:$porta$caminho"))
            .method(metodo, json?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (json != null) b.header("Content-Type", tipo)
        if (token != null) b.header("Authorization", "Bearer $token")
        if (cookie != null) b.header("Cookie", cookie)
        if (agente != null) b.header("User-Agent", agente)
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return Resposta(r.statusCode(), if (r.body().isBlank()) mapper.createObjectNode() else mapper.readTree(r.body()), r.headers())
    }

    /** "nexo_refresh=valor" do Set-Cookie, pronto para o cabeçalho Cookie. */
    fun refreshDe(r: Resposta): String = r.cabecalhos.allValues("Set-Cookie").first { it.startsWith("nexo_refresh=") }.substringBefore(";")

    fun cadastrarSemVerificar(nome: String = "Ana", email: String = "${UUID.randomUUID()}@x.com", senha: String = "senha-forte-1"): Resposta =
        chamar("POST", "/auth/cadastro", """{"nome":"$nome","email":"$email","senha":"$senha"}""")

    fun login(email: String, senha: String = "senha-forte-1", agente: String? = null) =
        chamar("POST", "/auth/login", """{"email":"$email","senha":"$senha"}""", agente = agente)

    /** Conta pronta para uso: cadastra, confirma o e-mail pelo link capturado e entra. Devolve a resposta do login (token e cookie). */
    fun cadastrar(nome: String = "Ana", email: String = "${UUID.randomUUID()}@x.com", senha: String = "senha-forte-1", agente: String? = null): Resposta {
        val r = cadastrarSemVerificar(nome, email, senha)
        check(r.status == 201) { "cadastro falhou: ${r.status} ${r.corpo}" }
        val link = emails.com(email, "Confirme seu e-mail").last().token
        check(chamar("POST", "/auth/verificar-email", """{"token":"$link"}""").status == 204) { "verificação falhou" }
        return login(email.trim(), senha, agente)
    }
}
