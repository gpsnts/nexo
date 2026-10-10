package br.nexo.conta

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

// UUID gerado pelo backend; usuario_id é UUID simples, sem @ManyToOne (regra 3). save() faz merge (um SELECT a mais) porque o id é atribuído.
// DynamicUpdate: gravar só o que mudou, para não sobrescrever o contador de falhas de login (atualizado por UPDATE atômico).
@Entity
@DynamicUpdate
@Table(name = "usuario")
class Usuario(
    @field:Id @field:Column(name = "id") val id: UUID,
    @field:Column(name = "nome") var nome: String,
    @field:Column(name = "email") var email: String,
    @field:Column(name = "senha_hash") var senhaHash: String,
    @field:Column(name = "falhas_login") var falhasLogin: Int = 0,
    @field:Column(name = "bloqueado_ate") var bloqueadoAte: Instant? = null,
    @field:Column(name = "email_verificado_em") var emailVerificadoEm: Instant? = null,
    @field:Column(name = "criado_em", insertable = false, updatable = false) val criadoEm: Instant? = null,
)

@Entity
@Table(name = "assunto")
class Assunto(
    @field:Id @field:Column(name = "id") val id: UUID,
    @field:Column(name = "usuario_id") val usuarioId: UUID,
    @field:Column(name = "nome") var nome: String,
    @field:Column(name = "assunto_padrao") val assuntoPadrao: Boolean,
)

@Entity
@Table(name = "sessao")
class SessaoRefresh(
    @field:Id @field:Column(name = "id") val id: UUID,
    @field:Column(name = "usuario_id") val usuarioId: UUID,
    @field:Column(name = "familia_id") val familiaId: UUID,
    @field:Column(name = "token_hash") val tokenHash: String,
    @field:Column(name = "expira_em") val expiraEm: Instant,
    @field:Column(name = "revogado_em") var revogadoEm: Instant? = null,
    @field:Column(name = "dispositivo") val dispositivo: String? = null,
    @field:Column(name = "ip") val ip: String? = null,
    @field:Column(name = "inicio_em") val inicioEm: Instant = Instant.now(),
    @field:Column(name = "criado_em", insertable = false, updatable = false) val criadoEm: Instant? = null,
)

@Entity
@Table(name = "token_conta")
class TokenConta(
    @field:Id @field:Column(name = "id") val id: UUID,
    @field:Column(name = "usuario_id") val usuarioId: UUID,
    @field:Column(name = "tipo") val tipo: String,
    @field:Column(name = "token_hash") val tokenHash: String,
    @field:Column(name = "novo_email") val novoEmail: String?,
    @field:Column(name = "expira_em") val expiraEm: Instant,
    @field:Column(name = "usado_em") var usadoEm: Instant? = null,
    @field:Column(name = "criado_em", insertable = false, updatable = false) val criadoEm: Instant? = null,
)

interface UsuarioRepository : JpaRepository<Usuario, UUID> {
    fun findByEmail(email: String): Usuario?

    /** Atômico: a 5ª falha (valor configurável) zera o contador e bloqueia até [ate]. */
    @Modifying
    @Transactional
    @Query(
        """update usuario set
             falhas_login = case when falhas_login + 1 >= :max then 0 else falhas_login + 1 end,
             bloqueado_ate = case when falhas_login + 1 >= :max then :ate else bloqueado_ate end
           where id = :id""",
        nativeQuery = true,
    )
    fun registrarFalha(@Param("id") id: UUID, @Param("max") max: Int, @Param("ate") ate: Instant)

    /** Conta que nunca confirmou o e-mail não entra e não guarda dados: apagar libera o e-mail para o dono de verdade. */
    @Modifying
    @Transactional
    @Query("delete from Usuario u where u.emailVerificadoEm is null and u.criadoEm < :limite")
    fun apagarNaoVerificadas(@Param("limite") limite: Instant): Int

    @Modifying
    @Transactional
    @Query("update Usuario u set u.falhasLogin = 0, u.bloqueadoAte = null where u.id = :id")
    fun limparFalhas(@Param("id") id: UUID)
}

interface AssuntoRepository : JpaRepository<Assunto, UUID>

interface SessaoRepository : JpaRepository<SessaoRefresh, UUID> {
    fun findByTokenHash(hash: String): SessaoRefresh?

    /** Uma linha ativa por família = uma sessão na tela. */
    @Query("select s from SessaoRefresh s where s.usuarioId = :usuario and s.revogadoEm is null and s.expiraEm > :agora order by s.criadoEm desc")
    fun ativas(@Param("usuario") usuario: UUID, @Param("agora") agora: Instant): List<SessaoRefresh>

    /** Sempre filtrada pelo dono: sessao não tem RLS, então é esta cláusula que impede encerrar a sessão de outra pessoa. Devolve 0 se não for dele. */
    @Modifying
    @Transactional
    @Query("update SessaoRefresh s set s.revogadoEm = :agora where s.usuarioId = :usuario and s.familiaId = :familia and s.revogadoEm is null")
    fun revogarFamiliaDoUsuario(@Param("usuario") usuario: UUID, @Param("familia") familia: UUID, @Param("agora") agora: Instant): Int

    @Modifying
    @Transactional
    @Query("update SessaoRefresh s set s.revogadoEm = :agora where s.usuarioId = :usuario and s.familiaId <> :manter and s.revogadoEm is null")
    fun revogarOutras(@Param("usuario") usuario: UUID, @Param("manter") manter: UUID, @Param("agora") agora: Instant): Int

    /** Atômico: devolve 0 se o token já tinha sido usado (base da detecção de reuso). */
    @Modifying
    @Transactional
    @Query("update SessaoRefresh s set s.revogadoEm = :agora where s.id = :id and s.revogadoEm is null")
    fun revogar(@Param("id") id: UUID, @Param("agora") agora: Instant): Int

    @Modifying
    @Transactional
    @Query("update SessaoRefresh s set s.revogadoEm = :agora where s.familiaId = :familia and s.revogadoEm is null")
    fun revogarFamilia(@Param("familia") familia: UUID, @Param("agora") agora: Instant)

    @Modifying
    @Transactional
    @Query("delete from SessaoRefresh s where s.expiraEm < :agora")
    fun apagarVencidas(@Param("agora") agora: Instant): Int

    @Modifying
    @Transactional
    @Query("update SessaoRefresh s set s.revogadoEm = :agora where s.usuarioId = :usuario and s.revogadoEm is null")
    fun revogarTodas(@Param("usuario") usuario: UUID, @Param("agora") agora: Instant)
}

interface TokenContaRepository : JpaRepository<TokenConta, UUID> {
    fun findByTokenHashAndTipo(hash: String, tipo: String): TokenConta?

    fun countByUsuarioIdAndTipoAndCriadoEmAfter(usuarioId: UUID, tipo: String, desde: Instant): Long

    @Modifying
    @Transactional
    @Query("delete from TokenConta t where t.expiraEm < :agora")
    fun apagarVencidos(@Param("agora") agora: Instant): Int

    @Modifying
    @Transactional
    @Query("update TokenConta t set t.usadoEm = :agora where t.usuarioId = :usuario and t.tipo = :tipo and t.usadoEm is null")
    fun invalidarPendentes(@Param("usuario") usuario: UUID, @Param("tipo") tipo: String, @Param("agora") agora: Instant)
}
