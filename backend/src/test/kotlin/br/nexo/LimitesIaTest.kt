package br.nexo

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Limite de operações por hora e de gasto por janela, contados em registro_geracao, conferidos antes de cada operação com IA (ADR 0033). */
class LimitesIaTest : BaseIntegracao() {
    class Conta(val token: String, val id: UUID)

    private fun conta(): Conta {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        return Conta(token, dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!)
    }

    /** Registro de operação com IA criado [minutosAtras] minutos atrás (papel dono: ignora a RLS). */
    private fun registro(c: Conta, minutosAtras: Long, custo: String = "0", status: String = "concluida") = dono.update(
        """INSERT INTO registro_geracao(id,usuario_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual,finalizada_em,criado_em)
           VALUES (?,?,'resumo','topico_livre','m',?,0,0,?::numeric,0,0,0,?,now(),now() - make_interval(mins => ?::int))""",
        UUID.randomUUID(), c.id, status, custo, if (status == "concluida") 100 else 0, minutosAtras,
    )

    private fun encher(c: Conta, n: Int, minutosAtras: Long = 1, status: String = "concluida") = repeat(n) { registro(c, minutosAtras, status = status) }

    private fun pdf() = "%PDF-1.4 PAGINAS=2 TRECHOS=3 nonce=${UUID.randomUUID()}".toByteArray()
    private fun enviar(c: Conta) = enviarArquivo(c.token, "a.pdf", pdf())
    private fun uso(c: Conta) = chamar("GET", "/uso", token = c.token).corpo
    private fun materiais(c: Conta) = dono.queryForObject("SELECT count(*) FROM material WHERE usuario_id = ?", Int::class.java, c.id)!!

    @AfterEach fun restaurar() { python.propostaTopicos = "[]" }

    @Test
    fun `uso conta os registros da janela e comeca zerado`() {
        val c = conta()
        assertEquals(0, uso(c)["operacoes"].asInt()); assertEquals(30, uso(c)["limiteOperacoes"].asInt()); assertEquals(60, uso(c)["janelaOperacoesMinutos"].asLong().toInt())
        assertEquals(10.0, uso(c)["limiteGastoUsd"].asDouble()); assertEquals(30, uso(c)["janelaGastoDias"].asInt())
        assertEquals(202, enviar(c).status)
        assertEquals(2, uso(c)["operacoes"].asInt(), "indexação e sugestão de tópicos são duas operações com IA")
    }

    @Test
    fun `ao atingir o limite por hora o envio e a reorganizacao sao recusados com o prazo, sem gastar nada`() {
        val c = conta()
        val id = enviar(c).corpo["materialId"].asString()          // material pronto, para a reorganização ter o que reorganizar
        encher(c, 28)                                                // 2 do envio + 28 = 30
        val chamadasAntes = python.chamadas.size
        val materiaisAntes = materiais(c)
        val registrosAntes = dono.queryForObject("SELECT count(*) FROM registro_geracao WHERE usuario_id = ?", Int::class.java, c.id)

        val envio = enviar(c)
        assertEquals(429, envio.status); assertEquals("limite_operacoes", envio.corpo["codigo"].asString())
        assertTrue(envio.corpo["esperaSegundos"].asLong() in 1..3600, envio.corpo.toString())
        assertTrue("30 operações com IA por hora" in envio.corpo["mensagem"].asString(), envio.corpo["mensagem"].asString())
        val reorg = chamar("POST", "/materiais/$id/topicos/reorganizar", token = c.token)
        assertEquals(429, reorg.status); assertEquals("limite_operacoes", reorg.corpo["codigo"].asString())

        assertEquals(materiaisAntes, materiais(c), "o envio recusado não grava material")
        assertEquals(registrosAntes, dono.queryForObject("SELECT count(*) FROM registro_geracao WHERE usuario_id = ?", Int::class.java, c.id), "recusado não cria registro")
        assertEquals(chamadasAntes, python.chamadas.size, "o serviço de processamento nem foi chamado")
        assertEquals(30, uso(c)["operacoes"].asInt())
    }

