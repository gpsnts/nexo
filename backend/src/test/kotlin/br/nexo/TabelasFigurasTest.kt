package br.nexo

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tabelas e figuras na indexação: tipo do trecho gravado, avisos de figuras ignoradas, custo da visão e isolamento entre contas. */
class TabelasFigurasTest : BaseIntegracao() {
    class Conta(val token: String, val id: UUID)

    private fun conta(): Conta {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        return Conta(token, dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!)
    }

    private fun enviar(c: Conta, marcas: String) =
        enviarArquivo(c.token, "a.pdf", "%PDF-1.4 PAGINAS=3 TRECHOS=6 $marcas nonce=${UUID.randomUUID()}".toByteArray()).corpo["materialId"].asString()

    private fun material(c: Conta, id: String) = chamar("GET", "/materiais", token = c.token).corpo.toList().firstOrNull { it["id"].asString() == id }

    @Test
    fun `grava o tipo de cada trecho, o aviso das figuras ignoradas e soma o gasto da visao ao registro da indexacao`() {
        val c = conta()
        val id = enviar(c, "TABELAS=2,5 FIGURAS=3 FIGURAS_ACIMA=2 FIGURAS_FALHA=1 VISAO=1")
        assertEquals(
            listOf("texto", "tabela", "figura", "texto", "tabela", "texto"),
            dono.queryForList("SELECT tipo_conteudo FROM trecho WHERE material_id = ?::uuid ORDER BY ordem", String::class.java, id),
        )
        val m = assertNotNull(material(c, id))
        assertEquals("pronto", m["status"].asString())
        assertEquals(2, m["figurasAcimaDoLimite"].asInt()); assertEquals(1, m["figurasComFalha"].asInt())
        val idx = dono.queryForMap("SELECT tokens_entrada, tokens_saida FROM registro_geracao WHERE material_id = ?::uuid AND operacao = 'indexacao_material'", id)
        assertEquals(60 + 120, idx["tokens_entrada"], "6 trechos x 10 tokens de embedding + 120 da visão"); assertEquals(30, idx["tokens_saida"])
        assertEquals(30, python.ultimoMaxFiguras, "o limite de figuras por material sai da configuração")
    }

    @Test
    fun `figura que falha deixa o material pronto com aviso e sem trecho de figura`() {
        val c = conta()
        val id = enviar(c, "FIGURAS_FALHA=3")
        val m = assertNotNull(material(c, id))
        assertEquals("pronto", m["status"].asString()); assertEquals(3, m["figurasComFalha"].asInt())
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM trecho WHERE material_id = ?::uuid AND tipo_conteudo = 'figura'", Int::class.java, id))
    }

    @Test
    fun `material sem figuras nao traz aviso`() {
        val c = conta()
        val m = assertNotNull(material(c, enviar(c, "")))
        assertEquals(0, m["figurasAcimaDoLimite"].asInt()); assertEquals(0, m["figurasComFalha"].asInt())
    }

    @Test
    fun `uma conta nao enxerga o material nem os avisos de outra`() {
        val a = conta(); val b = conta()
        val idA = enviar(a, "TABELAS=2 FIGURAS_FALHA=4")
        val idB = enviar(b, "")
        assertTrue(material(b, idA) == null, "B não vê o material de A")
        assertEquals(0, material(b, idB)!!["figurasComFalha"].asInt())
        assertEquals(4, material(a, idA)!!["figurasComFalha"].asInt())
        assertEquals(404, chamar("GET", "/materiais/$idA/andamento", token = b.token).status)
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM trecho WHERE material_id = ?::uuid AND usuario_id = ?", Int::class.java, idA, b.id))
    }
}
