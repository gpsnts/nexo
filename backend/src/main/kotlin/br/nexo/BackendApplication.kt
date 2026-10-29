package br.nexo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/** Aplicação Spring Boot do backend da Plataforma Nexo: API, autenticação e regras de negócio. É o único componente que acessa o banco. */
@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(NexoProperties::class)
class BackendApplication

/**
 * Ponto de entrada da aplicação.
 * @param args argumentos de linha de comando.
 */
fun main(args: Array<String>) {
    runApplication<BackendApplication>(*args)
}
