package br.nexo.material

/** Mensagem para o usuário a partir do código da falha do serviço de IA (o código vem do provedor, via serviço Python). Sem código conhecido: a mensagem genérica. */
object MensagensIa {
    const val GENERICA = "O modelo de linguagem não respondeu. Tente novamente em instantes."

    fun deCodigo(codigo: String?): String = when (codigo) {
        "limite_provedor" -> "O provedor de IA atingiu o limite de requisições da conta (comum em plano gratuito). Aguarde cerca de um minuto e tente de novo."
        "sem_credito" -> "A conta do provedor de IA está sem crédito ou sem cota. Confira o plano e o faturamento no site do provedor; um plano gratuito pode não incluir uso da API."
        "chave_recusada" -> "O provedor de IA recusou a chave de API. Confira a chave (OPENAI_API_KEY ou GEMINI_API_KEY) no .env."
        "modelo_inexistente" -> "O modelo de IA configurado não existe ou não está disponível nesta conta. Confira MODELO_LINGUAGEM_NOME e MODELO_EMBEDDING_NOME no .env."
        "resposta_cortada" -> "A resposta do modelo foi cortada pelo limite de tokens. Aumente MODELO_LINGUAGEM_MAX_TOKENS."
        "preco_ausente" -> "O preço do modelo de IA não está configurado: defina PRECO_LINGUAGEM_ENTRADA_USD_POR_MILHAO e PRECO_LINGUAGEM_SAIDA_USD_POR_MILHAO (modelo de linguagem) ou PRECO_EMBEDDING_USD_POR_MILHAO (embedding) no .env; em plano gratuito use 0."
        "modelo_falso" -> "O serviço de IA está com provedor falso em produção."
        else -> GENERICA
    }
}
