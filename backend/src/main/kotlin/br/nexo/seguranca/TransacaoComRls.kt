package br.nexo.seguranca

import jakarta.persistence.EntityManagerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import org.hibernate.Session
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Executa o equivalente a `SET LOCAL app.usuario_id` no início de TODA transação.
 * `set_config(..., true)` é o SET LOCAL com parâmetro: vale só até o fim da transação, então não vaza no pool.
 * Sem usuário atual nada é definido e a RLS devolve 0 linhas.
 */
class GerenciadorTransacaoComRls(emf: EntityManagerFactory) : JpaTransactionManager(emf) {
    override fun doBegin(transaction: Any, definition: TransactionDefinition) {
        super.doBegin(transaction, definition)
        val usuarioId = UsuarioAtual.id() ?: return
        val em = (TransactionSynchronizationManager.getResource(entityManagerFactory!!) as EntityManagerHolder).entityManager
        // Tipos explícitos: a inferência arrasta anotações de nulidade inacessíveis (aviso que vira erro no Kotlin 2.4).
        em.unwrap(Session::class.java).doWork { conexao: Connection ->
            conexao.prepareStatement("select set_config('app.usuario_id', ?, true)").use { ps: PreparedStatement ->
                ps.setString(1, usuarioId.toString())
                ps.execute()
            }
        }
    }
}

/** Registra o gerenciador de transação que aplica a RLS. */
@Configuration
class TransacaoConfig {
    /**
     * Gerenciador de transação que define `app.usuario_id` no início de cada transação.
     * @param emf fábrica de gerenciadores de entidade.
     * @return o [GerenciadorTransacaoComRls].
     */
    @Bean
    fun transactionManager(emf: EntityManagerFactory) = GerenciadorTransacaoComRls(emf)
}
