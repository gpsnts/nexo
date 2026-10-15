package br.nexo.material

import java.util.UUID

/** Faixa de trechos de um tópico de um material. [id] nulo = tópico novo. */
data class FaixaTopico(val id: UUID?, val nome: String, val inicio: Int, val fim: Int)

/** Regra 12: a divisão cobre o material de 1 a N sem lacuna nem sobreposição. O banco só impede a sobreposição e trechos inexistentes. */
object Cobertura {
    const val NOME_MAX = 200

    /** Mensagem de erro (em português) ou nulo se a divisão é válida. */
    fun validar(faixas: List<FaixaTopico>, totalTrechos: Int, maxTopicos: Int): String? {
        if (faixas.isEmpty()) return "Informe ao menos um tópico."
        if (faixas.size > maxTopicos) return "No máximo $maxTopicos tópicos por material."
        var esperado = 1
        faixas.forEachIndexed { i, f ->
            val n = i + 1
            if (f.nome.isBlank()) return "O tópico $n precisa de um nome."
            if (f.nome.trim().length > NOME_MAX) return "O nome do tópico $n pode ter até $NOME_MAX caracteres."
            if (f.inicio < 1 || f.fim > totalTrechos || f.fim < f.inicio) return "O tópico $n tem limites inválidos (o material tem $totalTrechos trechos)."
            if (f.inicio > esperado) return "Faltam os trechos $esperado a ${f.inicio - 1}: o tópico $n começa depois do fim do anterior."
            if (f.inicio < esperado) return "O tópico $n se sobrepõe ao anterior."
            esperado = f.fim + 1
        }
        if (esperado != totalTrechos + 1) return "Faltam os trechos $esperado a $totalTrechos no fim do material."
        return null
    }

    /**
     * Tópicos pela ESTRUTURA do documento (sem IA): cada título do nível mais alto que gere pelo menos duas seções abre um tópico.
     * Seções com menos de [minimoTrechos] trechos se juntam à anterior (a primeira, à seguinte). Nulo se o documento não tem estrutura aproveitável
     * (TXT, PDF sem títulos, uma seção só ou seções demais).
     */
    fun porEstrutura(trechos: List<TrechoExtraido>, minimoTrechos: Int, maxTopicos: Int): List<TopicoProposto>? {
        if (trechos.isEmpty()) return null
        for (nivel in trechos.mapNotNull { it.nivel }.toSortedSet()) {
            val inicios = (listOf(1) + trechos.filter { (it.nivel ?: Int.MAX_VALUE) <= nivel }.map { it.ordem }).distinct().sorted()
            if (inicios.size < 2) continue
            val secoes = inicios.mapIndexed { i, ini -> Secao(ini, (inicios.getOrNull(i + 1) ?: (trechos.size + 1)) - 1, listOfNotNull(trechos[ini - 1].titulo?.trim()?.takeIf { it.isNotEmpty() })) }
            val juntas = juntarPequenas(secoes, minimoTrechos)
            if (juntas.size < 2) continue
            if (juntas.size > maxTopicos) return null
            return juntas.map { TopicoProposto(it.nome(), it.inicio, it.fim) }
        }
        return null
    }

    /** Seção do documento: faixa de trechos e os títulos que ela reúne (mais de um quando seções pequenas se juntaram). */
    private data class Secao(val inicio: Int, val fim: Int, val titulos: List<String>) {
        val tamanho get() = fim - inicio + 1

        /** Um título: ele. Vários: "primeiro a último", para o nome mostrar até onde o tópico vai. Sem título: "Introdução". */
        fun nome(): String = when (titulos.size) {
            0 -> "Introdução"
            1 -> titulos[0]
            else -> "${titulos.first()} a ${titulos.last()}"
        }.take(NOME_MAX)

        fun juntarCom(outra: Secao) = Secao(inicio, outra.fim, titulos + outra.titulos)
    }

    private fun juntarPequenas(secoes: List<Secao>, minimo: Int): List<Secao> {
        val r = mutableListOf<Secao>()
        secoes.forEach { s ->
            val anterior = r.lastOrNull()
            if (anterior != null && s.tamanho < minimo) r[r.size - 1] = anterior.juntarCom(s) else r += s
        }
        // a primeira seção pequena (sem anterior) junta-se à seguinte
        if (r.size >= 2 && r[0].tamanho < minimo) {
            val primeira = r.removeAt(0)
            r[0] = primeira.juntarCom(r[0])
        }
        return r
    }

    /** UC05 A1 / plano B: blocos de tamanho semelhante. [titulos] (por ordem do trecho, base 1 na posição 0) dá nome ao bloco quando o trecho inicial abre com título. */
    fun blocosIguais(total: Int, porTopico: Int, titulos: List<String?> = emptyList()): List<TopicoProposto> {
        val blocos = maxOf(1, (total + porTopico - 1) / porTopico)
        val base = total / blocos
        val resto = total % blocos
        var inicio = 1
        return (1..blocos).map { i ->
            val tamanho = base + if (i <= resto) 1 else 0
            val nome = titulos.getOrNull(inicio - 1)?.trim()?.takeIf { it.isNotEmpty() }?.take(NOME_MAX) ?: "Parte $i"
            TopicoProposto(nome, inicio, inicio + tamanho - 1).also { inicio += tamanho }
        }
    }
}
