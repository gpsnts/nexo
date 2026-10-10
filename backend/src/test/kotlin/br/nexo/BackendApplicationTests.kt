package br.nexo

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.net.URI
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendApplicationTests(
    @Autowired val props: NexoProperties,
    @Autowired val jdbc: JdbcTemplate,
    @LocalServerPort val porta: Int,
) {
    companion object {
        const val SENHA_APP = "senha-app-teste"

        // Papel dono aplica o V1 + callback; o backend conecta como nexo_app.
        val pg = PostgreSQLContainer("pgvector/pgvector:pg16").withUsername("nexo_dono").also {
            it.start()
            Flyway.configure().dataSource(it.jdbcUrl, it.username, it.password)
                .locations("filesystem:../db/migrations", "filesystem:../db/callbacks")
                .placeholders(mapOf("nexo_app_senha" to SENHA_APP)).load().migrate()
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasource(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { pg.jdbcUrl }
            r.add("spring.datasource.username") { "nexo_app" }
            r.add("spring.datasource.password") { SENHA_APP }
        }
    }

    @Test
    fun `health responde UP`() {
        val corpo = URI("http://localhost:$porta/actuator/health").toURL().readText()
        assertContains(corpo, "\"UP\"")
    }

    @Test
    fun `parametros padrao do CLAUDE md`() {
        assertEquals(30, props.limiteOperacoesPorHora)
        assertEquals(0.90, props.limiarSimilaridadeRepeticao)
        assertEquals("America/Sao_Paulo", props.fuso.id)
        assertEquals(0, BigDecimal("2.50").compareTo(props.revisao.fatorInicial))
        assertEquals(listOf("pdf", "docx", "txt"), props.formatosAceitos)
    }

    @Test
    fun `sem app usuario_id nao ha leitura nem gravacao`() {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM material", Int::class.java))
        // usuario não tem RLS; assunto tem
        jdbc.update("INSERT INTO usuario(id,nome,email,senha_hash) VALUES (?,?,?,?)", UUID.randomUUID(), "t", "t@x.com", "h")
        val e = assertFailsWith<DataAccessException> {
            jdbc.update(
                "INSERT INTO assunto(id,usuario_id,nome,assunto_padrao) SELECT ?, id, 'Geral', true FROM usuario",
                UUID.randomUUID(),
            )
        }
        assertContains(e.mostSpecificCause.message ?: "", "row-level security")
    }

    @Test
    fun `nexo_app nao le o historico do Flyway`() {
        assertFailsWith<DataAccessException> { jdbc.queryForList("SELECT * FROM flyway_schema_history") }
    }
}
