package br.nexo.seguranca

import br.nexo.NexoProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.SecurityFilterChain

/** Configuração de segurança: relógio, JWT, codificador de senha (Argon2id limitado) e cadeia de filtros HTTP. */
@Configuration
class SegurancaConfig {
    /**
     * Relógio UTC, injetável nos testes.
     * @return relógio do sistema em UTC.
     */
    @Bean
    fun relogio(): java.time.Clock = java.time.Clock.systemUTC()

    /**
     * Serviço que emite e valida os JWT de acesso.
     * @param p parâmetros de negócio.
     * @return o [JwtService] configurado.
     */
    @Bean
    fun jwtService(p: NexoProperties) = JwtService(p.jwt.segredo, p.jwt.validadeAcesso)

    /**
     * Decodificador usado pelo Spring Security para validar o JWT.
     * @param jwt serviço de JWT.
     * @return o decodificador do serviço.
     */
    @Bean
    fun jwtDecoder(jwt: JwtService): JwtDecoder = jwt.decodificador

    /**
     * Codificador de senha Argon2id com teto de hashes simultâneos.
     * @param p parâmetros de negócio.
     * @return o codificador.
     */
    @Bean
    fun passwordEncoder(p: NexoProperties): PasswordEncoder =
        PasswordEncoderLimitado(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(), p.seguranca.hashConcorrencia, p.seguranca.hashEspera)

    /**
     * Rotas sem token. Todo o resto exige JWT; o teste de rotas confere isso.
     * @param http construtor de segurança HTTP.
     * @return a cadeia de filtros (stateless, sem CSRF, JWT como servidor de recursos).
     */
    @Bean
    fun filtros(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() } // API stateless com token no cabeçalho; o cookie do refresh é SameSite=Strict e só vai às rotas /auth
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            // O despacho de ERRO (/error) precisa passar: sem isso um JSON malformado numa rota pública virava 401 mudo.
            it.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll().requestMatchers(
                "/auth/cadastro", "/auth/login", "/auth/refresh", "/auth/logout", "/auth/esqueci-senha",
                "/auth/redefinir-senha", "/auth/confirmar-email", "/auth/verificar-email", "/auth/reenviar-verificacao", "/actuator/health",
            ).permitAll().anyRequest().authenticated()
        }
        .oauth2ResourceServer { it.jwt { } }
        .build()
}
