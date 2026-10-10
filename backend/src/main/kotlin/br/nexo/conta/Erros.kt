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

class ErroNegocio(val status: HttpStatus, override val message: String, val codigo: String? = null) : RuntimeException(message)

@RestControllerAdvice
class TratadorErros {
    @ExceptionHandler(ErroNegocio::class)
    fun negocio(e: ErroNegocio) = ResponseEntity.status(e.status).body(listOfNotNull("mensagem" to e.message, e.codigo?.let { "codigo" to it }).toMap())

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validacao(e: MethodArgumentNotValidException) = ResponseEntity.badRequest()
        .body(mapOf("mensagem" to (e.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "Dados inválidos"), "codigo" to "dados_invalidos"))

    // Erros de formato também respondem com {mensagem}, no status certo (antes viravam 401 mudo pela rota /error).
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun corpoInvalido(e: HttpMessageNotReadableException) = erro(HttpStatus.BAD_REQUEST, "Corpo da requisição inválido.", "corpo_invalido")

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun tipoNaoSuportado(e: HttpMediaTypeNotSupportedException) = erro(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Formato não suportado. Envie JSON.", "formato_nao_suportado")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun metodoNaoPermitido(e: HttpRequestMethodNotSupportedException) = erro(HttpStatus.METHOD_NOT_ALLOWED, "Método não permitido.", "metodo_nao_permitido")

    @ExceptionHandler(NoResourceFoundException::class)
    fun naoEncontrado(e: NoResourceFoundException) = erro(HttpStatus.NOT_FOUND, "Não encontrado.", "nao_encontrado")

    private fun erro(status: HttpStatus, mensagem: String, codigo: String) = ResponseEntity.status(status).body(mapOf("mensagem" to mensagem, "codigo" to codigo))
}
