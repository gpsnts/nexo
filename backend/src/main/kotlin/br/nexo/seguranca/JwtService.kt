package br.nexo.seguranca

import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/** JWT de acesso HS256, de vida curta (ADR 0008). */
class JwtService(segredo: String, private val validade: Duration, private val relogio: Clock = Clock.systemUTC()) {
    private val chave = SecretKeySpec(segredo.toByteArray(), "HmacSHA256")
    private val codificador: JwtEncoder = NimbusJwtEncoder(ImmutableSecret(chave))
    val decodificador = NimbusJwtDecoder.withSecretKey(chave).macAlgorithm(MacAlgorithm.HS256).build()

    init {
        require(segredo.toByteArray().size >= 32) { "NEXO_JWT_SEGREDO precisa ter ao menos 32 bytes" }
    }

    /** [sessaoId] é a família do refresh: a tela de sessões usa para marcar "este dispositivo". */
    fun emitir(usuarioId: UUID, sessaoId: UUID): String {
        val agora = relogio.instant()
        val claims = JwtClaimsSet.builder().subject(usuarioId.toString()).claim("sid", sessaoId.toString()).issuedAt(agora).expiresAt(agora.plus(validade)).build()
        return codificador.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue
    }
}
