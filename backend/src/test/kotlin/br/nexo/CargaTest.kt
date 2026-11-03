package br.nexo

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Carga com vários usuários ao mesmo tempo, num ambiente isolado (banco de teste e serviço de IA falso, sem custo de IA): cada usuário
 * percorre o dia a dia de estudo (listas, painel, revisão, tópico, resposta, prova do banco, exportação) e se mede a latência por rota.
 * Só roda sob pedido (`-PincluirCarga`). O gerador de carga, o backend e o banco dividem a mesma máquina, então os números valem como
 * comparação entre cargas, não como a capacidade de um servidor dedicado.
 */
@Tag("carga")
class CargaTest : BaseIntegracao() {
    companion object {
        /** Pool de conexões do tamanho padrão do Hikari (a base de testes usa 1 para expor vazamento de contexto, o que não vale numa carga). */
        @JvmStatic
        @DynamicPropertySource
        fun poolDeVerdade(r: DynamicPropertyRegistry) { r.add("spring.datasource.hikari.maximum-pool-size") { "10" } }
    }

    private class Usuario(val token: String, val id: UUID, val topicos: List<String>, val questoes: List<String>)
    private class Amostra(val rota: String, val ms: Long, val status: Int)

    /** Conta com 5 tópicos de 60 questões e 200 respostas já dadas, preparada direto no banco (o cadastro, com Argon2, é lento de propósito). */
    private fun preparar(): Usuario {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        val id = dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!
        val assunto = dono.queryForObject("SELECT id FROM assunto WHERE usuario_id = ? LIMIT 1", UUID::class.java, id)!!
        val topicos = (1..5).map { n ->
            val t = UUID.randomUUID()
            dono.update("INSERT INTO topico(id,usuario_id,assunto_id,nome,ordem) VALUES (?,?,?,?,?)", t, id, assunto, "Tópico $n", n)
            dono.update(
                """INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding)
                   SELECT gen_random_uuid(), ?, ?, 'Enunciado ' || g || ' do tópico $n, com texto de tamanho realista para uma pergunta de estudo?', '["a","b","c","d"]'::jsonb, 1,
                          md5(g::text || ?) || md5(g::text), array_fill(0.01::real, ARRAY[1536])::vector, 'm' FROM generate_series(1, 60) g""", id, t, t.toString(),
            )
            t.toString()
        }
        dono.update(
            "INSERT INTO resposta(id,usuario_id,questao_id,alternativa_escolhida,correta,respondida_em) SELECT gen_random_uuid(), usuario_id, id, 1, true, now() - (random() * interval '40 days') FROM (SELECT * FROM questao WHERE usuario_id = ? LIMIT 200) q", id,
        )
        val questoes = dono.queryForList("SELECT id::text FROM questao WHERE usuario_id = ?", String::class.java, id).filterNotNull()
        return Usuario(token, id, topicos, questoes)
    }

    /** Uma volta do dia a dia de um usuário; cada chamada vira uma amostra. */
    private fun volta(u: Usuario, n: Int, amostras: MutableList<Amostra>) {
        fun med(rota: String, f: () -> Resposta): Resposta { val t = System.nanoTime(); val r = f(); amostras += Amostra(rota, (System.nanoTime() - t) / 1_000_000, r.status); return r }
        val aleatorio = Random(u.id.hashCode() + n)
        med("GET /assuntos") { chamar("GET", "/assuntos", token = u.token) }
        med("GET /materiais") { chamar("GET", "/materiais?limite=100", token = u.token) }
        med("GET /revisao (resumo)") { chamar("GET", "/revisao?resumo=true", token = u.token) }
        med("GET /revisao (sessão)") { chamar("GET", "/revisao", token = u.token) }
        med("GET /desempenho") { chamar("GET", "/desempenho", token = u.token) }
        val t = u.topicos[aleatorio.nextInt(u.topicos.size)]
        med("GET /topicos/{id}") { chamar("GET", "/topicos/$t", token = u.token) }
        med("GET /topicos/{id}/questoes") { chamar("GET", "/topicos/$t/questoes", token = u.token) }
        med("POST /questoes/{id}/respostas") { chamar("POST", "/questoes/${u.questoes[aleatorio.nextInt(u.questoes.size)]}/respostas", """{"alternativa":${aleatorio.nextInt(4)}}""", u.token) }
        val a = chamar("GET", "/assuntos", token = u.token).corpo[0]["id"].asString()
        val prova = med("POST /provas (do banco)") { chamar("POST", "/provas", """{"assuntos":[{"assuntoId":"$a"}],"quantidade":10}""", u.token) }
        med("GET /provas") { chamar("GET", "/provas", token = u.token) }
        if (prova.status == 201) med("GET /provas/{id}") { chamar("GET", "/provas/${prova.corpo["provas"][0]["id"].asString()}", token = u.token) }
        med("GET /uso") { chamar("GET", "/uso", token = u.token) }
        if (n % 5 == 0) med("GET /exportacao (300 questões, JSON)") { chamar("GET", "/exportacao?conteudo=questoes&formato=json", token = u.token) }
    }

