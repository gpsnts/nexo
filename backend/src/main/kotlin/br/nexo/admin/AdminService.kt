package br.nexo.admin

import br.nexo.NexoProperties
import br.nexo.conta.AuthService
import br.nexo.conta.ErroNegocio
import br.nexo.conta.EstadoConta
import br.nexo.conta.Usuario
import br.nexo.conta.UsuarioRepository
import br.nexo.material.LimitesIa
import br.nexo.material.RemocaoArquivos
import br.nexo.seguranca.Auditoria
import br.nexo.seguranca.UsuarioAtual
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * Uma conta como o administrador a vê: só dados de cadastro e de consumo, nunca materiais, respostas ou resumos.
 *
 * @property id identificador da conta.
 * @property nome nome de exibição.
 * @property email e-mail de acesso.
 * @property status `ativo` ou `suspenso`.
 * @property admin verdadeiro se a conta é de administrador (não pode ser alterada pelo painel).
 * @property emailVerificado verdadeiro se o dono já confirmou o e-mail.
 * @property criadoEm data do cadastro.
 * @property ultimoAcessoEm último login ou renovação de sessão; nulo se nunca entrou.
 * @property cotaPersonalizada cota definida para esta conta; nulo se usa a padrão.
 * @property creditosLimite cota semanal em vigor (0 = sem IA).
 * @property creditosUsados créditos usados na semana.
 * @property percentual porcentagem da cota usada na semana (0 a 100).
 */
data class UsuarioAdminDto(
    val id: UUID, val nome: String, val email: String, val status: String, val admin: Boolean, val emailVerificado: Boolean,
    val criadoEm: Instant?, val ultimoAcessoEm: Instant?, val cotaPersonalizada: Int?, val creditosLimite: Int, val creditosUsados: Int, val percentual: Int,
)

/**
 * Ações do painel de administração. Toda chamada confere antes se quem chama é administrador (senão 404, sem revelar que a rota existe)
 * e registra a ação na trilha de auditoria. Contas de administrador não são alteradas por aqui.
 */
