package br.nexo

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Proteção por hora e cota semanal de créditos, contadas em registro_geracao e conferidas antes de cada operação com IA. */
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
           VALUES (?,?,'resumo','topico_livre','m',?,0,0,?::numeric,0,0,0,?,CASE WHEN ? IN ('concluida','falhou') THEN now() END,now() - make_interval(mins => ?::int))""",
        UUID.randomUUID(), c.id, status, custo, if (status == "concluida") 100 else 0, status, minutosAtras,
    )

    private fun encher(c: Conta, n: Int, minutosAtras: Long = 1, status: String = "concluida") = repeat(n) { registro(c, minutosAtras, status = status) }

    private fun pdf() = "%PDF-1.4 PAGINAS=2 TRECHOS=3 nonce=${UUID.randomUUID()}".toByteArray()
    private fun enviar(c: Conta) = enviarArquivo(c.token, "a.pdf", pdf())
    private fun uso(c: Conta) = chamar("GET", "/uso", token = c.token).corpo
    private fun materiais(c: Conta) = dono.queryForObject("SELECT count(*) FROM material WHERE usuario_id = ?", Int::class.java, c.id)!!

    @org.springframework.beans.factory.annotation.Autowired lateinit var limites: br.nexo.material.LimitesIa
    @org.springframework.beans.factory.annotation.Autowired lateinit var transacao: org.springframework.transaction.support.TransactionTemplate

    @AfterEach fun restaurar() { python.propostaTopicos = "[]" }

    /**
     * Conferir o limite e abrir o registro na mesma transação só é seguro com várias conexões se a conferência travar o usuário (read committed não vê o registro
     * ainda não confirmado de outra transação). O pool de 1 conexão dos testes esconde a corrida, então o teste confere a trava em si.
     */
    @Test
    fun `exigirDisponivel trava o usuario ate o fim da transacao e a trava e solta no commit`() {
        val c = conta()
        val travas = "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = pg_backend_pid()"
        val dentro = br.nexo.seguranca.UsuarioAtual.como(c.id) { transacao.execute { limites.exigirDisponivel(); jdbc.queryForObject(travas, Int::class.java) } }
        assertEquals(1, dentro, "a conferência segura a trava consultiva do usuário")
        assertEquals(0, transacao.execute { jdbc.queryForObject(travas, Int::class.java) }, "fora da transação não sobra trava")
    }

    @Test
    fun `operacao aberta ja reserva o minimo da cota e operacao que falhou nao`() {
        val c = conta()
        registro(c, 3, status = "em_andamento")   // aberta: reserva 1
        registro(c, 3, status = "pendente")       // aberta: reserva 1
        registro(c, 3, status = "falhou")         // falhou sem gastar: 0
        assertEquals(2, uso(c)["creditosUsados"].asInt())
    }

    @Test
    fun `uso conta os registros da janela e comeca zerado`() {
        val c = conta()
        assertEquals(0, uso(c)["operacoes"].asInt()); assertEquals(30, uso(c)["limiteOperacoes"].asInt()); assertEquals(60, uso(c)["janelaOperacoesMinutos"].asLong().toInt())
        assertEquals(300, uso(c)["creditosLimite"].asInt()); assertEquals(0, uso(c)["creditosUsados"].asInt()); assertEquals(0, uso(c)["percentual"].asInt())
        assertEquals(202, enviar(c).status)
        assertEquals(2, uso(c)["operacoes"].asInt(), "indexação e sugestão de tópicos são duas operações com IA")
        assertEquals(2, uso(c)["creditosUsados"].asInt(), "o provedor falso custa zero, mas cada operação concluída vale ao menos 1 crédito")
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
        assertTrue("o limite é 30 por hora" in envio.corpo["mensagem"].asString(), envio.corpo["mensagem"].asString())
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
    fun `cota semanal esgotada bloqueia ate a renovacao e diz quando volta, sem dolar na mensagem`() {
        val c = conta()
        registro(c, minutosAtras = 5, custo = "0.03")       // 0,03 / 0,0001 = 300 créditos = a cota inteira
        val r = enviar(c)
        assertEquals(429, r.status); assertEquals("limite_creditos", r.corpo["codigo"].asString())
        val mensagem = r.corpo["mensagem"].asString()
        assertTrue("cota de IA desta semana" in mensagem && "renova" in mensagem && "US$" !in mensagem, mensagem)
        assertTrue(r.corpo["esperaSegundos"].asLong() in 1..7 * 86400L, r.corpo["esperaSegundos"].toString())
        val u = uso(c)
        assertEquals(300, u["creditosUsados"].asInt()); assertEquals(100, u["percentual"].asInt()); assertTrue(u["renovaEm"].asString().isNotBlank())
        assertEquals(false, u.has("gastoUsd") || u.has("limiteGastoUsd"), "o dólar não sai do servidor")
        assertEquals(0, materiais(c))
    }

    @Test
    fun `abaixo da cota a operacao segue e consumo de semanas anteriores nao conta`() {
        val c = conta()
        registro(c, minutosAtras = 24, custo = "0.0299")    // 299 créditos < 300
        assertEquals(202, enviar(c).status)
        val d = conta()
        registro(d, minutosAtras = 8 * 24 * 60, custo = "5.0")   // há 8 dias: semana anterior
        assertEquals(202, enviar(d).status)
        assertEquals(2, uso(d)["creditosUsados"].asInt())
    }

    @Test
    fun `concluida custa ao menos o minimo, falha custa so o que gastou e o custo arredonda para cima`() {
        val c = conta()
        registro(c, 3, custo = "0", status = "concluida")      // 1 (mínimo)
        registro(c, 3, custo = "0", status = "falhou")         // 0
        registro(c, 3, custo = "0.00031", status = "concluida") // 3,1 -> 4
        registro(c, 3, custo = "0.00011", status = "falhou")    // 1,1 -> 2 (gastou algo antes de falhar)
        assertEquals(7, uso(c)["creditosUsados"].asInt())
        assertEquals(2, uso(c)["percentual"].asInt())           // 7 de 300
    }

    @Test
    fun `o limite e por conta e o uso de uma nao aparece na outra`() {
        val a = conta(); val b = conta()
        encher(b, 30); registro(b, 10, custo = "99.0")
        assertEquals(429, enviar(b).status)
        assertEquals(202, enviar(a).status, "o limite de B não afeta A")
        assertEquals(2, uso(a)["operacoes"].asInt()); assertEquals(2, uso(a)["creditosUsados"].asInt())
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
