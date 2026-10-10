package br.nexo.conta

import br.nexo.NexoProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

/** Envio de e-mail atrás de uma interface, como as de IA (ADR 0011): falso por padrão, SMTP por configuração. */
interface EnviadorEmail {
    fun enviar(para: String, assunto: String, texto: String)
}

@Component
@ConditionalOnProperty("nexo.email.provedor", havingValue = "falso", matchIfMissing = true)
class EnviadorEmailLog : EnviadorEmail {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun enviar(para: String, assunto: String, texto: String) {
        log.info("E-mail (provedor falso) para {}: {}\n{}", para, assunto, texto)
    }
}

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
}