@Service
class AdminService(
    private val usuarios: UsuarioRepository,
    private val auth: AuthService,
    private val tx: TransactionTemplate,
    private val props: NexoProperties,
    private val jdbc: JdbcTemplate,
    private val limites: LimitesIa,
    private val arquivos: RemocaoArquivos,
    private val auditoria: Auditoria,
) {
    /**
     * Lista as contas, das mais recentes para as mais antigas. O consumo de cada conta é calculado dentro de uma transação
     * no contexto dela, então o isolamento por conta continua valendo: o painel soma só os registros de cada dona.
     * @return uma linha por conta.
     * @throws ErroNegocio 404 se quem chama não é administrador.
     */
    fun listar(): List<UsuarioAdminDto> {
        exigirAdmin()
        val acessos = jdbc.query("SELECT usuario_id, max(criado_em) FROM sessao GROUP BY usuario_id") { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getTimestamp(2).toInstant() }.toMap()
        return usuarios.findAll()
            .sortedByDescending { it.criadoEm }
            .map { u ->
                val uso = UsuarioAtual.como(u.id) { tx.execute { limites.uso() }!! }
                UsuarioAdminDto(u.id, u.nome, u.email, u.statusConta, props.admin.ehAdmin(u.email), u.emailVerificadoEm != null, u.criadoEm,
                    acessos[u.id], u.cotaCreditos, uso.creditosLimite, uso.creditosUsados, uso.percentual)
            }
    }

    /**
     * Suspende uma conta ativa: o acesso é cortado na hora (as sessões são encerradas e o token em uso deixa de valer na próxima chamada).
     * @param id conta a suspender.
     * @throws ErroNegocio 404 se não for administrador ou a conta não existir; 409 se a conta não está ativa ou é de administrador.
     */
    fun suspender(id: UUID) {
        mudarEstado(id, setOf(EstadoConta.ATIVO), EstadoConta.SUSPENSO, "admin_suspendeu")
        tx.executeWithoutResult { auth.revogarTodas(id) }
    }

    /**
     * Devolve o acesso a uma conta suspensa.
     * @param id conta a reativar.
     * @throws ErroNegocio 404 se não for administrador ou a conta não existir; 409 se a conta não está suspensa ou é de administrador.
     */
    fun reativar(id: UUID) {
        mudarEstado(id, setOf(EstadoConta.SUSPENSO), EstadoConta.ATIVO, "admin_reativou")
    }

    /**
     * Define a cota semanal de IA de uma conta.
     * @param id conta.
     * @param creditos créditos por semana (0 = sem IA); nulo volta à cota padrão.
     * @throws ErroNegocio 404 se não for administrador ou a conta não existir; 409 se for conta de administrador.
     */
    fun definirCota(id: UUID, creditos: Int?) {
        val admin = exigirAdmin()
        tx.executeWithoutResult { alvoAlteravel(id).cotaCreditos = creditos }
        auditoria.registrar("admin_cota", admin.id, "alvo=$id cota=${creditos ?: "padrao"}")
    }

    /**
     * Exclui uma conta e tudo o que é dela (em cascata); os arquivos enviados saem depois do commit.
     * @param id conta a excluir.
     * @param confirmacao deve ser a palavra EXCLUIR.
     * @throws ErroNegocio 400 se a confirmação não bater; 404 se não for administrador ou a conta não existir; 409 se for conta de administrador.
     */
    fun excluir(id: UUID, confirmacao: String) {
        val admin = exigirAdmin()
        if (confirmacao != "EXCLUIR") throw ErroNegocio(HttpStatus.BAD_REQUEST, "Digite EXCLUIR para confirmar.", "confirmacao_invalida")
        tx.executeWithoutResult {
            alvoAlteravel(id)
            usuarios.deleteById(id)
            arquivos.depoisDoCommit(pastas = listOf(id.toString()))
        }
        auditoria.registrar("admin_excluiu", admin.id, "alvo=$id")
    }

    /**
     * Troca o estado de uma conta, se ela estiver em um dos estados de partida.
     * @param id conta alvo.
     * @param de estados de partida aceitos.
     * @param para novo estado.
     * @param evento nome do evento de auditoria.
     * @return a conta já alterada.
     * @throws ErroNegocio 404, 409 conforme [alvoAlteravel] e `estado_invalido` (409) se o estado atual não permitir a ação.
     */
    private fun mudarEstado(id: UUID, de: Set<String>, para: String, evento: String): Usuario {
        val admin = exigirAdmin()
        val alvo = tx.execute {
            val u = alvoAlteravel(id)
            if (u.statusConta !in de) throw ErroNegocio(HttpStatus.CONFLICT, "Esta ação não vale para uma conta ${u.statusConta}.", "estado_invalido")
            u.also { it.statusConta = para }
        }!!
        auditoria.registrar(evento, admin.id, "alvo=$id")
        return alvo
    }

    /**
     * Carrega a conta alvo de uma ação. Deve rodar dentro de uma transação.
     * @param id conta alvo.
     * @return a conta, para alteração.
     * @throws ErroNegocio 404 se não existir; 409 `alvo_admin` se for conta de administrador.
     */
    private fun alvoAlteravel(id: UUID): Usuario {
        val u = usuarios.findById(id).orElse(null) ?: throw ErroNegocio(HttpStatus.NOT_FOUND, "Conta não encontrada.", "nao_encontrado")
        if (props.admin.ehAdmin(u.email)) throw ErroNegocio(HttpStatus.CONFLICT, "Contas de administrador não podem ser alteradas pelo painel.", "alvo_admin")
        return u
    }

    /**
     * Confere que quem chama é administrador.
     * @return a conta do administrador.
     * @throws ErroNegocio 404 se não for (a rota não revela que existe).
     */
    private fun exigirAdmin(): Usuario {
        val u = UsuarioAtual.id()?.let { usuarios.findById(it).orElse(null) }
        if (u == null || !props.admin.ehAdmin(u.email)) throw ErroNegocio(HttpStatus.NOT_FOUND, "Não encontrado.", "nao_encontrado")
        return u
    }
}
