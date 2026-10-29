package br.nexo.material

import br.nexo.NexoProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/** Custo estimado das chamadas ao modelo de linguagem (soma tokens e custo ao registro da operação; o limite de gasto lê esse custo). */
@Component
class PrecosIa(private val props: NexoProperties) {
    private val preco get() = props.geracao.precoLinguagem

    /**
     * Provedor falso e a divisão por regras (sem modelo) não custam nada.
     * @param modelo nome do modelo gravado.
     * @return verdadeiro se a operação não gera custo.
     */
    fun semCusto(modelo: String) = "falso" in modelo || modelo == Indexador.MODELO_ESTRUTURA

    /**
     * Diz se o preço do modelo de linguagem real está configurado.
     * @return verdadeiro se há preço de entrada e de saída.
     */
    fun precoConfigurado() = preco.entradaUsdPorMilhao != null && preco.saidaUsdPorMilhao != null

    /**
     * Custo estimado de uma chamada ao modelo de linguagem.
     * @param modelo modelo usado.
     * @param tokensEntrada tokens do prompt.
     * @param tokensSaida tokens gerados.
     * @return custo em US$, com 6 casas (zero para modelo sem custo).
     */
    fun custoLinguagem(modelo: String, tokensEntrada: Int, tokensSaida: Int): BigDecimal {
        if (semCusto(modelo)) return BigDecimal.ZERO
        val e = preco.entradaUsdPorMilhao ?: return BigDecimal.ZERO   // a recusa de `ProcessamentoHttp.modelos()` impede chegar aqui sem preço
        val s = preco.saidaUsdPorMilhao ?: return BigDecimal.ZERO
        return (e.multiply(BigDecimal(tokensEntrada)) + s.multiply(BigDecimal(tokensSaida))).divide(BigDecimal(1_000_000), 6, RoundingMode.HALF_UP)
    }
}
