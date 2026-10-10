package br.nexo

import br.nexo.seguranca.UsuarioAtual
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Prova da RLS e do mecanismo que põe app.usuario_id em cada transação. O pool tem 1 conexão: todo teste reusa a mesma. */
class IsolamentoTest : BaseIntegracao() {
    @Autowired lateinit var tx: TransactionTemplate

    private fun novoUsuario(): UUID = UUID.randomUUID().also { id ->
        dono.update("INSERT INTO usuario(id,nome,email,senha_hash) VALUES (?,?,?,?)", id, "u", "$id@x.com", "h")
        dono.update("INSERT INTO assunto(id,usuario_id,nome,assunto_padrao) VALUES (?,?,?,true)", UUID.randomUUID(), id, "Geral")
    }

    private fun <T> como(id: UUID?, bloco: () -> T): T =
        if (id == null) tx.execute { bloco() }!! else UsuarioAtual.como(id) { tx.execute { bloco() }!! }

    private fun nomesVisiveis() = jdbc.queryForList("SELECT id::text FROM assunto", String::class.java)

    @Test
    fun `sem contexto as consultas voltam vazias e a gravacao e recusada`() {
        val a = novoUsuario()
        assertTrue(como(null) { nomesVisiveis() }.isEmpty())
        assertFailsWith<DataAccessException> {
            como(null) { jdbc.update("INSERT INTO assunto(id,usuario_id,nome,assunto_padrao) VALUES (?,?,'X',false)", UUID.randomUUID(), a) }
        }
    }

    @Test
    fun `cada usuario ve so as proprias linhas`() {
        val a = novoUsuario(); val b = novoUsuario()
        val deA = como(a) { jdbc.queryForList("SELECT usuario_id::text FROM assunto", String::class.java) }
        assertTrue(deA.isNotEmpty() && deA.all { it == a.toString() })
        assertTrue(como(b) { jdbc.queryForList("SELECT usuario_id::text FROM assunto", String::class.java) }.all { it == b.toString() })
    }

    @Test
    fun `A nao grava em nome de B nem altera ou apaga linhas de B`() {
        val a = novoUsuario(); val b = novoUsuario()
        val idDeB = dono.queryForObject("SELECT id FROM assunto WHERE usuario_id = ?", UUID::class.java, b)!!
        assertFailsWith<DataAccessException> {
            como(a) { jdbc.update("INSERT INTO assunto(id,usuario_id,nome,assunto_padrao) VALUES (?,?,'Invasor',false)", UUID.randomUUID(), b) }
        }
        assertEquals(0, como(a) { jdbc.update("UPDATE assunto SET nome = 'Hackeado' WHERE id = ?", idDeB) })
        assertEquals(0, como(a) { jdbc.update("DELETE FROM assunto WHERE id = ?", idDeB) })
        assertEquals("Geral", dono.queryForObject("SELECT nome FROM assunto WHERE id = ?", String::class.java, idDeB))
    }

    @Test
    fun `o contexto nao vaza para a transacao seguinte na mesma conexao`() {
        val a = novoUsuario()
        assertTrue(como(a) { nomesVisiveis() }.isNotEmpty())
        assertTrue(como(null) { nomesVisiveis() }.isEmpty())
    }

    @Test
    fun `como restaura o valor anterior mesmo com excecao`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        UsuarioAtual.como(a) {
            runCatching { UsuarioAtual.como(b) { error("falha") } }
            assertEquals(a, UsuarioAtual.id())
        }
        assertEquals(null, UsuarioAtual.id())
    }
}
