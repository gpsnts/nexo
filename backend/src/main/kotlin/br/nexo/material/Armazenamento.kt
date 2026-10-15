package br.nexo.material

import br.nexo.NexoProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Onde ficam os arquivos enviados. As chaves têm a forma `<usuario_id>/<material_id>` (nunca o nome original: sem path traversal). */
interface Armazenamento {
    fun salvar(chave: String, dados: ByteArray)
    fun ler(chave: String): ByteArray
    fun remover(chave: String)
    fun removerPasta(prefixo: String)
}

@Component
class ArmazenamentoLocal(props: NexoProperties) : Armazenamento {
    private val raiz: Path = Path.of(props.material.diretorio).toAbsolutePath().normalize()

    private fun caminho(chave: String): Path {
        val c = raiz.resolve(chave).normalize()
        require(c.startsWith(raiz) && c != raiz) { "Chave fora do armazenamento" }
        return c
    }

    override fun salvar(chave: String, dados: ByteArray) {
        val destino = caminho(chave)
        Files.createDirectories(destino.parent)
        val temp = Files.createTempFile(destino.parent, ".envio", ".tmp")
        try {
            Files.write(temp, dados)
            Files.move(temp, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun ler(chave: String): ByteArray = Files.readAllBytes(caminho(chave))

    override fun remover(chave: String) { Files.deleteIfExists(caminho(chave)) }

    override fun removerPasta(prefixo: String) {
        val pasta = caminho(prefixo)
        if (!Files.exists(pasta)) return
        Files.walk(pasta).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

/**
 * Remoção de arquivos só depois do commit (regras 6 e 7): se a transação desfizer, o arquivo continua. Falhou? Tenta de novo nos prazos de
 * `nexo.material.esperas-remocao` e, esgotados, registra erro com a chave.
 * shortcut: as novas tentativas vivem na memória; um arquivo cuja remoção esgotar as tentativas (ou perdido num reinício) vira órfão. Sem varredura, ADR 0024.
 */
@Component
class RemocaoArquivos(private val armazenamento: Armazenamento, props: NexoProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val esperas = props.material.esperasRemocao
    private val agenda = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "remocao-arquivos").apply { isDaemon = true } }

    /** [chaves] são arquivos; [pastas] são prefixos inteiros (exclusão da conta). Fora de transação, apaga na hora. */
    fun depoisDoCommit(chaves: List<String> = emptyList(), pastas: List<String> = emptyList()) {
        val acao = { chaves.forEach(armazenamento::remover); pastas.forEach(armazenamento::removerPasta) }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return tentar(acao, 0, chaves + pastas)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = tentar(acao, 0, chaves + pastas)
        })
    }

    private fun tentar(acao: () -> Unit, tentativa: Int, alvo: List<String>) {
        try {
            acao()
        } catch (e: Exception) {
            if (tentativa >= esperas.size) return log.error("Não foi possível remover {} (arquivo órfão): {}", alvo, e.toString())
            log.warn("Falha ao remover {} (tentativa {}): {}", alvo, tentativa + 1, e.toString())
            agenda.schedule({ tentar(acao, tentativa + 1, alvo) }, esperas[tentativa].toMillis(), TimeUnit.MILLISECONDS)
        }
    }
}
