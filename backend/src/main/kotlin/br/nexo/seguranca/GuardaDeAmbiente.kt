package br.nexo.seguranca

import br.nexo.NexoProperties
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/** Em `nexo.ambiente=producao` a aplicação se recusa a subir com configuração que só serve para desenvolvimento (ADR 0017). */
@Component
class GuardaDeAmbiente(private val props: NexoProperties) {
    fun problemas(): List<String> {
        if (props.ambiente != "producao") return emptyList()
        return listOfNotNull(
            "nexo.email.provedor=falso (o link de redefinição iria para o log)".takeIf { props.email.provedor == "falso" },
            "nexo.conta.cookie-seguro=false".takeIf { !props.conta.cookieSeguro },
            "nexo.conta.url-frontend não usa https".takeIf { !props.conta.urlFrontend.startsWith("https://") },
            "nexo.conta.assincrono=false".takeIf { !props.conta.assincrono },
        )
    }

    @PostConstruct
    fun validar() {
        val p = problemas()
        check(p.isEmpty()) { "Configuração insegura para produção: ${p.joinToString("; ")}" }
    }
}
