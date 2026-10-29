import { expect, test } from '@playwright/test'
import { criarContaEEntrar, enviarMaterial, fixture, reiniciarBackend, sql } from '../ajudas'

// Provedores de IA falsos: o fluxo é o do provedor real; o conteúdo é só um eco do material.
test('tópico de material: gera resumo e questões com barra, grava só as aprovadas e lista sem o gabarito @celular', async ({ page }) => {
  await criarContaEEntrar(page)
  await enviarMaterial(page, fixture('apostila-50-paginas.pdf'))
  await expect(page.getByRole('heading', { name: 'Tópicos do material' })).toBeVisible({ timeout: 45_000 })
  await page.getByRole('button', { name: 'Estudar o tópico 1: resumo e questões' }).click()
  await expect(page).toHaveURL(/\/topicos\/[0-9a-f-]{36}/)
  await expect(page.getByText(/limites deste tópico ficam fixos/)).toBeVisible()

  await page.getByRole('button', { name: 'Gerar resumo' }).click()
  await expect(page.getByText(/Resumo de demonstração/)).toBeVisible({ timeout: 30_000 })

  await page.getByLabel('Quantas questões gerar').fill('3')
  await page.getByRole('button', { name: 'Gerar questões' }).click()
  await expect(page.getByText(/3 questões gravadas/)).toBeVisible({ timeout: 45_000 })
  await expect(page.getByRole('list', { name: 'Questões do tópico' }).locator('li.correta')).toHaveCount(0) // o gabarito só aparece depois de responder

  await page.reload() // vem do banco
  await expect(page.getByRole('heading', { name: /Questões \(3\)/ })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})

test('tópico livre: gera pelo conhecimento geral do modelo', async ({ page }) => {
  await criarContaEEntrar(page)
  await page.goto('/novo-estudo')
  await page.getByLabel('Tópico', { exact: true }).fill('Movimento Retilíneo Uniforme')
  await page.getByRole('button', { name: 'Registrar tópico' }).click()
  await expect(page).toHaveURL(/\/topicos\/[0-9a-f-]{36}/) // registrar leva direto ao tópico
  await expect(page.getByText(/Tópico livre/)).toBeVisible()
  await page.getByLabel('Quantas questões gerar').fill('2')
  await page.getByRole('button', { name: 'Gerar questões' }).click()
  await expect(page.getByText(/2 questões gravadas/)).toBeVisible({ timeout: 45_000 })
})

test('cancelar uma geração em andamento (modelo lento): avisa, não grava nada e libera novo pedido', async ({ page }) => {
  await criarContaEEntrar(page)
  await page.goto('/novo-estudo')
  await page.getByLabel('Tópico', { exact: true }).fill('Termodinâmica LENTO') // o modelo falso demora 6 s por chamada neste tópico
  await page.getByRole('button', { name: 'Registrar tópico' }).click()
  await expect(page).toHaveURL(/\/topicos\/[0-9a-f-]{36}/) // registrar leva direto ao tópico
  await page.getByLabel('Quantas questões gerar').fill('2')
  await page.getByRole('button', { name: 'Gerar questões' }).click()
  await expect(page.getByRole('progressbar', { name: 'Andamento da geração' })).toBeVisible()
  await page.getByRole('button', { name: 'Cancelar geração' }).click()
  await expect(page.getByText('Geração cancelada.')).toBeVisible()
  await expect(page.getByRole('progressbar', { name: 'Andamento da geração' })).toHaveCount(0)
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Gerar questões' })).toBeEnabled()
  // a chamada lenta ainda termina no serviço; a tarefa vê o cancelamento e para sem gravar
  await page.waitForTimeout(9_000)
  await page.reload()
  await expect(page.getByRole('heading', { name: /Questões \(0\)/ })).toBeVisible()
  await expect(page.getByText(/Última geração/)).toHaveCount(0)
})

test('backend reiniciado no meio de uma geração: a tela não congela e dá para cancelar', async ({ page }) => {
  test.setTimeout(150_000)
  await criarContaEEntrar(page)
  await page.goto('/novo-estudo')
  await page.getByLabel('Tópico', { exact: true }).fill('Mecânica LENTO')
  await page.getByRole('button', { name: 'Registrar tópico' }).click()
  await expect(page).toHaveURL(/\/topicos\/[0-9a-f-]{36}/) // registrar leva direto ao tópico
  await page.getByRole('button', { name: 'Gerar questões' }).click()
  await expect(page.getByRole('progressbar', { name: 'Andamento da geração' })).toBeVisible()
  await reiniciarBackend() // a tarefa em segundo plano morre com o processo; o registro fica aberto no banco
  // a tela absorve a queda: ou segue acompanhando, ou oferece "Tentar de novo" (nunca fica com barra congelada e erro juntos)
  await expect(page.getByRole('button', { name: /Cancelar geração|Tentar de novo/ })).toBeVisible({ timeout: 30_000 })
  const tentar = page.getByRole('button', { name: 'Tentar de novo' })
  if (await tentar.isVisible()) {
    await expect(page.getByRole('progressbar', { name: 'Andamento da geração' })).toHaveCount(0)
    await tentar.click()
  }
  await expect(page.getByRole('progressbar', { name: 'Andamento da geração' })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await page.getByRole('button', { name: 'Cancelar geração' }).click() // cancelar também libera um tópico preso por uma geração perdida
  await expect(page.getByText('Geração cancelada.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Gerar questões' })).toBeEnabled()
})

test('início: materiais e tópicos livres na mesma lista, com o tipo de cada um @celular', async ({ page }) => {
  await criarContaEEntrar(page)
  await enviarMaterial(page, fixture('apostila-50-paginas.pdf'))
  await expect(page.getByRole('heading', { name: 'Tópicos do material' })).toBeVisible({ timeout: 45_000 })
  await page.goto('/novo-estudo')
  await page.getByLabel('Tópico', { exact: true }).fill('Ótica')
  await page.getByRole('button', { name: 'Registrar tópico' }).click()
  await expect(page).toHaveURL(/\/topicos\/[0-9a-f-]{36}/)
  await page.getByRole('button', { name: 'Início', exact: true }).click()
  const lista = page.getByRole('list', { name: 'Estudos' })
  await expect(lista.getByRole('listitem')).toHaveCount(2)
  await expect(lista.getByRole('listitem').first()).toContainText('Tópico livre') // o mais novo primeiro
  await expect(lista.getByRole('listitem').last()).toContainText('Material')
  await expect(page.getByText(/"Geral" guarda os estudos que não estão em nenhuma pasta/)).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})

test('tópico ou material que não existe: aviso claro, sem "Tentar de novo", e volta ao início', async ({ page }) => {
  await criarContaEEntrar(page)
  const inexistente = '99999999-9999-9999-9999-999999999999'
  for (const [rota, texto] of [[`/topicos/${inexistente}`, 'Este tópico não existe'], [`/materiais/${inexistente}`, 'Este material não existe']]) {
    await page.goto(rota)
    await expect(page.getByRole('alert')).toContainText(texto)
    await expect(page.getByRole('button', { name: 'Tentar de novo' })).toHaveCount(0)
  }
  await page.getByRole('button', { name: 'Voltar ao início' }).click()
  await expect(page).toHaveURL('/')
})

test('envio de arquivo mostra o botão em português e o nome escolhido @celular', async ({ page }) => {
  await criarContaEEntrar(page)
  await page.goto('/novo-estudo')
  await expect(page.getByText('Escolher arquivo')).toBeVisible()
  await expect(page.getByText('Nenhum arquivo escolhido')).toBeVisible()
  await page.getByLabel('Arquivo').setInputFiles(fixture('apostila-50-paginas.pdf'))
  await expect(page.getByText(/apostila-50-paginas\.pdf · /)).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})

test('URL e código sem espaços não estouram a página (resumo e questões) @celular', async ({ page }) => {
  await criarContaEEntrar(page)
  await page.goto('/novo-estudo')
  const nome = `Ponteiros em C ${Date.now()}` // nome único: o SQL abaixo localiza o tópico por ele
  await page.getByLabel('Tópico', { exact: true }).fill(nome)
  await page.getByRole('button', { name: 'Registrar tópico' }).click()
  await expect(page.getByRole('heading', { name: nome })).toBeVisible()
  const url = 'https://exemplo.com.br/um/caminho/muito/longo/sem/espacos/que/nao/quebra/naturalmente/' + 'a'.repeat(120)
  const codigo = 'int *p = malloc(sizeof(int) * 1024 * 1024 * 1024 * 1024 * 1024); free(p); /* ' + 'x'.repeat(90) + ' */'
  sql(`
    INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding)
    SELECT gen_random_uuid(), usuario_id, id, 'Qual a fonte? ${url} E o código ${codigo} faz o quê?',
           '["${url}","${codigo}","Curta","${'palavra '.repeat(40)}"]'::jsonb, 1, encode(sha256('longa'::bytea),'hex'), array_fill(0.01::real,ARRAY[1536])::vector, 'm'
      FROM topico WHERE nome = '${nome}';
    INSERT INTO resumo(id,usuario_id,topico_id,conteudo)
    SELECT gen_random_uuid(), usuario_id, id, E'Veja ${url}\\n\\n${codigo}\\n\\n' || repeat('Texto comum. ', 40) FROM topico WHERE nome = '${nome}';`)
  await page.reload()
  await page.getByText(/Banco de questões/).click() // as questões ficam num banco recolhido
  await expect(page.getByText(/Qual a fonte\?/)).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth), 'rolagem horizontal').toBe(true)
  // nada passa da borda do cartão que o contém
  const vazou = await page.evaluate(() => Array.from(document.querySelectorAll('.secao .texto-longo, .questao')).filter((e) => e.scrollWidth > e.clientWidth + 1).length)
  expect(vazou).toBe(0)
})

test('o cadastro e o envio avisam que o conteúdo é processado por um serviço externo de IA @celular', async ({ page }) => {
  await page.goto('/criar-conta')
  await expect(page.getByText(/processado por um serviço externo de inteligência artificial/)).toBeVisible()
  await criarContaEEntrar(page)
  await page.goto('/novo-estudo')
  await expect(page.getByText(/O texto do material é enviado a um serviço externo de inteligência artificial/)).toBeVisible()
  await expect(page.getByText(/Envie apenas material que você pode compartilhar/)).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})
