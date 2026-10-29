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

// UUID gerado pelo backend; usuario_id é UUID simples, sem @ManyToOne. save() faz merge (um SELECT a mais) porque o id é atribuído.
// DynamicUpdate: gravar só o que mudou, para não sobrescrever o contador de falhas de login (atualizado por UPDATE atômico).
/** Conta do estudante (tabela `usuario`, sem RLS: é lida antes de haver usuário autenticado). */
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

/** Assunto (pasta) de um usuário; o "Geral" é o padrão e não pode ser excluído. */
@Entity
@Table(name = "assunto")
class Assunto(
    @field:Id @field:Column(name = "id") val id: UUID,
    @field:Column(name = "usuario_id") val usuarioId: UUID,
    @field:Column(name = "nome") var nome: String,
    @field:Column(name = "assunto_padrao") val assuntoPadrao: Boolean,
)

/** Sessão de refresh: uma linha por token; as linhas de uma mesma `familiaId` formam uma sessão na tela (tabela `sessao`, sem RLS). */
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

/** Token de uso único enviado por e-mail (tabela `token_conta`, sem RLS); só o hash é gravado. */
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

/** Acesso às contas. */
interface UsuarioRepository : JpaRepository<Usuario, UUID> {
    /**
     * Busca a conta pelo e-mail.
     * @param email e-mail normalizado (minúsculas).
     * @return a conta, ou nulo se não existir.
     */
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
    /**
     * Atômico: a 5ª falha (valor configurável) zera o contador e bloqueia até [ate].
     * @param id conta.
     * @param max falhas seguidas que bloqueiam.
     * @param ate fim do bloqueio.
     */
    fun registrarFalha(@Param("id") id: UUID, @Param("max") max: Int, @Param("ate") ate: Instant)

    /**
     * Conta que nunca confirmou o e-mail não entra e não guarda dados: apagar libera o e-mail para o dono de verdade.
     * @param limite contas criadas antes deste instante são apagadas.
     * @return quantidade apagada.
     */
    @Modifying
    @Transactional
    @Query("delete from Usuario u where u.emailVerificadoEm is null and u.criadoEm < :limite")
    fun apagarNaoVerificadas(@Param("limite") limite: Instant): Int

    /**
     * Zera o contador de falhas e o bloqueio após um login certo.
     * @param id conta.
     */
    @Modifying
    @Transactional
    @Query("update Usuario u set u.falhasLogin = 0, u.bloqueadoAte = null where u.id = :id")
    fun limparFalhas(@Param("id") id: UUID)
}

/** Acesso aos assuntos (pastas) do usuário. */
interface AssuntoRepository : JpaRepository<Assunto, UUID>

/** Acesso às sessões de refresh. */
interface SessaoRepository : JpaRepository<SessaoRefresh, UUID> {
    /**
     * Busca a sessão pelo hash do refresh.
     * @param hash SHA-256 do token bruto.
     * @return a sessão, ou nulo se não existir.
     */
    fun findByTokenHash(hash: String): SessaoRefresh?

    /**
     * Uma linha ativa por família = uma sessão na tela.
     * @param usuario dono das sessões.
     * @param agora instante usado para descartar as expiradas.
     * @return sessões não revogadas e não expiradas, da mais nova para a mais antiga.
     */
    @Query("select s from SessaoRefresh s where s.usuarioId = :usuario and s.revogadoEm is null and s.expiraEm > :agora order by s.criadoEm desc")
    fun ativas(@Param("usuario") usuario: UUID, @Param("agora") agora: Instant): List<SessaoRefresh>

    /**
     * Sempre filtrada pelo dono: sessao não tem RLS, então é esta cláusula que impede encerrar a sessão de outra pessoa.
     * @param usuario dono da sessão.
     * @param familia família a encerrar.
     * @param agora instante da revogação.
     * @return linhas revogadas; 0 se a família não for dele.
     */
    @Modifying
    @Transactional
    @Query("update SessaoRefresh s set s.revogadoEm = :agora where s.usuarioId = :usuario and s.familiaId = :familia and s.revogadoEm is null")
    fun revogarFamiliaDoUsuario(@Param("usuario") usuario: UUID, @Param("familia") familia: UUID, @Param("agora") agora: Instant): Int

