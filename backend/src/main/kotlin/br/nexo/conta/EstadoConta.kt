package br.nexo.conta

import org.springframework.http.HttpStatus

/** Estados de uma conta (coluna `status_conta`) e o erro devolvido a quem teve o acesso cortado pelo administrador. */
object EstadoConta {
    const val ATIVO = "ativo"
    const val SUSPENSO = "suspenso"

    /**
     * Erro de quem não pode entrar nem continuar usando o sistema.
     * @param status estado da conta.
     * @return erro 403 `conta_suspensa`; nulo se a conta está ativa.
     */
    fun erro(status: String): ErroNegocio? =
        if (status == SUSPENSO) ErroNegocio(HttpStatus.FORBIDDEN, "Esta conta está suspensa. Fale com o administrador.", "conta_suspensa") else null
}
