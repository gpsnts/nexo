package br.nexo.admin

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Corpo de `PUT /admin/usuarios/{id}/cota`.
 * @property creditos créditos por semana (0 a 100000; 0 = sem IA); nulo volta à cota padrão.
 */
data class CotaRequest(@field:Min(0, message = "A cota não pode ser negativa.") @field:Max(100000, message = "A cota máxima é 100000 créditos.") val creditos: Int?)

/**
 * Corpo de `DELETE /admin/usuarios/{id}`.
 * @property confirmacao deve ser a palavra EXCLUIR.
 */
data class ExclusaoRequest(val confirmacao: String = "")

/** Rotas do painel de administração (`/admin`). Quem não é administrador recebe 404 em todas. */
@RestController
@RequestMapping("/admin")
class AdminController(private val admin: AdminService) {
    /**
     * Lista as contas com estado e consumo.
     * @return uma linha por conta.
     */
    @GetMapping("/usuarios")
    fun listar() = admin.listar()

    /**
     * Suspende uma conta ativa.
     * @param id conta.
     * @return nada (204 sem corpo).
     */
    @PostMapping("/usuarios/{id}/suspender")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun suspender(@PathVariable id: UUID) = admin.suspender(id)

    /**
     * Reativa uma conta suspensa.
     * @param id conta.
     * @return nada (204 sem corpo).
     */
    @PostMapping("/usuarios/{id}/reativar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reativar(@PathVariable id: UUID) = admin.reativar(id)

    /**
     * Define a cota semanal de IA da conta.
     * @param id conta.
     * @param r nova cota (nulo = padrão).
     * @return nada (204 sem corpo).
     */
    @PutMapping("/usuarios/{id}/cota")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cota(@PathVariable id: UUID, @Valid @RequestBody r: CotaRequest) = admin.definirCota(id, r.creditos)

    /**
     * Exclui a conta e todos os dados dela.
     * @param id conta.
     * @param r confirmação digitada.
     * @return nada (204 sem corpo).
     */
    @DeleteMapping("/usuarios/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun excluir(@PathVariable id: UUID, @RequestBody r: ExclusaoRequest) = admin.excluir(id, r.confirmacao)
}
