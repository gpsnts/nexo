package br.nexo

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import java.math.BigDecimal
import java.net.URI
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackendApplicationTests : BaseIntegracao() {
    @Autowired lateinit var props: NexoProperties

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
