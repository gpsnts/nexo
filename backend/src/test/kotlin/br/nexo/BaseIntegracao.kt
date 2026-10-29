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

/** Relógio dos testes: segue o tempo real até [fixar]; as cópias de `withZone` compartilham o mesmo valor. Testes que fixam devem chamar [liberar]. */
class RelogioTeste(private val zona: java.time.ZoneId = java.time.ZoneOffset.UTC, private val origem: java.util.concurrent.atomic.AtomicReference<java.time.Instant?> = java.util.concurrent.atomic.AtomicReference()) : java.time.Clock() {
    override fun getZone(): java.time.ZoneId = zona
    override fun withZone(zone: java.time.ZoneId): java.time.Clock = RelogioTeste(zone, origem)
    override fun instant(): java.time.Instant = origem.get() ?: java.time.Instant.now()
    fun fixar(instante: String) = origem.set(java.time.Instant.parse(instante))
    fun liberar() = origem.set(null)
}

@org.springframework.boot.test.context.TestConfiguration
class RelogioTesteConfig {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    fun relogioTeste() = RelogioTeste()
}

// Testes rodam sem segundo plano (e-mails aparecem na hora) e com limite de taxa alto; LimiteTaxaTest baixa o limite.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["nexo.conta.assincrono=false", "nexo.material.assincrono=false", "nexo.geracao.assincrono=false", "nexo.limite-publico.requisicoes=100000"],
)
@org.springframework.context.annotation.Import(EmailTesteConfig::class, RelogioTesteConfig::class)
abstract class BaseIntegracao {
    @Autowired lateinit var jdbc: JdbcTemplate // papel nexo_app
    @LocalServerPort var porta: Int = 0
    @Autowired lateinit var emails: EmailCaptura

    /** Papel dono: ignora a RLS. Serve para preparar e conferir dados nos testes. */
    val dono: JdbcTemplate by lazy { JdbcTemplate(DriverManagerDataSource(pg.jdbcUrl, "nexo_dono", pg.password)) }

    companion object {
        const val SENHA_APP = "senha-app-teste"
        const val SEGREDO = "segredo-de-teste-com-mais-de-32-caracteres"
        const val SEGREDO_INTERNO = "segredo-interno-de-teste"

        /** Serviço Python de mentira e pasta de arquivos temporária, compartilhados por todos os testes (um só contexto Spring). */
        val python = PythonFalso(SEGREDO_INTERNO)
        val arquivos: java.nio.file.Path = java.nio.file.Files.createTempDirectory("nexo-arquivos")

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
            r.add("nexo.processamento.url") { python.url }
            r.add("nexo.processamento.segredo") { SEGREDO_INTERNO }
            r.add("nexo.processamento.espera") { "PT0.01S" }
            r.add("nexo.processamento.cache-modelos") { "PT0S" }  // os testes trocam o nome do modelo no serviço falso: sem cache
            r.add("nexo.geracao.contexto-max-caracteres") { "300" }  // pequeno, para exercitar a busca vetorial com poucos trechos
            r.add("nexo.material.diretorio") { arquivos.toString() }
            r.add("nexo.material.esperas-remocao") { "PT0.05S,PT0.05S" }
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

    /** Envio multipart de um arquivo (POST /materiais). */
    fun enviarArquivo(token: String, nome: String, conteudo: ByteArray, assuntoId: String? = null): Resposta {
        val limite = "teste" + UUID.randomUUID().toString().replace("-", "")
        val corpo = java.io.ByteArrayOutputStream().apply {
            if (assuntoId != null) write("--$limite\r\nContent-Disposition: form-data; name=\"assuntoId\"\r\n\r\n$assuntoId\r\n".toByteArray())
            write("--$limite\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"$nome\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(conteudo); write("\r\n--$limite--\r\n".toByteArray())
        }.toByteArray()
        val r = http.send(
            HttpRequest.newBuilder(URI("http://localhost:$porta/materiais")).header("Authorization", "Bearer $token")
                .header("Content-Type", "multipart/form-data; boundary=$limite").POST(HttpRequest.BodyPublishers.ofByteArray(corpo)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
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
