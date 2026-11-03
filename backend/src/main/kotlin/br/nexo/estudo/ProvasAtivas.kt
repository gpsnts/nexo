package br.nexo.estudo

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Provas que este servidor ainda vai montar ou está montando (na fila ou em execução). Serve para a recuperação não dar por
 * abandonada uma prova que só está esperando a vez, e para a montagem confirmar que a prova continua valendo antes de gastar cota.
 */
@Component
class ProvasAtivas {
    private val ids: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * Marca uma prova como em montagem neste servidor.
     * @param id prova.
     */
    fun marcar(id: UUID) { ids += id }

    /**
     * Tira a marca quando a montagem termina (ou é descartada).
     * @param id prova.
     */
    fun soltar(id: UUID) { ids -= id }

    /**
     * Provas marcadas agora.
     * @return os ids, numa cópia.
     */
    fun todas(): List<UUID> = ids.toList()
}