    /**
     * Revoga as sessões do usuário, menos a família indicada.
     * @param usuario dono das sessões.
     * @param manter família que continua ativa.
     * @param agora instante da revogação.
     * @return linhas revogadas.
     */
    @Modifying
    @Transactional
    @Query("update SessaoRefresh s set s.revogadoEm = :agora where s.usuarioId = :usuario and s.familiaId <> :manter and s.revogadoEm is null")
    fun revogarOutras(@Param("usuario") usuario: UUID, @Param("manter") manter: UUID, @Param("agora") agora: Instant): Int

    /**
     * Atômico: devolve 0 se o token já tinha sido usado (base da detecção de reuso).
     * @param id linha de refresh.
     * @param agora instante da revogação.
     * @return 1 se revogou; 0 se já estava revogada.
     */
    @Modifying
    @Transactional
    @Query("update SessaoRefresh s set s.revogadoEm = :agora where s.id = :id and s.revogadoEm is null")
    fun revogar(@Param("id") id: UUID, @Param("agora") agora: Instant): Int

    /**
     * Revoga todas as linhas de uma família (logout ou suspeita de reuso).
     * @param familia família.
     * @param agora instante da revogação.
     */
    @Modifying
    @Transactional
    @Query("update SessaoRefresh s set s.revogadoEm = :agora where s.familiaId = :familia and s.revogadoEm is null")
    fun revogarFamilia(@Param("familia") familia: UUID, @Param("agora") agora: Instant)

    /**
     * Apaga as sessões expiradas.
     * @param agora instante de corte.
     * @return quantidade apagada.
     */
    @Modifying
    @Transactional
    @Query("delete from SessaoRefresh s where s.expiraEm < :agora")
    fun apagarVencidas(@Param("agora") agora: Instant): Int

    /**
     * Revoga todas as sessões do usuário.
     * @param usuario dono das sessões.
     * @param agora instante da revogação.
     */
    @Modifying
    @Transactional
    @Query("update SessaoRefresh s set s.revogadoEm = :agora where s.usuarioId = :usuario and s.revogadoEm is null")
    fun revogarTodas(@Param("usuario") usuario: UUID, @Param("agora") agora: Instant)
}

/** Acesso aos tokens de conta. */
interface TokenContaRepository : JpaRepository<TokenConta, UUID> {
    /**
     * Busca o token pelo hash e pelo tipo.
     * @param hash SHA-256 do token bruto.
     * @param tipo finalidade do token.
     * @return o token, ou nulo se não existir.
     */
    fun findByTokenHashAndTipo(hash: String, tipo: String): TokenConta?

    /**
     * Conta os tokens do tipo criados depois do instante (limite por hora).
     * @param usuarioId usuário.
     * @param tipo finalidade do token.
     * @param desde início da janela.
     * @return quantidade encontrada.
     */
    fun countByUsuarioIdAndTipoAndCriadoEmAfter(usuarioId: UUID, tipo: String, desde: Instant): Long

    /**
     * Apaga os tokens expirados.
     * @param agora instante de corte.
     * @return quantidade apagada.
     */
    @Modifying
    @Transactional
    @Query("delete from TokenConta t where t.expiraEm < :agora")
    fun apagarVencidos(@Param("agora") agora: Instant): Int

    /**
     * Marca como usados os tokens pendentes do usuário e tipo, para só o mais novo valer.
     * @param usuario dono dos tokens.
     * @param tipo finalidade.
     * @param agora instante da invalidação.
     */
    @Modifying
    @Transactional
    @Query("update TokenConta t set t.usadoEm = :agora where t.usuarioId = :usuario and t.tipo = :tipo and t.usadoEm is null")
    fun invalidarPendentes(@Param("usuario") usuario: UUID, @Param("tipo") tipo: String, @Param("agora") agora: Instant)
}
