package br.nexo.conta

import br.nexo.NexoProperties
import br.nexo.conta.TokenContaService.Companion.VERIFICACAO
import br.nexo.seguranca.Auditoria
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Verificação do e-mail no cadastro (ADR 0014): a conta nasce sem entrar. Quem registrou o e-mail de outra pessoa não consegue usá-lo,
 * e o dono de verdade recupera o endereço pelo "Esqueci minha senha" (que também confirma o e-mail).
 */
@Service
class VerificacaoService(
    private val usuarios: UsuarioRepository,
    private val tokens: TokenContaService,
    private val email: EnviadorEmail,
    private val segundoPlano: SegundoPlano,
    private val tx: TransactionTemplate,
    private val props: NexoProperties,
    private val auditoria: Auditoria,
) {
    /** Em segundo plano e com o mesmo limite por hora da recuperação de senha. */
    fun enviar(u: Usuario) = segundoPlano.executar {
        if (tokens.pedidosNaUltimaHora(u.id, VERIFICACAO) < props.conta.pedidosPorHora) {
            val bruto = tokens.criar(u.id, VERIFICACAO, null, props.conta.validadeConfirmacaoEmail)
            email.enviar(u.email, "Confirme seu e-mail no Nexo",
                "Olá, ${u.nome}.\n\nPara ativar sua conta, confirme este e-mail abrindo o link (vale por ${props.conta.validadeConfirmacaoEmail.toHours()} horas):\n" +
                    "${props.conta.urlFrontend}/verificar-email?token=$bruto\n\nSe não foi você quem criou a conta, ignore este e-mail.")
        }
    }

    /** Resposta sempre igual, exista ou não a conta, esteja ou não verificada. */
    fun reenviar(emailInformado: String) {
        val u = usuarios.findByEmail(normalizarEmail(emailInformado)) ?: return
        if (u.emailVerificadoEm == null) enviar(u)
    }

    fun verificar(bruto: String) = tx.executeWithoutResult {
        val t = tokens.validar(bruto, VERIFICACAO)
        val u = usuarios.findById(t.usuarioId).orElseThrow { TokenContaService.linkInvalido() }
        if (u.emailVerificadoEm == null) u.emailVerificadoEm = tokens.agora()
        t.usadoEm = tokens.agora()
        auditoria.registrar("email_verificado", u.id)
    }
}
