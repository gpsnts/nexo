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

@Configuration
class SegurancaConfig {
    @Bean
    fun relogio(): java.time.Clock = java.time.Clock.systemUTC()

    @Bean
    fun jwtService(p: NexoProperties) = JwtService(p.jwt.segredo, p.jwt.validadeAcesso)

    @Bean
    fun jwtDecoder(jwt: JwtService): JwtDecoder = jwt.decodificador

    @Bean
    fun passwordEncoder(p: NexoProperties): PasswordEncoder =
        PasswordEncoderLimitado(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(), p.seguranca.hashConcorrencia, p.seguranca.hashEspera)

    /** Rotas sem token. Todo o resto exige JWT; o teste de rotas confere isso. */
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