    private fun percentil(v: List<Long>, p: Double): Long = v.sorted().let { it[((it.size - 1) * p).toInt()] }

    @Test
    fun `carga com 10, 30 e 50 usuarios simultaneos`() {
        val voltas = 10
        val usuarios = (1..50).map { preparar() }
        println("CARGA preparados 50 usuários (5 tópicos x 60 questões e 200 respostas cada)")
        for (n in listOf(10, 30, 50)) {
            val amostras = CopyOnWriteArrayList<Amostra>()
            val pool = Executors.newFixedThreadPool(n)
            val largada = CountDownLatch(1); val prontos = CountDownLatch(n)
            val erros = AtomicInteger(0)
            val inicio = System.nanoTime()
            usuarios.take(n).forEach { u ->
                pool.execute { prontos.countDown(); largada.await(); try { repeat(voltas) { volta(u, it, amostras) } } catch (e: Exception) { erros.incrementAndGet(); println("CARGA exceção de um usuário: $e") } }
            }
            prontos.await(); largada.countDown(); pool.shutdown(); pool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES)
            val seg = (System.nanoTime() - inicio) / 1e9
            val todas = amostras.map { it.ms }
            val falhas = amostras.count { it.status !in 200..299 }
            println("CARGA ${n} usuários x $voltas voltas: ${amostras.size} chamadas em ${"%.1f".format(seg)} s (${"%.0f".format(amostras.size / seg)} chamadas/s); falhas HTTP $falhas; exceções $erros; p50 ${percentil(todas, 0.5)} ms, p95 ${percentil(todas, 0.95)} ms, p99 ${percentil(todas, 0.99)} ms, máx ${todas.max()} ms")
            amostras.groupBy { it.rota }.toSortedMap().forEach { (rota, v) ->
                val ms = v.map { it.ms }
                println("CARGA   $n | ${rota.padEnd(38)} n=${ms.size.toString().padStart(4)}  p50 ${percentil(ms, 0.5).toString().padStart(5)} ms  p95 ${percentil(ms, 0.95).toString().padStart(5)} ms  máx ${ms.max().toString().padStart(5)} ms${if (v.any { it.status !in 200..299 }) "  (status: ${v.groupBy { it.status }.mapValues { it.value.size }})" else ""}")
            }
            assertEquals(0, erros.get(), "nenhum usuário pode cair com exceção")
            assertTrue(percentil(todas, 0.95) < 3000, "p95 acima de 3 s com $n usuários: ${percentil(todas, 0.95)} ms")
        }
    }

    /** Acrescenta [n] contas "paradas" direto no banco (sem login), cada uma com 5 tópicos de 60 questões e 200 respostas: o banco cresce como numa implantação com muitos usuários. */
    private fun encherBanco(n: Int) {
        dono.update(
            """INSERT INTO usuario(id,nome,email,senha_hash,email_verificado_em)
               SELECT gen_random_uuid(), 'Parado ' || g, 'parado' || g || '-' || gen_random_uuid() || '@x.com', 'hash-que-nunca-loga', now() FROM generate_series(1, ?) g""", n,
        )
        dono.execute(
            """INSERT INTO assunto(id,usuario_id,nome,assunto_padrao) SELECT gen_random_uuid(), u.id, 'Geral', true FROM usuario u WHERE u.nome LIKE 'Parado %'""",
        )
        dono.execute(
            """INSERT INTO topico(id,usuario_id,assunto_id,nome,ordem)
               SELECT gen_random_uuid(), a.usuario_id, a.id, 'Tópico ' || o, o FROM assunto a JOIN usuario u ON u.id = a.usuario_id, generate_series(1, 5) o WHERE u.nome LIKE 'Parado %'""",
        )
        dono.execute(
            """INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding)
               SELECT gen_random_uuid(), t.usuario_id, t.id, 'Enunciado ' || g || ' do ' || t.nome, '["a","b","c","d"]'::jsonb, 1,
                      md5(g::text || t.id::text) || md5(g::text), array_fill(0.01::real, ARRAY[1536])::vector, 'm'
               FROM topico t JOIN usuario u ON u.id = t.usuario_id, generate_series(1, 60) g WHERE u.nome LIKE 'Parado %'""",
        )
        dono.execute(
            """INSERT INTO resposta(id,usuario_id,questao_id,alternativa_escolhida,correta,respondida_em)
               SELECT gen_random_uuid(), q.usuario_id, q.id, 1, (random() > 0.3), now() - (random() * interval '40 days')
               FROM (SELECT q.*, row_number() OVER (PARTITION BY q.usuario_id ORDER BY q.id) AS r FROM questao q JOIN usuario u ON u.id = q.usuario_id WHERE u.nome LIKE 'Parado %') q WHERE q.r <= 200""",
        )
        dono.execute("ANALYZE")
        println("CARGA banco com ${dono.queryForObject("SELECT count(*) FROM usuario", Int::class.java)} contas, ${dono.queryForObject("SELECT count(*) FROM questao", Int::class.java)} questões e ${dono.queryForObject("SELECT count(*) FROM resposta", Int::class.java)} respostas")
    }

    @Test
    fun `50 usuarios simultaneos num banco com 500 contas`() {
        val usuarios = (1..50).map { preparar() }
        encherBanco(450)
        val amostras = CopyOnWriteArrayList<Amostra>()
        val pool = Executors.newFixedThreadPool(50)
        val largada = CountDownLatch(1); val prontos = CountDownLatch(50)
        val erros = AtomicInteger(0)
        val inicio = System.nanoTime()
        usuarios.forEach { u -> pool.execute { prontos.countDown(); largada.await(); try { repeat(10) { volta(u, it, amostras) } } catch (e: Exception) { erros.incrementAndGet(); println("CARGA exceção de um usuário: $e") } } }
        prontos.await(); largada.countDown(); pool.shutdown(); pool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES)
        val seg = (System.nanoTime() - inicio) / 1e9
        val todas = amostras.map { it.ms }
        println("CARGA GRANDE 50 usuários ativos em banco de 500 contas: ${amostras.size} chamadas em ${"%.1f".format(seg)} s (${"%.0f".format(amostras.size / seg)} chamadas/s); falhas HTTP ${amostras.count { it.status !in 200..299 }}; exceções $erros; p50 ${percentil(todas, 0.5)} ms, p95 ${percentil(todas, 0.95)} ms, p99 ${percentil(todas, 0.99)} ms, máx ${todas.max()} ms")
        amostras.groupBy { it.rota }.toSortedMap().forEach { (rota, v) -> val ms = v.map { it.ms }; println("CARGA GRANDE   | ${rota.padEnd(38)} p50 ${percentil(ms, 0.5).toString().padStart(5)} ms  p95 ${percentil(ms, 0.95).toString().padStart(5)} ms  máx ${ms.max().toString().padStart(5)} ms") }
        assertEquals(0, erros.get())
        assertTrue(percentil(todas, 0.95) < 3000, "p95 acima de 3 s: ${percentil(todas, 0.95)} ms")
    }
}
