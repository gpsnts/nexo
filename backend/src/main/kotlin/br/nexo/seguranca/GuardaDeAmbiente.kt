package br.nexo.seguranca

import br.nexo.NexoProperties
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Em `nexo.ambiente=producao` a aplicação se recusa a subir com configuração que só serve para desenvolvimento.
 *
 * @property props parâmetros da aplicação.
 * @property senhaBanco senha com que o backend conecta ao banco (`spring.datasource.password`).
 */
@Component
class GuardaDeAmbiente(
    private val props: NexoProperties,
    @Value("\${spring.datasource.password:}") private val senhaBanco: String = "",
) {
    /**
     * Lista as configurações inseguras para produção.
     * @return mensagens dos problemas; vazia fora de produção ou se tudo estiver certo.
     */
    fun problemas(): List<String> {
        if (props.ambiente != "producao") return emptyList()
        return listOfNotNull(
            "nexo.email.provedor=falso (o link de redefinição iria para o log)".takeIf { props.email.provedor == "falso" },
            "nexo.conta.cookie-seguro=false".takeIf { !props.conta.cookieSeguro },
            "nexo.conta.url-frontend não usa https".takeIf { !props.conta.urlFrontend.startsWith("https://") },
            "nexo.conta.assincrono=false".takeIf { !props.conta.assincrono },
            "nexo.material.assincrono=false".takeIf { !props.material.assincrono },
            "nexo.geracao.assincrono=false".takeIf { !props.geracao.assincrono },
            "nexo.processamento.segredo com menos de 24 caracteres".takeIf { props.processamento.segredo.length < 24 },
            // Os valores de exemplo do .env.example começam com "troque": copiar o arquivo sem trocar os segredos não pode ir a produção.
            "segredo do JWT ou interno ainda é o valor de exemplo do .env.example".takeIf { props.jwt.segredo.startsWith("troque") || props.processamento.segredo.startsWith("troque") },
            "senha do banco do backend com menos de 16 caracteres ou ainda é o valor de exemplo".takeIf { senhaBanco.length < 16 || senhaBanco.startsWith("troque") },
        )
    }

    /**
     * Impede a subida da aplicação em produção com configuração só de desenvolvimento.
     * @throws IllegalStateException se [problemas] não estiver vazia.
     */
    @PostConstruct
    fun validar() {
        val p = problemas()
        check(p.isEmpty()) { "Configuração insegura para produção: ${p.joinToString("; ")}" }
    }
}
