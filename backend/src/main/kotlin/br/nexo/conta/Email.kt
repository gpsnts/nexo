package br.nexo.conta

import br.nexo.NexoProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component

/** Envio de e-mail atrás de uma interface, como as de IA: falso por padrão, SMTP por configuração. */
interface EnviadorEmail {
    /**
     * Envia um e-mail de texto simples.
     * @param para endereço do destinatário.
     * @param assunto assunto da mensagem.
     * @param texto corpo da mensagem.
     */
    fun enviar(para: String, assunto: String, texto: String)

    /**
     * Envia um e-mail com versão em HTML. Por padrão usa só o texto; o provedor SMTP envia as duas versões.
     * @param para endereço do destinatário.
     * @param assunto assunto da mensagem.
     * @param texto corpo em texto simples.
     * @param html corpo em HTML.
     * @return nada; o envio é feito pelo provedor.
     */
    fun enviar(para: String, assunto: String, texto: String, html: String) = enviar(para, assunto, texto)

    /**
     * Envia uma mensagem montada pelo [ModeloEmail].
     * @param para endereço do destinatário.
     * @param assunto assunto da mensagem.
     * @param mensagem texto e HTML prontos.
     * @return nada; o envio é feito pelo provedor.
     */
    fun enviar(para: String, assunto: String, mensagem: MensagemEmail) = enviar(para, assunto, mensagem.texto, mensagem.html)
}

/** Provedor falso de e-mail: só escreve a mensagem no log (desenvolvimento e testes). */
@Component
@ConditionalOnProperty("nexo.email.provedor", havingValue = "falso", matchIfMissing = true)
class EnviadorEmailLog : EnviadorEmail {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun enviar(para: String, assunto: String, texto: String) {
        log.info("E-mail (provedor falso) para {}: {}\n{}", para, assunto, texto)
    }
}

/** Provedor de e-mail por SMTP, usando o remetente configurado em `nexo.email`; envia texto e HTML na mesma mensagem. */
@Component
@ConditionalOnProperty("nexo.email.provedor", havingValue = "smtp")
class EnviadorEmailSmtp(private val remetente: JavaMailSender, private val props: NexoProperties) : EnviadorEmail {
    override fun enviar(para: String, assunto: String, texto: String) {
        remetente.send(SimpleMailMessage().also {
            it.from = props.email.remetente
            it.setTo(para)
            it.subject = assunto
            it.text = texto
        })
    }

    override fun enviar(para: String, assunto: String, texto: String, html: String) {
        val mensagem = remetente.createMimeMessage()
        MimeMessageHelper(mensagem, true, "UTF-8").also {
            it.setFrom(props.email.remetente)
            it.setTo(para)
            it.setSubject(assunto)
            it.setText(texto, html)
        }
        remetente.send(mensagem)
    }
}
