package br.nexo

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Executor de verdade (thread própria, sem usuário no SecurityContext): a indexação precisa gravar como o dono do material. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["nexo.conta.assincrono=false", "nexo.material.assincrono=true", "nexo.limite-publico.requisicoes=100000"],
)
@org.springframework.context.annotation.Import(EmailTesteConfig::class)
class IndexacaoAssincronaTest : BaseIntegracao() {
    @Test
    fun `indexacao em segundo plano grava com o usuario certo e a barra sobe ate 100`() {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        val uid = dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!
        val r = enviarArquivo(token, "a.pdf", "%PDF-1.4 PAGINAS=2 TRECHOS=130 ${UUID.randomUUID()}".toByteArray())
        assertEquals(202, r.status)
        val id = r.corpo["materialId"].asString()

        val vistos = mutableListOf<Int>()
        val limite = System.currentTimeMillis() + 15_000
        while (true) {
            val a = chamar("GET", "/materiais/$id/andamento", token = token).corpo
            vistos += a["percentual"].asInt()
            if (a["status"].asString() == "pronto") break
            check(a["status"].asString() != "falhou") { "falhou: ${a["motivoFalha"]}" }
            check(System.currentTimeMillis() < limite) { "não terminou em 15 s: $vistos" }
            Thread.sleep(10)
        }
        assertEquals(100, vistos.last())
        assertTrue(vistos.zipWithNext().all { (x, y) -> y >= x }, "o percentual nunca desce: $vistos")
        assertEquals(130, dono.queryForObject("SELECT count(*) FROM trecho WHERE material_id = ?::uuid AND usuario_id = ?", Int::class.java, id, uid))
        assertEquals(404, chamar("GET", "/materiais/$id/andamento", token = cadastrar().corpo["token"].asString()).status)
    }
}