    @Test
    fun `o prazo e o tempo que falta para a operacao mais antiga sair da janela`() {
        val c = conta()
        registro(c, 55); encher(c, 29, minutosAtras = 1)   // a mais antiga tem 55 min: sai da janela de 60 min em cerca de 5 min
        val r = enviar(c)
        assertEquals(429, r.status)
        assertTrue(r.corpo["esperaSegundos"].asLong() in 240..300, r.corpo["esperaSegundos"].toString())
        assertTrue("5 minutos" in r.corpo["mensagem"].asString(), r.corpo["mensagem"].asString())
    }

    @Test
    fun `registros fora da janela nao contam e os que falharam contam`() {
        val c = conta()
        encher(c, 40, minutosAtras = 61)                      // todos de mais de 1 hora atrás
        assertEquals(202, enviar(c).status)
        val d = conta()
        encher(d, 30, status = "falhou")                      // falha também gasta uma operação
        assertEquals(429, enviar(d).status)
    }

    @Test
    fun `limite de gasto bloqueia ate o gasto antigo sair da janela`() {
        val c = conta()
        registro(c, minutosAtras = 2 * 24 * 60, custo = "6.0"); registro(c, minutosAtras = 24 * 60, custo = "5.0")   // 11 >= 10
        val r = enviar(c)
        assertEquals(429, r.status); assertEquals("limite_gasto", r.corpo["codigo"].asString())
        // tirar o gasto mais antigo (6.0) basta para voltar abaixo de 10: espera = tempo até ele completar 30 dias (28 dias)
        val dias = r.corpo["esperaSegundos"].asLong() / 86400.0
        assertTrue(dias in 27.9..28.0, "esperaSegundos = ${r.corpo["esperaSegundos"]}")
        assertTrue("US$ 10,00 em 30 dias" in r.corpo["mensagem"].asString() && "bloqueadas até" in r.corpo["mensagem"].asString(), r.corpo["mensagem"].asString())
        assertEquals(11.0, uso(c)["gastoUsd"].asDouble())
        assertEquals(0, materiais(c))
    }

    @Test
    fun `abaixo do limite de gasto ou com gasto antigo demais a operacao segue`() {
        val c = conta()
        registro(c, minutosAtras = 24 * 60, custo = "6.0"); registro(c, minutosAtras = 60, custo = "3.99")   // 9,99 < 10
        assertEquals(202, enviar(c).status)
        val d = conta()
        registro(d, minutosAtras = 31 * 24 * 60, custo = "50.0")                                             // fora dos 30 dias
        assertEquals(202, enviar(d).status)
        assertEquals(0.0, uso(d)["gastoUsd"].asDouble())
    }

    @Test
    fun `o limite e por conta e o uso de uma nao aparece na outra`() {
        val a = conta(); val b = conta()
        encher(b, 30); registro(b, 10, custo = "99.0")
        assertEquals(429, enviar(b).status)
        assertEquals(202, enviar(a).status, "o limite de B não afeta A")
        assertEquals(2, uso(a)["operacoes"].asInt()); assertEquals(0.0, uso(a)["gastoUsd"].asDouble())
        assertEquals(31, uso(b)["operacoes"].asInt())
        assertEquals(401, chamar("GET", "/uso").status)
    }

    @Test
    fun `arquivo repetido avisa do repetido antes do limite e nao sobra arquivo no disco`() {
        val c = conta()
        val bytes = pdf()
        assertEquals(202, enviarArquivo(c.token, "a.pdf", bytes).status)
        encher(c, 30)
        assertEquals("material_repetido", enviarArquivo(c.token, "a.pdf", bytes).corpo["codigo"].asString())
        assertEquals("limite_operacoes", enviar(c).corpo["codigo"].asString())
        assertEquals(1L, Files.list(arquivos.resolve(c.id.toString())).use { it.count() }, "só o arquivo do material aceito")
    }
}
