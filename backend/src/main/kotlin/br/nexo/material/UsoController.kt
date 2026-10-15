package br.nexo.material

import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Uso de IA do próprio usuário na janela dos limites (a tela mostra "x de 30 operações"). */
@RestController
class UsoController(private val limites: LimitesIa, private val tx: TransactionTemplate) {
    @GetMapping("/uso")
    fun uso(): Uso = tx.execute { limites.uso() }!!
}
