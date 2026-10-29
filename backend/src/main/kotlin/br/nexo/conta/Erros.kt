package br.nexo.conta

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Erro de regra de negócio, traduzido em resposta HTTP pelo [TratadorErros].
 * @property status status HTTP da resposta.
 * @property message mensagem clara para o usuário.
 * @property codigo código curto estável para o frontend, se houver.
 * @property extras campos adicionais do corpo da resposta (ex.: tempo de espera).
 */
class ErroNegocio(val status: HttpStatus, override val message: String, val codigo: String? = null, val extras: Map<String, Any> = emptyMap()) : RuntimeException(message)

/** Traduz exceções em respostas JSON `{mensagem, codigo}` com o status certo, sem expor detalhes internos. */
@RestControllerAdvice
class TratadorErros {
    /**
     * Responde a um [ErroNegocio].
     * @param e erro de negócio lançado.
     * @return resposta com o status do erro e corpo `{mensagem, codigo?, ...extras}`.
     */
    @ExceptionHandler(ErroNegocio::class)
    fun negocio(e: ErroNegocio): ResponseEntity<Map<String, Any>> {
        val base: Map<String, Any> = listOfNotNull("mensagem" to e.message, e.codigo?.let { "codigo" to it }).toMap()
        return ResponseEntity.status(e.status).body(base + e.extras)
    }

    /**
     * Responde a falha de validação do corpo (Bean Validation).
     * @param e exceção com os campos inválidos.
     * @return 400 com a mensagem do primeiro campo inválido e código `dados_invalidos`.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validacao(e: MethodArgumentNotValidException) = ResponseEntity.badRequest()
        .body(mapOf("mensagem" to (e.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "Dados inválidos"), "codigo" to "dados_invalidos"))

    // Erros de formato também respondem com {mensagem}, no status certo (antes viravam 401 mudo pela rota /error).
    /**
     * Responde a corpo ilegível ou malformado.
     * @param e exceção de leitura do corpo.
     * @return 400 com código `corpo_invalido`.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun corpoInvalido(e: HttpMessageNotReadableException) = erro(HttpStatus.BAD_REQUEST, "Corpo da requisição inválido.", "corpo_invalido")

    /**
     * Responde a tipo de conteúdo não suportado.
     * @param e exceção do tipo de mídia.
     * @return 415 com código `formato_nao_suportado`.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun tipoNaoSuportado(e: HttpMediaTypeNotSupportedException) = erro(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Formato não suportado. Envie JSON.", "formato_nao_suportado")

    /**
     * Responde a método HTTP não permitido na rota.
     * @param e exceção do método.
     * @return 405 com código `metodo_nao_permitido`.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun metodoNaoPermitido(e: HttpRequestMethodNotSupportedException) = erro(HttpStatus.METHOD_NOT_ALLOWED, "Método não permitido.", "metodo_nao_permitido")

    /**
     * Responde a rota ou recurso estático inexistente.
     * @param e exceção de recurso não encontrado.
     * @return 404 com código `nao_encontrado`.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun naoEncontrado(e: NoResourceFoundException) = erro(HttpStatus.NOT_FOUND, "Não encontrado.", "nao_encontrado")

    // Parâmetro ou parte ausente/malformada: 400 no mesmo formato {mensagem, codigo} (antes: 500 sem `arquivo` e corpo padrão do Spring no UUID inválido).
    @ExceptionHandler(
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class,
        org.springframework.web.bind.MissingServletRequestParameterException::class,
        org.springframework.web.multipart.support.MissingServletRequestPartException::class,
        org.springframework.web.multipart.MultipartException::class,
    )
    /**
     * Responde a parâmetro ou parte ausente ou malformada (UUID inválido, multipart incompleto).
     * @param e exceção capturada.
     * @return 400 com código `dados_invalidos` (ou 413 se for arquivo grande demais).
     */
    fun parametroInvalido(e: Exception): ResponseEntity<Map<String, String>> {
        if (e is org.springframework.web.multipart.MaxUploadSizeExceededException) return arquivoGrande(e)
        return erro(HttpStatus.BAD_REQUEST, "Requisição inválida: confira os dados enviados.", "dados_invalidos")
    }

    /**
     * Responde a upload acima do limite do servidor.
     * @param e exceção de tamanho excedido.
     * @return 413 com código `arquivo_grande`.
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException::class)
    fun arquivoGrande(e: org.springframework.web.multipart.MaxUploadSizeExceededException) = erro(HttpStatus.CONTENT_TOO_LARGE, "O arquivo é grande demais.", "arquivo_grande")

    /**
     * Monta a resposta de erro padrão.
     * @param status status HTTP.
     * @param mensagem texto para o usuário.
     * @param codigo código curto estável.
     * @return resposta com corpo `{mensagem, codigo}`.
     */
    private fun erro(status: HttpStatus, mensagem: String, codigo: String) = ResponseEntity.status(status).body(mapOf("mensagem" to mensagem, "codigo" to codigo))
}
