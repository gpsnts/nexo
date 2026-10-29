-- O estudante escolhe o grau de dificuldade ao gerar questões (fácil, média ou difícil). As questões que já existem ficam como 'media'.
ALTER TABLE questao ADD COLUMN dificuldade text NOT NULL DEFAULT 'media' CHECK (dificuldade IN ('facil', 'media', 'dificil'));
